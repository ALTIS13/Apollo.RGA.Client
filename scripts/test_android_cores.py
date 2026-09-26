"""Behavioral packaging checks: reject later misaligned segments and stale archives."""
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import warnings
from zipfile import ZipFile

spec = importlib.util.spec_from_file_location("cores", Path(__file__).with_name("verify-android-cores.py"))
cores = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cores)


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


if __name__ == "__main__":
    unittest.main()
