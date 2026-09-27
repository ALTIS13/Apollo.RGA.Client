"""Release-gate regressions; synthetic APKs and public metadata only."""

import importlib.util
from pathlib import Path
import tempfile
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


class ApolloRgaReleaseGateTest(unittest.TestCase):
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
