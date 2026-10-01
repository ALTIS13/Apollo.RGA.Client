# Apollo.RGA Personal OTA Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Обновить личную A063-сборку с собственного сервера без утраты подписки.
**Architecture:** Personal-only signed manifest; exact selected-path download;
Android package/certificate/version verification before the system installer.
Legacy GitHub updater stays disabled; general subscribers stay disabled.
**Tech Stack:** Kotlin/JCA EC P-256, Android PackageManager/FileProvider/PackageInstaller,
existing SOCKS/session guards, RGA-only Home static artifacts.
**Spec:** `docs/superpowers/specs/2026-10-01-apollo-rga-personal-ota-design.md`.

## Global Constraints

- Personal package `tech.gatealtas.rga.personal`, companion `tech.gatealtas.rga.personal.test`.
- Origin `https://rga.apollot.ru`; manifest `/updates/personal/v1/manifest.json`.
- Manifest <=8192 bytes; payload <=4096 ASCII bytes, exact 17 LF-terminated lines.
- APK <=268435456 bytes; arm64-v8a; minSdk>=23; manual bootstrap versionCode=2,
  first true OTA versionCode=3. Installed v1 cannot run the new updater/pin.
- Manifest lifetime `0 < expiresAt - issuedAt <= 604800` seconds,
  future clock allowance 300 seconds, expiry>now.
- Public SPKI pin only; private keys/credentials/room/subscription stay ignored and off server serving paths.
- No Remnawave, DNS, RU/nodes, ordinary release, GA/GAP or excluded project writes.
- Root owns build/device/Home runtime; delegate non-overlapping source work only.

## Review Focus

- Correctly signed manifest with wrong package/certificate must not become an installer input.
- Cancellation after download/verification cannot reuse a replaced file or stale selected session.
- Offline/expired cache cannot disable or overwrite the currently working APK/subscription.
- Missing/incompatible companion must produce an actionable refusal, not false Working.
- Declining install permission/installer must preserve VPN data and avoid retry loops.

## Task 1 — Manifest contract and publisher

**Files:** Create `sharedUI/src/androidMain/kotlin/org/olcbox/app/update/personal/PersonalUpdateManifest.kt`,
`PersonalUpdateManifestVerifier.kt` in the same directory; test
`sharedUI/src/androidHostTest/kotlin/org/olcbox/app/update/personal/PersonalUpdateManifestVerifierTest.kt`;
create `scripts/publish-personal-update.ps1`. Keys/artifact staging only ignored/private.

**Interfaces:** `PersonalUpdateBaseline` contains installed package/version/cert,
SDK/ABIs and companion package/version/SHA/cert; `PersonalUpdateHighWater(sequence: Long,
payloadSha256: String?)`; `verify(envelope: ByteArray, pinnedSpki: ByteArray,
baseline: PersonalUpdateBaseline, highWater: PersonalUpdateHighWater,
nowEpochSeconds: Long): PersonalUpdateVerification`. Success carries parsed
manifest + payloadSha256, failure a fixed enum, never raw body/URL/error text.

- [ ] Write real-JCA tests: valid signed fixture; tamper; wrong key; malformed/duplicate envelope;
  unknown/reordered payload line; expired/future; downgrade; same-sequence conflict; oversized;
  wrong package/cert/ABI/path; companion mismatch. Generate test keys in memory.
- [ ] Run AndroidHost verifier tests and record RED, not a mocked signature result.
- [ ] Implement exact envelope/signature/schema/installed-baseline/high-water verification per spec.
- [ ] Generate separate permanent manifest key privately; verify encrypted off-device recovery
  before activation. Publisher derives fields from exact candidate, signs payload, validates
  its own output through the verifier, writes immutable artifacts then manifest last.
- [ ] Verify independent encrypted backup/restore of the EXISTING APK signer against
  full baseline certificate SHA-256. Do not assume a previous ordinary-release-key
  backup covers this personal signer; no signer rotation or reinstall. Without this
  evidence continue source verification only, not OTA activation/publication.
- [ ] Run verifier GREEN and publisher roundtrip; independently inspect public outputs for secrets.
  Commit only owned source/docs after focused diff review; never push to upstream `origin`.

## Task 2 — Personal selected-path update and Android installer gate

**Files:** Create `PersonalUpdateController.kt`, `AndroidPersonalApkVerifier.kt`,
`PersonalUpdateHighWaterStore.kt`, `AndroidPersonalUpdateInstaller.kt` under the same
Android personal update directory. Modify personal-only debug manifest/build fields,
`AppActivity.kt`, `AndroidMainScreen.kt`, and exact owner UI resource keys. Add focused tests
under `sharedUI/src/androidHostTest/kotlin/org/olcbox/app/update/personal/` and a personal
instrumentation test under `androidApp/src/androidTest/kotlin/org/olcbox/app/`.

**Interfaces:** Controller consumes Task 1 success and session-fenced exact update URL
proxy; exposes closed states Idle/Checking/Available/Downloading/Ready/SessionChanged/
Unavailable/Rejected, no bearer strings. `verifyApk(file: File, manifest,
baseline): VerifiedPersonalApk?` accepts only verified metadata/hash/size. Installer
consumes `VerifiedPersonalApk`, never arbitrary URL/file. High-water store commits
sequence+payloadSha256 atomically. Ordinary AppUpdateService is not reused.

- [ ] Write RED cases for cancelled/stale download, redirect, size/hash mismatch,
  wrong package/signer/version, missing companion, corrupted high-water and rejected permission.
- [ ] Implement separate exact update-purpose ticket without widening subscription URL fence.
  Recheck captured session after awaits; Checking returns blocked, not null/default HTTP.
- [ ] Implement bounded temporary download, actual APK verification and immutable handoff;
  personal-only install permission; foreground check limit 6h; explicit user install action.
- [ ] Preserve subscription/selection/Keystore on refusal; close/delete only owned cache artifacts;
  explain VPN restart and SessionChanged; no hidden settings or background install.
- [ ] Run focused source/Android tests and independent review. Build candidate with same
  established personal signer/versionCode=2; core binary gate still runs. Verify manifests
  of ordinary/Play variants do not gain personal install permission or update activation.
- [ ] Install this bootstrap v2 once through ADB without uninstalling. Verify same
  package/certificate and saved subscription/selection/Keystore; updater can now
  verify the pinned manifest. A 404/unavailable manifest does not stop the working VPN.

## Task 3 — RGA-only serving and A063 acceptance

**Files:** Private immutable staging and exact Home `rga.apollot.ru` update route;
operational receipts/resume stay ignored under the existing personal SDD directory.
No general subscription page change, no other Caddy host change.

**Interfaces:** Serves Task 1 signed manifest and a higher-version main candidate v3;
Task 2 bootstrap v2 already runs the verifier/controller. Frozen
companion remains installed. Server still denies unbound OlcRTC installations.

- [ ] Snapshot the owned prestate; validate served outputs and RGA-only route diff.
  Stage APK first, switch signed manifest last; retain exact rollback route/file state.
- [ ] Fetch via ordinary network and selected OlcRTC SOCKS; signature/hash/metadata pass;
  wrong/corrupt/redirect fixture must not reach installer. No broad server restart.
- [ ] On A063 let the owner accept system installation; prove versionCode=3,
  same package/signature, saved subscription/selection/Keystore, ordinary connection
  and restricted-network transition. Declined install leaves bootstrap version2 untouched.
- [ ] Verify own Stop and exact terminal/no active QA session. Do not repeat unchanged
  sleep/return suites unless update changed their inputs or exposed a failure.
- [ ] Record physical versus source evidence separately; leave personal OTA and
  general quota/release statuses truthful. Future fix rollback is a higher-version
  candidate, not a package downgrade or signer change.
