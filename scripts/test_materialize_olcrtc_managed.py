"""Offline regression tests for the pinned managed OlcRTC patch carrier."""

from __future__ import annotations

import json
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


SCRIPTS = Path(__file__).resolve().parent
REPO = SCRIPTS.parent
BASE_SHA = "8d97e32e0b1af0a727d6e785c4c467f37543aa4d"
MATERIALIZER = SCRIPTS / "materialize-olcrtc-managed.py"


def run(*args: str, cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, cwd=cwd, capture_output=True, text=True, check=False)


class ManagedPatchCarrierTests(unittest.TestCase):
    def setUp(self) -> None:
        self.source = Path(os.environ.get("APOLLO_RGA_OLCRTC_SOURCE", REPO / ".deps" / "olcrtc"))
        if not self.source.is_dir():
            self.skipTest("pinned local OlcRTC checkout is not available")
        self.scratch = tempfile.TemporaryDirectory(prefix="apollo-rga-olcrtc-carrier-")
        self.addCleanup(self.scratch.cleanup)
        self.root = Path(self.scratch.name)

    def materialize(self, source: Path, output: Path, script: Path = MATERIALIZER) -> subprocess.CompletedProcess[str]:
        return run(sys.executable, str(script), "--source", str(source), "--output", str(output))

    def local_clone(self, name: str) -> Path:
        target = self.root / name
        cloned = run("git", "clone", "--quiet", "--local", "--no-hardlinks", "--no-checkout", str(self.source), str(target))
        self.assertEqual(cloned.returncode, 0, cloned.stderr)
        checked = run("git", "-C", str(target), "checkout", "--quiet", "--detach", BASE_SHA)
        self.assertEqual(checked.returncode, 0, checked.stderr)
        return target

    def test_materializes_once_and_rejects_patched_source(self) -> None:
        output = self.root / "patched"
        made = self.materialize(self.source, output)
        self.assertEqual(made.returncode, 0, made.stderr)
        self.assertEqual(run("git", "-C", str(output), "rev-parse", "HEAD").stdout.strip(), BASE_SHA)
        changed = run("git", "-C", str(output), "diff", "--name-only")
        self.assertIn("internal/handshake/handshake.go", changed.stdout)
        self.assertEqual(run("git", "-C", str(output), "diff", "--check").returncode, 0)
        again = self.materialize(output, self.root / "second")
        self.assertNotEqual(again.returncode, 0)
        self.assertIn("source checkout is dirty", again.stderr)
        self.assertFalse((self.root / "second").exists())

    def test_rejects_wrong_source_commit_before_output(self) -> None:
        source = self.root / "other-repo"
        self.assertEqual(run("git", "init", "--quiet", str(source)).returncode, 0)
        self.assertEqual(run("git", "-C", str(source), "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "--quiet", "--allow-empty", "-m", "other").returncode, 0)
        output = self.root / "wrong-head-output"
        result = self.materialize(source, output)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("source commit does not match", result.stderr)
        self.assertFalse(output.exists())

    def test_rejects_dirty_source_before_output(self) -> None:
        source = self.local_clone("dirty-source")
        with (source / "go.mod").open("a", encoding="utf-8") as file:
            file.write("\n// test-only dirty source\n")
        output = self.root / "dirty-output"
        result = self.materialize(source, output)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("source checkout is dirty", result.stderr)
        self.assertFalse(output.exists())

    def test_rejects_patch_digest_mismatch_before_output(self) -> None:
        scripts = self.root / "scripts"
        (scripts / "patches").mkdir(parents=True)
        copied_script = scripts / MATERIALIZER.name
        shutil.copy2(MATERIALIZER, copied_script)
        shutil.copy2(SCRIPTS / "cores-pins.sh", scripts / "cores-pins.sh")
        manifest = json.loads((SCRIPTS / "olcrtc-managed-patch.json").read_text(encoding="utf-8"))
        (scripts / "olcrtc-managed-patch.json").write_text(json.dumps(manifest), encoding="utf-8")
        patch = SCRIPTS / manifest["patch_path"]
        copied_patch = scripts / manifest["patch_path"]
        shutil.copy2(patch, copied_patch)
        with copied_patch.open("ab") as file:
            file.write(b"\n")
        output = self.root / "bad-digest-output"
        result = self.materialize(self.source, output, copied_script)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("patch digest does not match", result.stderr)
        self.assertFalse(output.exists())

    def test_rejects_authoritative_engine_pin_drift_before_output(self) -> None:
        scripts = self.root / "scripts-pin-drift"
        (scripts / "patches").mkdir(parents=True)
        copied_script = scripts / MATERIALIZER.name
        shutil.copy2(MATERIALIZER, copied_script)
        shutil.copy2(SCRIPTS / "olcrtc-managed-patch.json", scripts / "olcrtc-managed-patch.json")
        manifest = json.loads((scripts / "olcrtc-managed-patch.json").read_text(encoding="utf-8"))
        shutil.copy2(SCRIPTS / manifest["patch_path"], scripts / manifest["patch_path"])
        pins = (SCRIPTS / "cores-pins.sh").read_text(encoding="utf-8")
        self.assertIn(BASE_SHA[:12], pins)
        (scripts / "cores-pins.sh").write_text(
            pins.replace(BASE_SHA[:12], "111111111111"), encoding="utf-8"
        )
        output = self.root / "pin-drift-output"
        result = self.materialize(self.source, output, copied_script)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("engine pin does not match patch base", result.stderr)
        self.assertFalse(output.exists())

    def test_digest_pinned_patch_survives_fresh_autocrlf_checkout(self) -> None:
        repository = self.root / "autocrlf-source"
        target = repository / "scripts" / "patches"
        target.mkdir(parents=True)
        manifest = json.loads((SCRIPTS / "olcrtc-managed-patch.json").read_text(encoding="utf-8"))
        patch = SCRIPTS / manifest["patch_path"]
        shutil.copy2(patch, target / patch.name)
        attributes = REPO / ".gitattributes"
        if attributes.exists():
            shutil.copy2(attributes, repository / ".gitattributes")
        self.assertEqual(run("git", "init", "--quiet", str(repository)).returncode, 0)
        self.assertEqual(run("git", "-C", str(repository), "add", ".").returncode, 0)
        committed = run(
            "git", "-C", str(repository), "-c", "user.name=Test",
            "-c", "user.email=test@example.invalid", "commit", "--quiet", "-m", "eol test"
        )
        self.assertEqual(committed.returncode, 0, committed.stderr)
        checked_out = self.root / "autocrlf-checkout"
        cloned = run(
            "git", "-c", "core.autocrlf=true", "clone", "--quiet", "--local",
            "--no-hardlinks", str(repository), str(checked_out)
        )
        self.assertEqual(cloned.returncode, 0, cloned.stderr)
        actual = hashlib.sha256((checked_out / "scripts" / "patches" / patch.name).read_bytes()).hexdigest()
        self.assertEqual(actual, manifest["patch_sha256"])

    def test_existing_output_remains_untouched(self) -> None:
        output = self.root / "existing-output"
        output.mkdir()
        marker = output / "keep.txt"
        marker.write_text("untouched", encoding="utf-8")
        result = self.materialize(self.source, output)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("output already exists", result.stderr)
        self.assertEqual(marker.read_text(encoding="utf-8"), "untouched")

    def test_symlink_output_remains_untouched_when_supported(self) -> None:
        target = self.root / "target"
        target.mkdir()
        marker = target / "keep.txt"
        marker.write_text("untouched", encoding="utf-8")
        output = self.root / "output-link"
        try:
            os.symlink(target, output, target_is_directory=True)
        except (OSError, NotImplementedError):
            self.skipTest("creating a symlink is not permitted on this host")
        result = self.materialize(self.source, output)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("output already exists", result.stderr)
        self.assertEqual(marker.read_text(encoding="utf-8"), "untouched")

    @unittest.skipUnless(os.name == "nt", "junctions are Windows-only")
    def test_junction_output_remains_untouched(self) -> None:
        target = self.root / "junction-target"
        target.mkdir()
        marker = target / "keep.txt"
        marker.write_text("untouched", encoding="utf-8")
        output = self.root / "output-junction"
        linked = run("cmd", "/c", "mklink", "/J", str(output), str(target))
        self.assertEqual(linked.returncode, 0, linked.stderr)
        self.assertTrue(os.path.isjunction(output))
        result = self.materialize(self.source, output)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("output already exists", result.stderr)
        self.assertEqual(marker.read_text(encoding="utf-8"), "untouched")

    def test_rejects_output_nested_inside_source(self) -> None:
        source = self.local_clone("overlap-source")
        output = source / "nested-patched"
        result = self.materialize(source, output)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("output overlaps source", result.stderr)
        self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
