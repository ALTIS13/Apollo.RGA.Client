# Apollo.RGA.Client

## Purpose and current state

Apollo.RGA is an Android-first client for an existing Remnawave subscription, with ordinary VLESS/Reality/XHTTP and Hysteria 2 paths plus separately gated OlcRTC work. The ordinary application ID is `tech.gatealtas.rga`; personal test code uses `tech.gatealtas.rga.personal` and must not enable general subscriber admission.

`main` consolidates the RGA client baseline, personal stop/recovery work, the newer OlcRTC/Android/Access Bridge stack, signed personal OTA source, and isolated HomeNode deployment packaging. This is source and candidate work, not a general user release. Do not infer production readiness, a shared ordinary/OlcRTC quota, or physical-device acceptance from source tests.

## Architecture

- `sharedUI/`: Kotlin Multiplatform domain, subscription/routing logic, Compose UI, VPN contracts, personal update code, and common/JVM/Android-host tests.
- `androidApp/`: Android application, manifests, JNI/native tunnel integration, resources, and instrumentation tests.
- `desktopApp/` and `iosApp/`: existing non-Android hosts; do not claim their checks were run on an unsupported host.
- `access-bridge/`: Go access policy, authenticated linking, Remnawave integration, durable QA accounting, personal admission, and `cmd/rga-personal-node`.
- `scripts/` and `androidApp/src/main/jni/`: pinned OlcRTC materialization, native patches, release tooling, and native proofs. Keep tracked modules free of ad-hoc `replace` directives.
- `access-bridge/deploy/personal-homenode/` and `deployment/personal-homenode.compose.yaml`: isolated single-installation deployment packaging, not general rollout configuration.

## Infrastructure and safety

Remnawave remains the source of the existing subscription, hosts, expiry, and ordinary limits. The personal node binds one installation, subject, room, and country; its PostgreSQL journal is isolated QA state and does not debit the ordinary Remnawave cycle. HomeNode uses protected configuration, a loopback-only TLS bootstrap behind the existing Caddy route, an internal database network, and an isolated egress gateway.

Never commit subscription URLs, room keys, tokens, DSNs, signing material, protected configs, dumps, or private acceptance evidence. Keep `.ops-private/`, `.remnawave-ops/`, `.worktrees/`, dependencies, build outputs, and caches out of commits. `origin` retains the Ghostlane/upstream identity; `rga` is the Apollo RGA remote. Do not push Apollo work to `origin`.

## Verification

Use JDK 21. Android builds require Android SDK 37 and NDK `28.2.13676358`; ordinary release assemblies additionally require the pinned arm64 native executables described in `README.MD`.

From the repository root on Windows:

```powershell
.\gradlew.bat --no-daemon :sharedUI:jvmTest :desktopApp:compileKotlin --console=plain
.\gradlew.bat --no-daemon :sharedUI:testAndroidHostTest :androidApp:assembleDebug --console=plain
python -m unittest scripts.test_materialize_olcrtc_managed scripts.test_gate_scripts scripts.test_android_cores scripts.test_apollo_rga_release
```

On Unix, use `./gradlew` in place of `.\gradlew.bat`. Read test-result XML; `UP-TO-DATE` alone is not a fresh test pass. Run Android instrumentation separately with a device when required:

```powershell
.\gradlew.bat --no-daemon :androidApp:connectedDebugAndroidTest -Polcbox.android.abiFilters=x86_64
```

Access Bridge checks:

```bash
cd access-bridge
go test -count=1 ./...
go vet ./...
```

For a pinned personal-node build, follow `access-bridge/cmd/rga-personal-node/build-verified.ps1`; it requires a clean checkout at the pin in `scripts/olcrtc-managed-patch.json`. HomeNode input validation and Linux Docker checks are documented in `access-bridge/deploy/personal-homenode/README.md`. Do not label unavailable SDK, native, device, Linux Docker, or deployment checks as passes.

## Unfinished acceptance work

- Complete cold start directly on restricted LTE, HomeNode SIGTERM-under-traffic, full provider-peer attribution, and remaining physical network checks.
- Keep crash/database-outage accounting tails unconfirmed until scoped reconciliation; do not rewrite them to zero.
- Finish the remaining personal OTA device/selected-path/retention checks before treating OTA as accepted; ordinary Realme checks also remain environment-dependent.
- Do not enable general OlcRTC subscribers or claim shared quota, UDP/IPv6 support, production readiness, or a public APK release without separate evidence and approval.

## REA routing

- Route Apollo product, Android/shared UI, Access Bridge, personal OTA, and HomeNode work to this repository; route generic Ghostlane engine changes to the appropriate upstream project instead of `origin`.
- Keep changes in the owning layer: shared behavior/tests in `sharedUI`, Android adapters in `androidApp` or `sharedUI/src/androidMain`, server policy/accounting in `access-bridge`, pinned native changes in `scripts` plus managed patches, and deployment-only changes in the deployment files.
- Treat requirements, source evidence, and acceptance evidence separately. A source test does not satisfy a physical-device, native, Linux Docker, provider, or deployment gate.

Detailed product and release constraints live in `PRODUCT.md`, `README.MD`, `docs/superpowers/specs/`, and the two personal operational READMEs referenced above; this file is the only root agent-context document.
