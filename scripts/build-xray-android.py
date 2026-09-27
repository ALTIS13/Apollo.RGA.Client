#!/usr/bin/env python3
"""Build only Android Xray with its isolated exact Go toolchain; no latest fallback."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
PINS = json.loads((ROOT / "scripts/android-xray-pins.json").read_text())
TARGETS = {
    "arm64-v8a": ("arm64", "aarch64-linux-android23"),
    "armeabi-v7a": ("arm", "armv7a-linux-androideabi23"),
    "x86_64": ("amd64", "x86_64-linux-android23"),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abis", nargs="+", choices=TARGETS, default=list(TARGETS))
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--go", default="go")
    parser.add_argument("--out", type=Path, default=ROOT / "androidApp/jniLibs")
    args = parser.parse_args()
    source, out = args.source.resolve(), args.out.resolve()
    try:
        properties = (args.ndk / "source.properties").read_text(encoding="utf-8")
        revisions = [value.strip() for line in properties.splitlines()
                     for key, separator, value in [line.partition("=")]
                     if separator and key.strip() == "Pkg.Revision"]
    except (OSError, UnicodeError):
        raise SystemExit("Android NDK source.properties is missing or unreadable")
    if revisions != [PINS["ndk"]]:
        raise SystemExit(f"Android NDK must have exact Pkg.Revision {PINS['ndk']}")
    if not source.exists():
        subprocess.run(["git", "clone", "--depth", "1", "--branch", "v" + PINS["version"],
                        "https://github.com/XTLS/Xray-core.git", str(source)], check=True)
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=source, text=True).strip()
    dirty = subprocess.check_output(["git", "status", "--porcelain"], cwd=source, text=True).strip()
    if commit != PINS["commit"] or dirty:
        raise SystemExit("Xray source must be clean and match the exact Android commit")
    # Do not inherit `go env -w`, architecture tuning, experiments, custom flags
    # or compiler search paths. Keep ordinary OS/network/proxy settings intact.
    compiler_environment = {
        "CC", "CXX", "AR", "AS", "FC", "LD", "RANLIB", "PKG_CONFIG",
        "CFLAGS", "CPPFLAGS", "CXXFLAGS", "LDFLAGS", "CPATH", "C_INCLUDE_PATH",
        "CPLUS_INCLUDE_PATH", "OBJC_INCLUDE_PATH", "LIBRARY_PATH", "COMPILER_PATH",
        "GCC_EXEC_PREFIX", "CCC_OVERRIDE_OPTIONS", "SDKROOT", "MACOSX_DEPLOYMENT_TARGET",
        "CLANG_CONFIG_FILE_SYSTEM_DIR", "CLANG_CONFIG_FILE_USER_DIR", "SOURCE_DATE_EPOCH",
        "INCLUDE", "LIB", "LIBPATH", "CL", "_CL_", "LINK", "_LINK_",
    }
    env = {key: value for key, value in os.environ.items()
           if not key.upper().startswith(("GO", "CGO_")) and key.upper() not in compiler_environment}
    env.update(GOENV="off", GOTOOLCHAIN="go" + PINS["go"], GOWORK="off",
               GOFLAGS="", GOEXPERIMENT="", GOAMD64="v1", GOARM64="v8.0")
    version = subprocess.check_output([args.go, "env", "GOVERSION"], cwd=source, env=env, text=True).strip()
    if version != "go" + PINS["go"]:
        raise SystemExit("Unexpected Android Xray Go toolchain")
    host = "windows-x86_64" if sys.platform == "win32" else "darwin-x86_64" if sys.platform == "darwin" else "linux-x86_64"
    ndk_bin = args.ndk.resolve() / "toolchains/llvm/prebuilt" / host / "bin"
    spec = importlib.util.spec_from_file_location("verify", ROOT / "scripts/verify-android-cores.py")
    verify = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(verify)
    source_hash = hashlib.sha256(subprocess.check_output(["git", "archive", "HEAD"], cwd=source)).hexdigest()
    for abi in args.abis:
        arch, triple = TARGETS[abi]
        target = out / abi / "libxraycore.so"
        target.parent.mkdir(parents=True, exist_ok=True)
        suffix = ".cmd" if sys.platform == "win32" else ""
        env.update(CGO_ENABLED="1", GOOS="android", GOARCH=arch, GOARM="7",
                   CC=str(ndk_bin / (triple + "-clang" + suffix)),
                   CXX=str(ndk_bin / (triple + "-clang++" + suffix)))
        subprocess.run([args.go, "build", "-mod=readonly", "-trimpath", "-buildvcs=false", "-o", str(target),
                        # Upstream Android release requires this for anet's net.zoneCache linkname.
                        "-ldflags", f"-X github.com/xtls/xray-core/core.build=v{PINS['version']} -s -w -buildid= -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384",
                        "./main"], cwd=source, env=env, check=True)
        data = target.read_bytes()
        verify.verify_elf(data, abi)
        receipt = dict(PINS, abi=abi, source_archive_sha256=source_hash,
                       binary_sha256=hashlib.sha256(data).hexdigest())
        receipts = ROOT / "build/xray-receipts"
        receipts.mkdir(parents=True, exist_ok=True)
        (receipts / (abi + ".json")).write_text(json.dumps(receipt, indent=2) + "\n")
        print(json.dumps(receipt))


if __name__ == "__main__":
    main()
