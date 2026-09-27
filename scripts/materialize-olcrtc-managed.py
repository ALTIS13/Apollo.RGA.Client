#!/usr/bin/env python3
"""Create an isolated, exact-base OlcRTC checkout with the reviewed RGA patch.

This is an offline preparation tool, not a release or deployment step.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile


class MaterializeError(Exception):
    pass


def git(cwd: Path, *args: str) -> subprocess.CompletedProcess[bytes]:
    try:
        return subprocess.run(
            ["git", "-C", str(cwd), *args], capture_output=True, check=False
        )
    except OSError as exc:
        raise MaterializeError("local Git is unavailable") from exc


def require_git(cwd: Path, *args: str) -> bytes:
    result = git(cwd, *args)
    if result.returncode != 0:
        raise MaterializeError("local Git operation failed")
    return result.stdout


def load_manifest(script_dir: Path) -> tuple[str, Path]:
    try:
        manifest = json.loads((script_dir / "olcrtc-managed-patch.json").read_text(encoding="utf-8"))
        base_sha = manifest["base_sha"]
        patch_sha = manifest["patch_sha256"]
        path_value = manifest["patch_path"]
        if not isinstance(base_sha, str) or re.fullmatch(r"[0-9a-f]{40}", base_sha) is None:
            raise ValueError
        if not isinstance(patch_sha, str) or re.fullmatch(r"[0-9a-f]{64}", patch_sha) is None:
            raise ValueError
        if not isinstance(path_value, str) or not path_value or Path(path_value).is_absolute():
            raise ValueError
        patch_path = (script_dir / path_value).resolve(strict=True)
        patch_path.relative_to(script_dir.resolve(strict=True))
        if not patch_path.is_file():
            raise ValueError
        actual_sha = hashlib.sha256(patch_path.read_bytes()).hexdigest()
        if actual_sha != patch_sha:
            raise MaterializeError("patch digest does not match")
    except MaterializeError:
        raise
    except (OSError, KeyError, TypeError, ValueError) as exc:
        raise MaterializeError("managed patch manifest is invalid") from exc
    return base_sha, patch_path


def check_source(source: Path, base_sha: str) -> None:
    head = require_git(source, "rev-parse", "--verify", "HEAD").decode("ascii", "replace").strip()
    if head != base_sha:
        raise MaterializeError("source commit does not match pinned base")
    if require_git(source, "status", "--porcelain", "--untracked-files=all"):
        raise MaterializeError("source checkout is dirty")


def safe_stage_cleanup(stage: Path | None, parent: Path) -> None:
    if stage is None or not os.path.lexists(stage):
        return
    if stage.is_symlink() or getattr(os.path, "isjunction", lambda _: False)(stage):
        raise MaterializeError("refusing unsafe staging cleanup")
    resolved = stage.resolve(strict=True)
    if resolved.parent != parent.resolve(strict=True):
        raise MaterializeError("refusing staging cleanup outside output parent")
    shutil.rmtree(stage)


def materialize(source_arg: str, output_arg: str, script_dir: Path) -> None:
    source = Path(source_arg).resolve(strict=True)
    output = Path(output_arg).absolute()
    if not source.is_dir():
        raise MaterializeError("source is not a local directory")
    if os.path.lexists(output):
        raise MaterializeError("output already exists")
    output_target = output.resolve(strict=False)
    if output_target == source or source in output_target.parents or output_target in source.parents:
        raise MaterializeError("output overlaps source")
    parent = output.parent.resolve(strict=True)
    if not parent.is_dir():
        raise MaterializeError("output parent is not a directory")

    base_sha, patch_path = load_manifest(script_dir)
    check_source(source, base_sha)

    stage: Path | None = None
    try:
        stage = Path(tempfile.mkdtemp(prefix=".apollo-rga-olcrtc-", dir=parent))
        engine = stage / "engine"
        clone = subprocess.run(
            ["git", "clone", "--quiet", "--local", "--no-hardlinks", "--no-checkout", "--", str(source), str(engine)],
            capture_output=True,
            check=False,
        )
        if clone.returncode != 0:
            raise MaterializeError("local clone failed")
        require_git(engine, "checkout", "--quiet", "--detach", base_sha)
        require_git(engine, "apply", "--check", str(patch_path))
        require_git(engine, "apply", str(patch_path))
        require_git(engine, "diff", "--check")
        if not require_git(engine, "diff", "--name-only"):
            raise MaterializeError("patch produced no source changes")
        if os.path.lexists(output):
            raise MaterializeError("output already exists")
        os.rename(engine, output)
    finally:
        safe_stage_cleanup(stage, parent)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, help="clean local checkout of pinned OlcRTC")
    parser.add_argument("--output", required=True, help="new directory for patched checkout")
    args = parser.parse_args()
    try:
        materialize(args.source, args.output, Path(__file__).resolve().parent)
    except (MaterializeError, OSError) as exc:
        message = str(exc) if isinstance(exc, MaterializeError) else "materialization failed"
        print(f"materializer: {message}", file=sys.stderr)
        return 1
    print("managed OlcRTC source materialized and verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
