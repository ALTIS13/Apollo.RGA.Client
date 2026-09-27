"""Release-gate regressions; synthetic APKs and public metadata only."""

import importlib.util
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import textwrap
import unittest
from zipfile import ZipFile


spec = importlib.util.spec_from_file_location(
    "rga_release", Path(__file__).with_name("verify-apollo-rga-release.py")
)
rga_release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rga_release)

CERT = "7D1028460690F766AC56A0E4F21134D5C2A45CC6E17509B53FC5037D774058B6"
BADGING = "package: name='tech.gatealtas.rga' versionCode='7' versionName='1.0.7'\n"
SIGNATURE = (
    "Verifies\n"
    "Verified using v1 scheme (JAR signing): true\n"
    "Verified using v2 scheme (APK Signature Scheme v2): true\n"
    f"Signer #1 certificate SHA-256 digest: {CERT.lower()}\n"
)
ASSETLINKS = [{
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {
        "namespace": "android_app",
        "package_name": "tech.gatealtas.rga",
        "sha256_cert_fingerprints": [":".join(CERT[i:i+2] for i in range(0, 64, 2))],
    },
}]
MANIFEST = """\
      E: intent-filter (line=107)
        A: android:autoVerify(0x010104ee)=(type 0x12)0xffffffff
        E: action (line=108)
          A: android:name(0x01010003)=\"android.intent.action.VIEW\" (Raw: \"android.intent.action.VIEW\")
        E: category (line=110)
          A: android:name(0x01010003)=\"android.intent.category.DEFAULT\" (Raw: \"android.intent.category.DEFAULT\")
        E: category (line=111)
          A: android:name(0x01010003)=\"android.intent.category.BROWSABLE\" (Raw: \"android.intent.category.BROWSABLE\")
        E: data (line=113)
          A: android:scheme(0x01010027)=\"https\" (Raw: \"https\")
          A: android:host(0x01010028)=\"rga.apollot.ru\" (Raw: \"rga.apollot.ru\")
          A: android:path(0x0101002a)=\"/import\" (Raw: \"/import\")
      E: intent-filter (line=118)
        E: action (line=119)
          A: android:name(0x01010003)=\"android.intent.action.MAIN\" (Raw: \"android.intent.action.MAIN\")
"""

WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/apollo-rga-android.yml"
PINS = Path(__file__).with_name("apollo-rga-release-pins.json")


def workflow_jobs():
    text = WORKFLOW.read_text(encoding="utf-8")
    jobs = text.split("\njobs:\n", 1)[1]
    matches = list(re.finditer(r"(?m)^  ([a-z][a-z0-9_-]*):\s*$", jobs))
    return text, {
        match.group(1): jobs[match.end():matches[index + 1].start() if index + 1 < len(matches) else None]
        for index, match in enumerate(matches)
    }


def workflow_bash_step(job, name):
    step = job.split(f"      - name: {name}\n", 1)[1]
    step = re.split(r"(?m)^      - (?:name:|uses:)", step, maxsplit=1)[0]
    return textwrap.dedent(step.split("        run: |\n", 1)[1])


class ApolloRgaReleaseGateTest(unittest.TestCase):
    def test_permanent_signing_key_isolated_from_build_job(self):
        _, jobs = workflow_jobs()
        self.assertEqual(set(jobs), {"build", "sign"})
        build, sign = jobs["build"], jobs["sign"]
        self.assertNotIn("environment:", build)
        self.assertNotIn("secrets.", build)
        self.assertNotIn("ANDROID_RELEASE_", build)
        self.assertIn("ref: ${{ github.sha }}", build)
        sign_header = sign.split("\n    steps:\n", 1)[0]
        self.assertIn("environment: apollo-rga-candidate", sign_header)
        self.assertRegex(sign_header, r"(?m)^    needs: build$")
        self.assertEqual(sign.count("${{ secrets.ANDROID_RELEASE_"), 4)

    def test_sign_job_requires_main_and_owned_repository(self):
        _, jobs = workflow_jobs()
        self.assertIn("sign", jobs)
        repository = json.loads(PINS.read_text(encoding="utf-8"))["ci_repository"]
        sign_header = jobs["sign"].split("\n    steps:\n", 1)[0]
        self.assertIn("github.ref == 'refs/heads/main'", sign_header)
        self.assertIn(f"github.repository == '{repository}'", sign_header)

    def test_sign_job_checks_exact_unsigned_handoff_before_key_use(self):
        _, jobs = workflow_jobs()
        self.assertIn("build", jobs)
        self.assertIn("sign", jobs)
        build, sign = jobs["build"], jobs["sign"]
        self.assertIn("actions/upload-artifact@", build)
        self.assertIn("artifact-id", build)
        self.assertIn("actions/download-artifact@", sign)
        self.assertIn("artifact-ids: ${{ needs.build.outputs.artifact_id }}", sign)
        for name in ("unsigned_sha256", "singbox_sha256", "xray_sha256", "xray_receipt_sha256"):
            self.assertIn(name, build)
            self.assertIn(f"needs.build.outputs.{name}", sign)
        pre_sign = sign.split("      - name: Sign finished APK", 1)[0]
        self.assertIn("sha256sum -c -", pre_sign)
        self.assertIn("find handoff", pre_sign)
        self.assertNotIn("actions/checkout@", pre_sign)
        self.assertNotIn("python3 scripts/", pre_sign)
        self.assertNotIn("./gradlew", pre_sign)
        self.assertLess(sign.index("Verify unsigned artifact handoff"), sign.index("Sign finished APK"))
        self.assertLess(sign.index("Sign finished APK"), sign.index("verify-apollo-rga-release.py"))
        self.assertIn("ref: ${{ github.sha }}", sign)

    def test_unsigned_handoff_rejects_tampering_extra_files_and_wrong_ref(self):
        _, jobs = workflow_jobs()
        script = workflow_bash_step(jobs["sign"], "Verify unsigned artifact handoff before key access")
        bash = Path("C:/Program Files/Git/bin/bash.exe") if os.name == "nt" else shutil.which("bash")
        self.assertTrue(bash, "Bash is required to execute the workflow handoff guard")
        files = {
            "handoff/unsigned.apk": b"synthetic unsigned APK",
            "handoff/cores/arm64-v8a/libsingboxcore.so": b"synthetic sing-box",
            "handoff/cores/arm64-v8a/libxraycore.so": b"synthetic Xray",
            "handoff/xray-receipt.json": b'{"synthetic": true}',
        }
        variables = (
            "EXPECTED_UNSIGNED_SHA256", "EXPECTED_SINGBOX_SHA256",
            "EXPECTED_XRAY_SHA256", "EXPECTED_XRAY_RECEIPT_SHA256",
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env = os.environ.copy()
            env.update({
                "GITHUB_REPOSITORY": json.loads(PINS.read_text(encoding="utf-8"))["ci_repository"],
                "GITHUB_REF": "refs/heads/main",
                "VERSION_NAME": "1.0.1",
                "VERSION_CODE": "1",
            })
            for (name, content), variable in zip(files.items(), variables):
                target = root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(content)
                env[variable] = hashlib.sha256(content).hexdigest()

            def check(extra_env=None):
                return subprocess.run(
                    [str(bash), "-c", script], cwd=root, env={**env, **(extra_env or {})},
                    capture_output=True, text=True, check=False,
                ).returncode

            self.assertEqual(check(), 0)
            self.assertNotEqual(check({"GITHUB_REF": "refs/heads/feature"}), 0)
            (root / "handoff/unsigned.apk").write_bytes(b"tampered APK")
            self.assertNotEqual(check(), 0)
            (root / "handoff/unsigned.apk").write_bytes(files["handoff/unsigned.apk"])
            (root / "handoff/extra.apk").write_bytes(b"extra")
            self.assertNotEqual(check(), 0)

    def test_all_workflow_actions_are_full_commit_pins(self):
        text, _ = workflow_jobs()
        actions = re.findall(r"(?m)^\s*(?:- )?uses:\s*(\S+)\s*$", text)
        self.assertGreaterEqual(len(actions), 7)
        for action in actions:
            with self.subTest(action=action):
                self.assertRegex(action, r"^[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+@[0-9a-f]{40}$")

    def test_release_evidence_cannot_claim_another_or_dirty_commit(self):
        sha = "a" * 40
        rga_release.verify_checkout_provenance(sha, sha, "")
        for requested, actual, dirty in (
            (sha, "b" * 40, ""),
            (sha, sha, " M androidApp/build.gradle.kts"),
            ("not-a-sha", sha, ""),
        ):
            with self.subTest(requested=requested, dirty=dirty), self.assertRaises(ValueError):
                rga_release.verify_checkout_provenance(requested, actual, dirty)

    def test_packaged_manifest_has_only_exact_verified_import_filter(self):
        rga_release.verify_manifest_app_link(MANIFEST, "rga.apollot.ru")
        for altered in (
            MANIFEST.replace('=\"/import\"', '=\"/\"'),
            MANIFEST.replace('android:path(', 'android:pathPrefix('),
            MANIFEST.replace('=\"https\"', '=\"http\"'),
            MANIFEST.replace('0xffffffff', '0x0'),
            MANIFEST.replace('rga.apollot.ru', 'other.apollot.ru'),
            MANIFEST.replace('android.intent.category.BROWSABLE', 'android.intent.category.LAUNCHER'),
            MANIFEST.replace('      E: intent-filter (line=118)',
                             '          A: android:host(0x01010028)=\"other.apollot.ru\"\n      E: intent-filter (line=118)'),
        ):
            with self.subTest(altered=altered[-110:]), self.assertRaises(ValueError):
                rga_release.verify_manifest_app_link(altered, "rga.apollot.ru")

    def test_accepts_exact_package_version_single_signer_and_published_app_link(self):
        metadata = rga_release.verify_metadata(
            BADGING, SIGNATURE, ASSETLINKS,
            package="tech.gatealtas.rga", version_code=7, version_name="1.0.7", cert=CERT,
        )
        self.assertEqual(metadata["package"], "tech.gatealtas.rga")
        self.assertEqual(metadata["signing_cert_sha256"], CERT)

    def test_rejects_wrong_identity_version_or_signer(self):
        cases = (
            (BADGING.replace("tech.gatealtas.rga", "org.olcbox.app"), SIGNATURE, ASSETLINKS),
            (BADGING.replace("versionCode='7'", "versionCode='6'"), SIGNATURE, ASSETLINKS),
            (BADGING.replace("versionName='1.0.7'", "versionName='1.0.6'"), SIGNATURE, ASSETLINKS),
            (BADGING, SIGNATURE.replace(CERT.lower(), "0" * 64), ASSETLINKS),
            (BADGING, SIGNATURE.replace("v2): true", "v2): false"), ASSETLINKS),
            (BADGING, SIGNATURE + f"Signer #2 certificate SHA-256 digest: {CERT.lower()}\n", ASSETLINKS),
            (BADGING, SIGNATURE, []),
        )
        for badging, signature, assetlinks in cases:
            with self.subTest(badging=badging[:75], signature=signature[-78:]), \
                    self.assertRaises(ValueError):
                rga_release.verify_metadata(
                    badging, signature, assetlinks,
                    package="tech.gatealtas.rga", version_code=7, version_name="1.0.7", cert=CERT,
                )

    def test_rejects_hidden_olcrtc_library_in_ordinary_apk(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "test.apk"
            with ZipFile(apk, "w") as archive:
                archive.writestr("lib/arm64-v8a/libsingboxcore.so", b"singbox")
                archive.writestr("lib/arm64-v8a/libxraycore.so", b"xray")
                archive.writestr("lib/arm64-v8a/libgojni.so", b"olcrtc")
            with self.assertRaisesRegex(ValueError, "OlcRTC"):
                rga_release.verify_apk_contents(apk)

    def test_rejects_missing_or_duplicate_core_members(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "test.apk"
            with ZipFile(apk, "w") as archive:
                archive.writestr("lib/arm64-v8a/libsingboxcore.so", b"singbox")
            with self.assertRaisesRegex(ValueError, "core"):
                rga_release.verify_apk_contents(apk)


if __name__ == "__main__":
    unittest.main()
