# Apollo.RGA Restricted-Network Classification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a testable, fail-closed detector for a restricted Android mobile network without changing the selected host, VPN status, OlcRTC availability, or production configuration.

**Architecture:** A pure Kotlin reducer accepts only generation-scoped, physical-network-scoped evidence and decides whether restrictions are suspected. A separate Android adapter performs three bounded HTTPS controls through the exact `Network` with `Proxy.NO_PROXY`; no ordinary proxy, global process binding, or old one-foreign `WhitelistCheck` participates. This plan deliberately stops before service activation/UX, admission, route conversion, native runtime, or transport handoff; those are separately gated subsystems in the spec.

**Tech Stack:** Kotlin Multiplatform `commonMain`/`commonTest`, Android `Network`/`HttpURLConnection`, `androidHostTest` with Robolectric, Gradle.

**Spec:** `docs/superpowers/specs/2026-09-27-apollo-rga-restricted-network-failover-design.md`

## Global Constraints

- `OrdinaryOnly` remains the installed-app release policy. No new OlcRTC library, room, grant, host, subscription, account, server, DNS, Remnawave, or production change.
- Classification requires 5 seconds on one stable cellular `Network`, two direct rounds at least 3 seconds apart, two independently operated foreign failures and a domestic HTTPS response in **each** round, then a failed selected-transport bulk check. Reuse `TransportCheck.passes(client)` for that later check: 65,536 bytes within 10 seconds; do not replace it with a HEAD-only test.
- If selected SOCKS succeeds but TUN/bridge fails, report local bridge failure, never `RestrictedSuspect`. Offline, captive, partial, stale, Wi-Fi, and unknown evidence remains `Inconclusive`.
- Each direct control has a 4-second maximum and reads no response body. One assessment attempt per physical network per minute at most; retry backoff is bounded at 5 minutes. No periodic background scanner.
- Every result carries VPN `generation`, opaque physical-network ID and link/capability revision. Manual disconnect invalidates outstanding work. Never log network IDs, IPs, probe bodies, subscription URLs, bearer tokens, room keys, or HWIDs.
- This detector is **not** a full-TUN proof and must not set `VpnStatus.Connected`, start OlcRTC, change the selected exit, or run automatically in the signed ordinary release. Stage 1 service wiring/UX is a later plan after its safety seams are specified.

## Review Focus

1. A callback from the old LTE after Wi-Fi returns must not classify the new session; Task 1 tests generation/network/revision mismatches.
2. Repeated `onCapabilitiesChanged` for the same network must not restart the 5-second settling clock; Task 1 tests unchanged revision.
3. A working SOCKS path with a broken TUN must report `LocalBridgeFailure`, not a restricted network; Task 1 tests that precedence.
4. A captive portal, foreign HTTP challenge, or redirect must not be mistaken for two independent network blocks; Tasks 1 and 2 test unknown evidence and no redirect following.
5. A system/global proxy or previous VPN-process binding must not contaminate direct controls; Task 2 tests the explicit `Network`/`Proxy.NO_PROXY` production seam and no `URL.openConnection()` fallback.

---

### Task 1: Pure, generation-scoped classification

**Files:**
- Create: `sharedUI/src/commonMain/kotlin/org/olcbox/app/net/RestrictedNetworkClassifier.kt`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/net/RestrictedNetworkClassifierTest.kt`

**Interfaces:**
- Produce `internal enum class ProbeOutcome { Answered, Failed, Unknown }` and `internal enum class RestrictedAssessment { Inconclusive, OrdinaryAvailable, LocalBridgeFailure, RestrictedSuspect }`.
- Produce `internal data class DirectProbeRound(val generation: Long, val networkId: Long, val revision: Long, val completedAtMs: Long, val foreignA: ProbeOutcome, val foreignB: ProbeOutcome, val domestic: ProbeOutcome)`.
- Produce `internal class RestrictedNetworkClassifier` with `onNetwork(generation: Long, networkId: Long, revision: Long, cellular: Boolean, captive: Boolean, nowMs: Long): Unit`, `begin(generation: Long, networkId: Long, revision: Long, nowMs: Long): Boolean`, `record(round: DirectProbeRound): Boolean`, `finish(generation: Long, networkId: Long, revision: Long, nowMs: Long, selectedTransport: ProbeOutcome, tunBridge: ProbeOutcome): RestrictedAssessment`, and `cancel(generation: Long): Unit`. Times are monotonic elapsed milliseconds, never wall-clock time. `record` returns false for stale/out-of-attempt input.
- `begin` opens one attempt only after 5,000 ms of unchanged cellular network/revision, not captive, and after its cooldown. `finish` accepts exactly two matching direct rounds separated by at least 3,000 ms; only two foreign `Failed` and domestic `Answered` in both plus selected transport `Failed` yield `RestrictedSuspect`. Selected transport `Answered` + bridge `Failed` yields `LocalBridgeFailure` first. An attempt sets per-network retry delay 60, 120, 240, then 300 seconds (capped); retain bounded recent network IDs across Wi-Fi flaps without persisting or logging them.

- [ ] **Step 1: Write failing reducer tests** in `RestrictedNetworkClassifierTest.kt`: `stableCellularWithTwoFailedForeignControlsAndFailedBulkIsSuspect`, `sameRevisionDoesNotResetSettling`, `oneForeignAnswerOrDomesticFailureIsInconclusive`, `workingSocksWithBrokenBridgeIsLocalFailure`, `staleGenerationNetworkAndDnsRevisionAreRejected`, and `manualCancelAndCooldownPreventNewAttempt`. Use synthetic IDs and monotonic times `0`, `5_000`, `8_000`, `65_000`; the positive test's decisive assertions are:

```kotlin
assertFalse(detector.begin(1, 17, 1, 4_999))
assertTrue(detector.begin(1, 17, 1, 5_000))
assertTrue(detector.record(DirectProbeRound(1, 17, 1, 5_000, Failed, Failed, Answered)))
assertEquals(Inconclusive, detector.finish(1, 17, 1, 5_000, Failed, Unknown))
assertTrue(detector.record(DirectProbeRound(1, 17, 1, 8_000, Failed, Failed, Answered)))
assertEquals(RestrictedSuspect, detector.finish(1, 17, 1, 8_000, Failed, Unknown))
assertFalse(detector.begin(1, 17, 1, 64_999))
```

`finish` before the second round returns `Inconclusive` without closing the attempt. For the negative tests assert `Inconclusive` when one foreign control answers, domestic fails, Wi-Fi/captive/offline/backwards-clock input appears, or the tuple is stale; assert `LocalBridgeFailure` when selected transport answers and bridge fails. A repeated same-revision `onNetwork` at `4_999` must still allow `begin` at `5_000`; `cancel(1)` must reject a delayed round from generation 1.
- [ ] **Step 2: Run the RED tests:** `.\gradlew.bat --no-daemon :sharedUI:jvmTest --tests org.olcbox.app.net.RestrictedNetworkClassifierTest --console=plain`. Expected: missing type or the newly asserted behavior fails; a pass before implementation means the test is not exposing new behavior.
- [ ] **Step 3: Implement only the above reducer** in `RestrictedNetworkClassifier.kt`. Keep the latest network tuple and two accepted rounds in memory; reject mismatched tuples before evaluating results. Keep cooldown keyed by opaque network ID with a small bounded map. Do not call Android APIs, network I/O, `SmartConnect`, `WhitelistCheck`, or app status APIs.
- [ ] **Step 4: Run the GREEN test command from Step 2.** Expected: all new test methods pass; inspect the XML result, not only Gradle `UP-TO-DATE`.
- [ ] **Step 5: Commit only the two owned files:** `git add sharedUI/src/commonMain/kotlin/org/olcbox/app/net/RestrictedNetworkClassifier.kt sharedUI/src/commonTest/kotlin/org/olcbox/app/net/RestrictedNetworkClassifierTest.kt` then `git commit -m "feat: classify restricted mobile evidence without switching"`.

### Task 2: Android physical-network-bound direct controls

**Files:**
- Create: `sharedUI/src/androidMain/kotlin/org/olcbox/app/net/BoundPhysicalNetworkProbe.kt`
- Test: `sharedUI/src/androidHostTest/kotlin/org/olcbox/app/net/BoundPhysicalNetworkProbeTest.kt`

**Interfaces:**
- Consume `DirectProbeRound`/`ProbeOutcome` from Task 1.
- Produce `internal class BoundPhysicalNetworkProbe(private val opener: (Network, URL) -> URLConnection = { network, url -> network.openConnection(url, Proxy.NO_PROXY) }, private val clockMs: () -> Long = { SystemClock.elapsedRealtime() })` with `suspend fun collect(network: Network, generation: Long, networkId: Long, revision: Long): DirectProbeRound`. Read `clockMs()` **after** the three probes complete so `completedAtMs` is not the start time.
- Three HTTPS controls, on the same supplied `Network`: `https://www.gstatic.com/generate_204` expects exactly 204; `https://www.cloudflare.com/cdn-cgi/trace` expects exactly 200; `https://ya.ru/` expects 2xx. Count a timeout/connect failure as `Failed`; a redirect, challenge/unexpected HTTP status, TLS/certificate error, or adapter error as `Unknown`. Never read the Cloudflare body (it contains requester metadata). No redirects, cookies, global proxy or process-wide binding. All three requests run concurrently with a bounded 4-second round and closed connections.

- [ ] **Step 1: Write failing host tests** `usesSameNetworkAndNoProxyForThreeHttpsControls`, `rejectsRedirectChallengeAndTlsErrorAsUnknown`, `timesOutAndClosesConnectionsWithoutReadingBodies`, and `returnsGenerationAndRevisionUnchanged`. Create a `Network` with Robolectric `ShadowNetwork.newInstance(17)` and fake `HttpURLConnection` instances for codes 204/200/200, then 302/403 and a timeout. A test-specific Robolectric shadow for `Network.openConnection(URL, Proxy)` must record the production default opener's `Proxy.NO_PROXY` argument. The decisive assertions are:

```kotlin
assertEquals(setOf("www.gstatic.com", "www.cloudflare.com", "ya.ru"), openedHosts.toSet())
assertEquals(3, openedHosts.size)
assertTrue(openedNetworks.all { it === network })
assertTrue(openedProxies.all { it == Proxy.NO_PROXY })
assertEquals(0, bodyReadCount)
assertEquals(3, disconnectCount)
assertEquals(DirectProbeRound(1, 17, 2, 8_000, Answered, Answered, Answered), result)
```

Assert `Unknown` for redirect, HTTP challenge, and TLS error; `Failed` for a timeout; every result must preserve the input generation/network/revision. The test must not make a real HTTP request.
- [ ] **Step 2: Run the RED host tests:** `.\gradlew.bat --no-daemon :sharedUI:testAndroidHostTest --tests org.olcbox.app.net.BoundPhysicalNetworkProbeTest --console=plain`. Expected: missing class or asserted behavior fails.
- [ ] **Step 3: Implement the probe** in `BoundPhysicalNetworkProbe.kt` with `Dispatchers.IO`, explicit 4,000-ms total deadline, per-connection connect/read bounds within it, disabled redirects/cache, and `disconnect()` in `finally`. Catch cancellation separately and propagate it; convert only network failures to `ProbeOutcome`, without exception text/URL logging. Do not run it from a callback or service yet.
- [ ] **Step 4: Run the GREEN host test and focused shared test commands.** Expected: host tests and `RestrictedNetworkClassifierTest` pass; inspect test XML and Android compile diagnostics. Check `git diff --check` and scan only new lines for secret-shaped literals.
- [ ] **Step 5: Commit only the two owned files:** `git add sharedUI/src/androidMain/kotlin/org/olcbox/app/net/BoundPhysicalNetworkProbe.kt sharedUI/src/androidHostTest/kotlin/org/olcbox/app/net/BoundPhysicalNetworkProbeTest.kt` then `git commit -m "feat: probe direct controls on a selected Android network"`.

## Handoff and explicit gaps

- This plan yields a runnable, tested diagnostic building block, **not** auto-failover. Do not claim a user-visible fix, a successful OlcRTC connection, or a full TUN-path test. `WhitelistCheck` remains a separate preconnect hint, not the new detector.
- Next client plan owns ordered `NetworkCallback` snapshots (including `onLinkPropertiesChanged`), selected-transport/bridge evidence capture, event-triggered invocation, debug-only UX, and no false Connected. It must not activate OlcRTC until server admission/ledger/bootstrap, route parity, native provenance, one-APK TUN proof and no-gap handoff have their own accepted plans and real-system evidence.
- Verification is targeted source/host testing here. A063 physical Wi-Fi/LTE diagnosis and a signed ordinary candidate are separate later checks; do not reinstall or disturb the phone simply to validate these pure components.
