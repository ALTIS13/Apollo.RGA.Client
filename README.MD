# Apollo.RGA Android client

This repository contains the Android source for Apollo.RGA, adapted from
[Ghostlane](https://github.com/ghostlane-project/ghostlane), which in turn was
forked from [olcbox](https://github.com/alananisimov/olcbox). It is a client for
an existing provider subscription, not a service that issues accounts or server
access. The Android application ID is `tech.gatealtas.rga`.

## Status and subscription import

This is an arm64 APK candidate, not a user release. No Apollo.RGA APK has been
released to users. The separate
[Apollo.RGA Android workflow](.github/workflows/apollo-rga-android.yml) uploads
a candidate and verification evidence for review; it does not publish an APK,
Google Play bundle or release page.

Users can add the HTTPS subscription already issued by their provider. The
Android App Link entry point is `https://rga.apollot.ru/import`; a subscription
address may be carried in its URL fragment, and the app asks for confirmation
before import. A subscription address is a credential: do not put a real one in
an issue, build log, source file or release note. Apollo.RGA does not require a
new account or a second subscription.

The ordinary Android release APK contains sing-box and Xray executable cores
for VLESS/Reality/XHTTP and Hysteria 2. olcRTC is not packaged or enabled in
that APK. Other Ghostlane platforms and release channels are not described by
this README.

## Build an Android candidate

The manual [candidate workflow](.github/workflows/apollo-rga-android.yml) is the
complete arm64 build and signing path. Dispatch it in the controlled Apollo.RGA
repository with `version_name` and a monotonically increasing `version_code`.
It checks the repository pin, builds the exact sing-box and Xray revisions in
the [release pins](scripts/apollo-rga-release-pins.json) and
[Xray pins](scripts/android-xray-pins.json), assembles an unsigned `githubRelease`
APK, signs it in a separate protected job on a fresh runner with the permanent
key, and verifies the
finished archive. Its artifact contains `Apollo-RGA-<version>-android-arm64.apk`
and `apollo-rga-release-evidence.json`; neither is published to users by this
workflow. Signing material must remain outside the source checkout.

For a local Gradle assembly, use JDK 21, Android SDK 37 and NDK
`28.2.13676358`. First stage both verified arm64 native executables at
`androidApp/jniLibs/arm64-v8a/libsingboxcore.so` and
`androidApp/jniLibs/arm64-v8a/libxraycore.so` using the workflow's pinned build
steps. Gradle does not build those two cores. Then run:

```bash
./gradlew -Papollo.rga.externalSigning=true -Polcbox.version=1.0.0 \
  -Polcbox.versionCode=1 -Polcbox.android.abiFilters=arm64-v8a \
  :androidApp:assembleGithubRelease
```

On Windows, invoke `.\gradlew.bat` instead. Replace the example version values
with the candidate's values. This produces
`androidApp/build/outputs/apk/github/release/androidApp-github-release-unsigned.apk`;
it is **not** a distributable signed APK. The workflow, not this Gradle command,
performs final signing and checks the package, certificate, App Link, native
cores and absence of olcRTC.

## Source and attribution for distribution

The repository [MIT licence](LICENSE) retains the original olcbox and Ghostlane
copyright notices. It does not replace the licences of bundled components.
[Third-party notices](THIRD_PARTY_NOTICES.md) identify the Android native
components, their pinned upstream source revisions and the vendored source.
The Apollo.RGA source snapshot starts from a new root commit, without the
upstream checkout's earlier Git history or historical test material. That
does not change its provenance from the upstream projects linked above or remove
their notices from `LICENSE`.

Before distributing an Apollo.RGA APK, finish the release checks and make the
exact application `source_commit` recorded in its evidence available from the
Apollo.RGA-owned repository, together with this notice, the repository licence
and component licence texts. Do not point recipients only to upstream Ghostlane
or to a mutable branch. The candidate workflow's artifact is not a source
publication step. The final source link must be filled in from the verified
release commit after review; no public Apollo.RGA APK release is claimed here.

The release process requires a protected signing job and an independent offline
copy of signing material, kept outside this repository. Remaining acceptance
gates include a clean CI build and signature, the isolated HWID
admission/refusal check and the outstanding physical-network checks. Source
tests and a locally signed APK do not by themselves establish a user-ready
release. Licence interactions and
distribution terms require separate review; these documents do not decide the
licence of a combined work.
