"""Behavioral packaging checks: reject later misaligned segments and stale archives."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import struct
import tempfile
import unittest
import warnings
from unittest.mock import patch
from zipfile import ZipFile

spec = importlib.util.spec_from_file_location("cores", Path(__file__).with_name("verify-android-cores.py"))
cores = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cores)
build_spec = importlib.util.spec_from_file_location("builder", Path(__file__).with_name("build-xray-android.py"))
builder = importlib.util.module_from_spec(build_spec)
build_spec.loader.exec_module(builder)


def elf(alignments=(16384, 16384), machine=183):
    data = bytearray(64 + 56 * len(alignments))
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<HH", data, 16, 3, machine)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, len(alignments))
    for i, alignment in enumerate(alignments):
        struct.pack_into("<IIQQQQQQ", data, 64 + 56*i, 1, 5, 0, 0, 0, 0, 0, alignment)
    return bytes(data)


class AndroidCoreTest(unittest.TestCase):
    def test_accepts_all_aligned_loads(self):
        cores.verify_elf(elf(), "arm64-v8a")

    def test_rejects_second_misaligned_load(self):
        with self.assertRaisesRegex(ValueError, "alignment"):
            cores.verify_elf(elf((16384, 4096)), "arm64-v8a")

    def test_rejects_no_loads_and_truncation(self):
        for data in (elf(()), elf()[:-1], b"not an elf"):
            with self.subTest(data=data[:8]), self.assertRaises(ValueError):
                cores.verify_elf(data, "arm64-v8a")

    def test_rejects_wrong_abi(self):
        with self.assertRaises(ValueError):
            cores.verify_elf(elf(machine=62), "arm64-v8a")

    def test_archive_requires_both_cores_and_exact_staged_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stage = root / "jni" / "arm64-v8a"
            stage.mkdir(parents=True)
            for name in cores.CORE_NAMES:
                (stage / name).write_bytes(elf())
            for extension, prefix in (("apk", "lib"), ("aab", "base/lib")):
                archive = root / f"app.{extension}"
                for mode in ("valid", "missing", "changed", "duplicate"):
                    with self.subTest(extension=extension, mode=mode):
                        with ZipFile(archive, "w") as zipped:
                            for name in cores.CORE_NAMES:
                                if mode == "missing" and name == "libxraycore.so":
                                    continue
                                value = elf() + (b"stale" if mode == "changed" else b"")
                                zipped.writestr(f"{prefix}/arm64-v8a/{name}", value)
                            if mode == "duplicate":
                                with warnings.catch_warnings():
                                    warnings.simplefilter("ignore", UserWarning)
                                    zipped.writestr(f"{prefix}/arm64-v8a/libxraycore.so", elf())
                        if mode == "valid":
                            cores.verify_stage(root / "jni", ["arm64-v8a"], archive)
                        else:
                            with self.assertRaises(ValueError):
                                cores.verify_stage(root / "jni", ["arm64-v8a"], archive)


class AndroidBuildIsolationTest(unittest.TestCase):
    def run_builder(self, root, revision="28.2.13676358", inherited=None):
        source, ndk = root / "source", root / "ndk"
        source.mkdir(exist_ok=True)
        ndk.mkdir(exist_ok=True)
        if revision is not None:
            (ndk / "source.properties").write_text(f"Pkg.Revision = {revision}\n")
        (root / "scripts").mkdir(exist_ok=True)
        shutil.copyfile(Path(__file__).with_name("verify-android-cores.py"), root / "scripts/verify-android-cores.py")
        environments = []

        # Substitute only the external git/compiler processes. The real build
        # driver writes and validates the resulting ELF and provenance receipt.
        def output(command, **kwargs):
            if command == ["git", "rev-parse", "HEAD"]:
                return builder.PINS["commit"] + "\n"
            if command == ["git", "status", "--porcelain"]:
                return ""
            if command == ["git", "archive", "HEAD"]:
                return b"synthetic source archive"
            if command == ["go", "env", "GOVERSION"]:
                environments.append(kwargs["env"].copy())
                return "go1.27.1\n"
            raise AssertionError(f"unexpected external command: {command}")

        def compile_core(command, **kwargs):
            self.assertEqual(command[:2], ["go", "build"])
            environments.append(kwargs["env"].copy())
            Path(command[command.index("-o") + 1]).write_bytes(elf())

        argv = ["builder", "--abis", "arm64-v8a", "--source", str(source),
                "--ndk", str(ndk), "--out", str(root / "out")]
        with patch.object(builder, "ROOT", root), patch.object(builder.sys, "argv", argv), \
                patch.dict(os.environ, inherited or {}, clear=True), \
                patch.object(builder.subprocess, "check_output", side_effect=output), \
                patch.object(builder.subprocess, "run", side_effect=compile_core):
            builder.main()
        return environments, json.loads((root / "build/xray-receipts/arm64-v8a.json").read_text())

    def test_ambient_go_and_compiler_settings_cannot_reach_build(self):
        inherited = {
            "GOENV": "custom-go-env", "GOFLAGS": "-tags=changed", "GOAMD64": "v4",
            "GOARM64": "v9.5", "GOEXPERIMENT": "changed", "GOROOT": "wrong-root",
            "CGO_CFLAGS": "-DCHANGED", "CGO_CPPFLAGS": "-DCHANGED",
            "CGO_CXXFLAGS": "-DCHANGED", "CGO_LDFLAGS": "-Lchanged",
            "CGO_CFLAGS_ALLOW": ".*", "CPATH": "changed", "LIBRARY_PATH": "changed",
            "CCC_OVERRIDE_OPTIONS": "changed", "HTTPS_PROXY": "http://proxy.invalid:8080",
        }
        with tempfile.TemporaryDirectory() as directory:
            environments, receipt = self.run_builder(Path(directory), inherited=inherited)
        for env in environments:
            self.assertEqual(env.get("GOENV"), "off")
            self.assertEqual(env.get("GOFLAGS", ""), "")
            self.assertEqual(env.get("GOEXPERIMENT", ""), "")
            self.assertEqual(env.get("GOAMD64"), "v1")
            self.assertEqual(env.get("GOARM64"), "v8.0")
            self.assertEqual(env["GOTOOLCHAIN"], "go1.27.1")
            for key in ("GOROOT", "CGO_CFLAGS", "CGO_CPPFLAGS", "CGO_CXXFLAGS", "CGO_LDFLAGS",
                        "CGO_CFLAGS_ALLOW", "CPATH", "LIBRARY_PATH", "CCC_OVERRIDE_OPTIONS"):
                self.assertNotIn(key, env)
            self.assertEqual(env["HTTPS_PROXY"], "http://proxy.invalid:8080")
        self.assertEqual(receipt.get("ndk"), "28.2.13676358")

    def test_wrong_or_missing_ndk_revision_is_rejected_before_build(self):
        for revision in ("28.1.13356709", "29.0.0", None):
            with self.subTest(revision=revision), tempfile.TemporaryDirectory() as directory:
                with self.assertRaisesRegex(SystemExit, "NDK"):
                    self.run_builder(Path(directory), revision=revision)
                self.assertFalse((Path(directory) / "out/arm64-v8a/libxraycore.so").exists())


if __name__ == "__main__":
    unittest.main()
