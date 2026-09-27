# Exact XRAY_JSON Regional Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Apollo.RGA's selected RU-direct routing and DNS policy work for its exact Remnawave XRAY_JSON hosts without altering the imported Xray graph.

**Architecture:** Keep authenticated Xray as the exact provider transport. In rule-based modes, put the existing sing-box SOCKS policy front between HEV and Xray; Global keeps the current single-Xray path. The front owns RU/direct vs foreign/proxy DNS and routes, with `.cc`/`.at` pinned to proxy before regional matches.

**Tech Stack:** Kotlin Multiplatform, Android VpnService, sing-box and Xray pinned native cores, Gradle JVM/Android host tests.

**Spec:** [Android release handoff, Exact XRAY_JSON regional routing section](../../operations/2026-09-26-apollo-rga-android-release-handoff.md#exact-xray_json-regional-routing--release-blocker-2026-09-27).

## Global Constraints

- Only `Apollo.RGA.Client` source and its separate controlled export; no changes to Remnawave, DNS, nodes, subscription page, GA Panel, LetsCube, Apollo.TF or INCY/Happ.
- Preserve imported Xray `dns`, `routing`, `outbounds` and provider-rule order. Runtime adaptation may still harden its single SOCKS inbound and disable logs as before.
- `.cc` and `.at` domains always use proxy and remote DNS in rule-based modes, even when a RU list contains them. Global retains the provider's original behavior.
- If a rule asset or either core fails, connection fails closed; no fallback to all-direct or all-proxy.
- Keep tokens, subscription URLs, core config and passwords out of app logs, source, CI evidence and user-visible output.

## Review Focus

- A user custom direct rule for `vk.cc` must not outrank the mandatory `.cc` proxy route or DNS rule; Task 1 tests precedence.
- A later RU list containing a new `.at` or `.cc` domain must still proxy it; Task 1 tests suffix-wide behavior.
- Restart/reuse must point HEV at the front port, not the Xray backend; Task 2 tests the plan and Task 3 checks device reconnect.
- A missing local `.srs` must abort before either core starts; Task 2 tests the routing-preparation failure boundary.
- Global mode must not require regional assets or start a second core; Task 2 tests it.

---

### Task 1: Mandatory proxy suffix precedence in the existing policy front

**Files:**
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/net/Routing.kt`
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/net/SingBoxConfig.kt`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/net/SingBoxConfigTest.kt`

**Interfaces:**
- `Routing.RuleBased.mandatoryTunnelSuffixes: List<String>` defaults to empty; `Routing.Rules` carries an optional value.
- The sing-box DNS and route rule builders use this list before user custom direct and RU region rules. Existing call sites with an empty list render their current graph.

- [x] Write `mandatoryProxySuffixesPrecedeCustomAndRuRules` with `Rules(..., mandatoryTunnelSuffixes = listOf("cc", "at"))`, a synthetic user direct `vk.cc`, and RU bypass. Assert the first policy DNS route chooses `dns-remote` for `cc/at`, and the first policy traffic route chooses `out` before custom-direct and RU rule-set entries; assert default foreign still `out`.
- [x] Run `./gradlew :sharedUI:jvmTest --tests org.olcbox.app.net.SingBoxConfigTest` and observe this new test fail on the missing interface/behavior.
- [x] Add the optional property and first-match DNS/route rules; do not change the empty-list rendering.
- [x] Run the focused test class and verify every test passes.
- [x] Commit only the owned model/builder/test files (`9e32bc4`).

### Task 2: Exact-Xray front plan and Android service wiring

**Files:**
- Create: `sharedUI/src/commonMain/kotlin/org/olcbox/app/net/ExactXrayChainPlan.kt`
- Modify: `sharedUI/src/androidMain/kotlin/org/olcbox/app/vpn/service/OlcboxVpnService.kt`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/net/ExactXrayChainPlanTest.kt`
- Test: `sharedUI/src/androidHostTest/kotlin/org/olcbox/app/net/ExactXrayServiceTest.kt`

**Interfaces:**
- `ExactXrayChainPlan.build(config: JsonObject, ingressPort: Int, backendPort: Int?, login: SocksLogin, routing: Routing, tunMode: Boolean): Built` returns the adapted Xray JSON, optional sing-box front JSON, the HEV-facing port and optional backend port. It pins `mandatoryTunnelSuffixes = listOf("cc", "at")` only when routing is `Routing.Rules`.
- `startExactXrayCore` receives the actual `Routing` from `routingFor(upstream)`, starts Xray then optional front, and requires the HEV-facing port and both processes healthy. It never falls back after a rule/preparation/start failure.

- [x] Write tests: `Global` preserves the current provider DNS/routing/outbounds with no front; `Rules` keeps those fields and yields an authenticated Xray backend plus policy front; missing/colliding backend port rejects without leaking config; current fixture's `bittorrent → direct` stays in Xray.
- [x] Run `./gradlew :sharedUI:jvmTest --tests org.olcbox.app.net.ExactXrayChainPlanTest` and verify it fails for missing behavior.
- [x] Implement the pure plan and route its outputs through the exact branch. Prepare rules before stopping/starting cores; never log untrusted exception text. The existing rule-file writer is the sole source of assets and must not silently degrade.
- [x] Run the focused common and Android host tests, then `:sharedUI:jvmTest` for affected routing and import boundaries; verify pass.
- [x] Commit only the owned plan/service/tests (`acaada1`).

### Task 3: Physical acceptance and replacement CI candidate

**Files:**
- Modify: `docs/operations/2026-09-26-apollo-rga-android-release-handoff.md`
- Modify only if needed for exact new artifact: `.github/workflows/apollo-rga-android.yml` and controlled public source export (no historical branch push).

**Interfaces:**
- Source commit and APK evidence must point to the same controlled public `main`; signing remains owner-approved in the `apollo-rga-candidate` Environment.

- [x] Validate the generated front with the pinned `sing-box check` and run focused Gradle tests plus Android assembly. Check source diff, actionlint if workflow changed, and secret scan before export.
- [ ] On A063, install same-signer candidate without clearing data. With a temporarily imported existing subscription and user permission, verify one exact VLESS and one Hy2 profile, RU domain/IP direct from the underlying network while VPN stays active, foreign egress through the chosen exit, `.cc/.at` proxy/DNS precedence, and no native IPv6 leak. Disconnect and remove the temporary list; check logs without printing secrets.
- [x] Export only reviewed source changes to the controlled repository, bump candidate version code, run clean CI and obtain a fresh manual owner approval for the protected sign job. Match final APK/evidence to the new commit, certificate and App Link.
- [x] Update the resume record with precise pass/fail and remaining HWID, long-sleep and Wi-Fi/LTE gates. Keep the production subscription card disabled until all required acceptance gates pass.
- [x] On the next candidate, export the dev-only exact-front dump/CI guard, validate its clean CI run and obtain the owner's protected sign approval; do not relabel the existing `a852787` APK as built with that guard. Done in public `f3b5d99`, CI run `36322793572`, signed artifact `10933222301`.

**2026-09-27 checkpoint:** focused and full JVM tests, Android host-focused tests, Android Kotlin compilation, local arm64 assembly, controlled eight-file export and public commit `a852787` are done. Clean CI run `36288920205` completed successfully after protected signing; its artifact/evidence hashes, v2/v3 signer, App Link certificate and native-core contents matched locally. The exact CI APK installed on A063 and kept App Link verified. A new synthetic exact-front JVM dump passed `sing-box check` using the same pinned Android binary and real local rule assets. Its dev workflow guard passed actionlint and the pinned host binary cross-built, but is not in the signed `a852787` source. On the signed CI APK, NL VLESS and Hy2 both passed RU-direct, foreign NL and `.cc/.at` IPv4 exit checks. Wi-Fi restored automatically after a switch; the current LTE connection could not reach the NL relay's TCP/443 even without VPN, while a RU control site worked. Short USB-powered screen-off continuity passed, but deep sleep, physical `.cc/.at` DNS attribution, dual-stack IPv6, permissive LTE handoff and real isolated HWID denial remain unverified. The test VPN was stopped; after A063 was unlocked, the temporary subscription was removed in-app (zero HTTPS sources, both core processes stopped). No production subscription card or server setting changed.

**Later candidate checkpoint:** public `f3b5d99` adds only the CI guard, its exact-config JVM dump and required validation script; it has no runtime-code delta from `a852787`. Protected run `36322793572` built and signed `1.0.2`/code `3`. An independent local pass of `verify-apollo-rga-release.py` matched the signed APK, source, certificate, exact App Link, published association, pinned sing-box/Xray cores and Xray receipt. A063 accepted the same-signer update without clearing data; Android reports `1.0.2` and a verified domain. The app is offline, contains no temporary subscription and has no active network-core process. The physical gates above remain open and the production card remains disabled.
