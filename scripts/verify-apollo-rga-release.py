#!/usr/bin/env python3
"""Fail closed unless a signed Apollo.RGA APK matches the published App Link."""

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
from urllib.request import urlopen
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parent.parent
PINS = ROOT / "scripts/apollo-rga-release-pins.json"
XRAY_PINS = ROOT / "scripts/android-xray-pins.json"
CORE_NAMES = ("libsingboxcore.so", "libxraycore.so")
ABI = "arm64-v8a"


def verify_checkout_provenance(requested, actual, tracked_changes):
    if not re.fullmatch(r"[0-9a-f]{40}", requested) or requested != actual or tracked_changes.strip():
        raise ValueError("release evidence requires the exact clean source commit")


def verify_manifest_app_link(xmltree, host):
    lines = xmltree.splitlines()
    view_filters = []
    for index, line in enumerate(lines):
        match = re.match(r"^(\s*)E: intent-filter\b", line)
        if not match:
            continue
        indent = len(match.group(1))
        block = [line]
        for child in lines[index + 1:]:
            if child.strip() and len(child) - len(child.lstrip()) <= indent:
                break
            block.append(child)
        text = "\n".join(block)

        def values(attribute):
            return re.findall(
                rf'^\s*A: android:{re.escape(attribute)}\([^)]*\)="([^"]+)"',
                text, re.MULTILINE,
            )

        actions = values("name")
        if "android.intent.action.VIEW" not in actions:
            continue
        view_filters.append((text, actions, values))
    if len(view_filters) != 1:
        raise ValueError("APK must contain one exact Apollo.RGA VIEW intent filter")
    text, actions, values = view_filters[0]
    if actions.count("android.intent.action.VIEW") != 1 or any(
        action.startswith("android.intent.action.") and action != "android.intent.action.VIEW"
        for action in actions
    ):
        raise ValueError("APK App Link intent actions are not exact")
    if sorted(value for value in actions if value.startswith("android.intent.category.")) != [
        "android.intent.category.BROWSABLE", "android.intent.category.DEFAULT"
    ]:
        raise ValueError("APK App Link must be browsable and default")
    if not re.search(r"^\s*A: android:autoVerify\([^)]*\)=\(type 0x12\)0xffffffff\s*$", text, re.MULTILINE):
        raise ValueError("APK App Link filter does not request Android verification")
    if values("scheme") != ["https"] or values("host") != [host] or values("path") != ["/import"]:
        raise ValueError("APK App Link host, scheme, or path differs from the exact import route")
    if any(re.search(rf"^\s*A: android:{name}\(", text, re.MULTILINE)
           for name in ("pathPrefix", "pathPattern", "pathAdvancedPattern", "port", "mimeType")):
        raise ValueError("APK App Link filter is broader than the exact import route")


def normalize_cert(value):
    cert = value.replace(":", "").upper()
    if not re.fullmatch(r"[0-9A-F]{64}", cert):
        raise ValueError("invalid signing certificate SHA-256")
    return cert


def verify_metadata(badging, signature, assetlinks, *, package, version_code, version_name, cert):
    packages = re.findall(
        r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'",
        badging, re.MULTILINE,
    )
    if packages != [(package, str(version_code), version_name)]:
        raise ValueError("APK package or version differs from the requested Apollo.RGA build")
    if not re.search(r"^Verified using v2 scheme \(APK Signature Scheme v2\): true\s*$", signature, re.MULTILINE):
        raise ValueError("APK Signature Scheme v2 is not verified")
    signers = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F:]+)\s*$", signature, re.MULTILINE)
    expected = normalize_cert(cert)
    if len(signers) != 1 or normalize_cert(signers[0]) != expected:
        raise ValueError("APK signer does not match the pinned App Link certificate")
    if not isinstance(assetlinks, list) or not any(
        isinstance(statement, dict)
        and "delegate_permission/common.handle_all_urls" in statement.get("relation", [])
        and isinstance(statement.get("target"), dict)
        and statement["target"].get("namespace") == "android_app"
        and statement["target"].get("package_name") == package
        and expected in [normalize_cert(item) for item in statement["target"].get("sha256_cert_fingerprints", [])]
        for statement in assetlinks
    ):
        raise ValueError("published assetlinks.json does not allow this exact package and signer")
    return {"package": package, "version_code": version_code, "version_name": version_name,
            "signing_cert_sha256": expected}


def verify_apk_contents(apk):
    with ZipFile(apk) as archive:
        names = archive.namelist()
        if any(Path(name).name == "libgojni.so" for name in names):
            raise ValueError("ordinary Apollo.RGA APK unexpectedly contains OlcRTC")
        cores = {}
        for name in CORE_NAMES:
            member = f"lib/{ABI}/{name}"
            if names.count(member) != 1:
                raise ValueError(f"missing or duplicate Android core: {member}")
            cores[name] = hashlib.sha256(archive.read(member)).hexdigest()
        if any(
            name.startswith("lib/") and Path(name).name in CORE_NAMES
            and name not in {f"lib/{ABI}/{core}" for core in CORE_NAMES}
            for name in names
        ):
            raise ValueError("unexpected Android core ABI in arm64 candidate")
    return cores


def verify_release(args):
    verify_checkout_provenance(
        args.source_sha,
        subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=no"], text=True),
    )
    pins = json.loads(PINS.read_text(encoding="utf-8"))
    if args.assetlinks:
        assetlinks = json.loads(args.assetlinks.read_text(encoding="utf-8"))
    else:
        url = f'https://{pins["app_link_host"]}/.well-known/assetlinks.json'
        with urlopen(url, timeout=15) as response:
            if response.status != 200 or response.geturl() != url:
                raise ValueError("published App Link association redirected or unavailable")
            if not response.headers.get_content_type() == "application/json":
                raise ValueError("published App Link association is not JSON")
            data = response.read(1_000_001)
            if len(data) > 1_000_000:
                raise ValueError("published App Link association exceeds 1 MB")
            assetlinks = json.loads(data)
    aapt = subprocess.check_output([str(args.aapt), "dump", "badging", str(args.apk)], text=True)
    manifest = subprocess.check_output(
        [str(args.aapt), "dump", "xmltree", str(args.apk), "AndroidManifest.xml"], text=True
    )
    apksigner = subprocess.check_output(
        [str(args.apksigner), "verify", "--verbose", "--print-certs", str(args.apk)], text=True
    )
    verify_manifest_app_link(manifest, pins["app_link_host"])
    metadata = verify_metadata(
        aapt, apksigner, assetlinks, package=pins["package"],
        version_code=args.version_code, version_name=args.version_name,
        cert=pins["signing_cert_sha256"],
    )
    cores = verify_apk_contents(args.apk)
    spec = importlib.util.spec_from_file_location("verify_cores", ROOT / "scripts/verify-android-cores.py")
    verify_cores = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(verify_cores)
    verify_cores.verify_stage(args.stage, [ABI], args.apk)
    receipt = json.loads(args.xray_receipt.read_text(encoding="utf-8"))
    xray_pins = json.loads(XRAY_PINS.read_text(encoding="utf-8"))
    if receipt.get("abi") != ABI or any(receipt.get(key) != value for key, value in xray_pins.items()):
        raise ValueError("Xray build receipt does not match its pinned source and toolchain")
    if receipt.get("binary_sha256") != cores["libxraycore.so"]:
        raise ValueError("Xray build receipt does not match the APK")
    evidence = {
        **metadata,
        "source_commit": args.source_sha,
        "apk_sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
        "android_abi": ABI,
        "core_sha256": cores,
        "singbox_version": pins["singbox_version"],
        "singbox_commit": pins["singbox_commit"],
        "xray_receipt": receipt,
        "app_link_host": pins["app_link_host"],
        "app_link_path": "/import",
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--stage", type=Path, default=ROOT / "androidApp/jniLibs")
    parser.add_argument("--aapt", type=Path, required=True)
    parser.add_argument("--apksigner", type=Path, required=True)
    parser.add_argument("--assetlinks", type=Path, help="offline fixture; CI fetches the pinned live host")
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--xray-receipt", type=Path, default=ROOT / "build/xray-receipts/arm64-v8a.json")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        evidence = verify_release(args)
    except (ValueError, OSError, subprocess.CalledProcessError, json.JSONDecodeError) as error:
        parser.exit(1, f"Apollo.RGA release verification failed: {error}\n")
    print("Verified Apollo.RGA candidate: " + evidence["apk_sha256"])


if __name__ == "__main__":
    main()
