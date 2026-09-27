# Android Network Snapshot and DNS-Aware Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve ordered, generation-scoped physical-network facts from Android callbacks and recover the ordinary transport when a Wi-Fi DNS change invalidates its direct-routing front, without starting OlcRTC.

**Architecture:** A small pure ledger owns callback-derived network revisions; an Android adapter translates `NetworkCallback` values into immutable facts synchronously, before any coroutine is launched. The existing VPN service continues to own transport recovery, but a tested refresh decision distinguishes unchanged Wi-Fi from changed DNS or an unverified reconnect. This is the next client slice of the approved restricted-network design, not a full-TUN proof or automatic alternate transport.

**Tech Stack:** Kotlin Multiplatform `commonMain`/`commonTest`, Android `ConnectivityManager.NetworkCallback`, `NetworkCapabilities`, `LinkProperties`, `androidHostTest` with Robolectric, Gradle.

**Spec:** `docs/superpowers/specs/2026-09-27-apollo-rga-restricted-network-failover-design.md`

## Global Constraints

- The completed Stage 1a classifier and bounded direct probe remain diagnostic-only. `OrdinaryOnly` remains the release policy; do not start OlcRTC, change subscription data, or set `VpnStatus.Connected` from a snapshot.
- Each event is attached to the monitor-registration `generation` and an opaque, per-availability `networkId`. Repeated equivalent capabilities or link properties do not advance revision; a material DNS/link change does. Lost, cancelled and older-generation events cannot revive a prior network. A transport restart increments `generation`, so the service must replace the old callback registration rather than silently reuse its generation.
- Keep Android callback work synchronous and small: copy or derive its supplied capabilities/link properties before dispatching; do not query `ConnectivityManager.getNetworkCapabilities` or `getLinkProperties` inside the callback, bind the process globally, perform network I/O, or log DNS/IP/network IDs.
- A known changed Wi-Fi DNS set triggers ordinary recovery using the existing selected host and routing builder. A callback with incomplete DNS cannot itself declare a refresh benign or trigger a recovery loop. Only the same physical Wi-Fi network with unchanged known DNS may keep a **currently Connected** ordinary transport; `Reconnecting` must not become `Connected` solely because its process and bridge are alive. This does not claim full-TUN verification; that requires the separate one-APK proof gate in the spec.
- Manual stop/new generation invalidates in-flight observations. Do not touch servers, Remnawave, DNS, production, A063, Happ, or unrelated projects.
- The current full Android host suite has unrelated UI-host failures (including null `Build.FINGERPRINT` and an import timeout); report it as red until separately repaired. Do not conceal those failures or expand this plan into UI test-runner work. Use focused tests and a full JVM suite for this slice.

## Review Focus

1. A late callback from an old LTE network after Wi-Fi selection must not schedule recovery or replace selected facts; Tasks 1–2 test identity/generation and Task 3 tests an unselected event.
2. Repeated equivalent capabilities and link callbacks must not reset the five-second settling window; Tasks 1 and 2 test stable revision.
3. A changed DNS set on the same Wi-Fi `Network` must not be treated as a benign refresh; Tasks 1 and 3 test the revision and recovery decision.
4. A `Reconnecting` Wi-Fi session with a live core and bridge must not be promoted to `Connected` without a real reconnect/verification path; Task 3 tests this branch.
5. An incomplete/captive/offline callback sequence must not authorize restricted-network classification or a transport switch; Tasks 1 and 2 test incomplete facts, and Task 3 leaves alternate transport disabled.

---

### Task 1: Pure physical-network snapshot ledger

**Files:**
- Create: `sharedUI/src/commonMain/kotlin/org/olcbox/app/net/PhysicalNetworkSnapshotLedger.kt`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/net/PhysicalNetworkSnapshotLedgerTest.kt`

**Interfaces:**
- Produce `internal data class PhysicalNetworkSnapshot(val generation: Long, val networkId: Long, val revision: Long, val transport: UpstreamTransport?, val usable: Boolean, val validated: Boolean, val captive: Boolean, val dnsServers: List<String>?)`. A `null` transport or DNS list means the corresponding callback has not arrived; the snapshot is not probe-ready. Import the existing `org.olcbox.app.vpn.UpstreamTransport`.
- Produce `internal class PhysicalNetworkSnapshotLedger` with `available(generation: Long, networkId: Long): PhysicalNetworkSnapshot?`, `capabilities(generation: Long, networkId: Long, transport: UpstreamTransport?, usable: Boolean, validated: Boolean, captive: Boolean): PhysicalNetworkSnapshot?`, `linkProperties(generation: Long, networkId: Long, linkRevision: Long, dnsServers: List<String>): PhysicalNetworkSnapshot?`, `lost(generation: Long, networkId: Long): Unit`, `current(generation: Long, networkId: Long): PhysicalNetworkSnapshot?`, and `cancel(generation: Long): Unit`.
- `available` creates one entry for a new opaque ID; capabilities/link callbacks for an unavailable/lost ID return `null`. Snapshot revision starts at `0` and increments only when a received material fact changes, including the first capabilities/link callback. `linkRevision` is an adapter-local monotonic token for changed `LinkProperties`; an equal token and equal DNS list do not increment snapshot revision, and a lower token is ignored. Preserve DNS order because it is used by the direct-routing builder; copy the list. A new higher generation clears old entries; `cancel` rejects that generation and older. Keep at most 16 recent entries without logging identifiers.

- [ ] **Step 1: Write failing tests** named `equivalentCallbacksKeepRevisionAndSettling`, `changedDnsOrLinkAdvancesRevisionAndInvalidatesOldRound`, `lowerLinkRevisionCannotReplaceCurrentFacts`, `lostAndCancelledNetworkCannotReappearFromLateCallback`, and `incompleteOrCaptiveFactsAreNotProbeReady`. Use synthetic IDs, generations and literal DNS values; assert the old revision is rejected by the Stage 1a classifier after the new snapshot is supplied.
- [ ] **Step 2: Run RED:** `.\gradlew.bat --no-daemon :sharedUI:jvmTest --tests org.olcbox.app.net.PhysicalNetworkSnapshotLedgerTest --console=plain`. Expected: missing types or one of the behavior assertions fails.
- [ ] **Step 3: Implement the ledger** with only immutable facts and bounded in-memory entries. Expose `isProbeReady` as a derived property on the snapshot: `transport == Cellular && usable && !captive && !dnsServers.isNullOrEmpty()`; do not infer restricted status from this property.
- [ ] **Step 4: Run GREEN** with the Step 2 command and inspect XML. Expected: all five methods pass.
- [ ] **Step 5: Commit only these two files** with `feat: retain generation-scoped physical network snapshots`.

### Task 2: Android callback-value adapter

**Files:**
- Create: `sharedUI/src/androidMain/kotlin/org/olcbox/app/net/AndroidPhysicalNetworkSnapshots.kt`
- Test: `sharedUI/src/androidHostTest/kotlin/org/olcbox/app/net/AndroidPhysicalNetworkSnapshotsTest.kt`

**Interfaces:**
- Consume `PhysicalNetworkSnapshotLedger` and its `PhysicalNetworkSnapshot` from Task 1.
- Produce `internal class AndroidPhysicalNetworkSnapshots(private val generation: Long, private val ledger: PhysicalNetworkSnapshotLedger)` with `onAvailable(network: Network): PhysicalNetworkSnapshot?`, `onCapabilitiesChanged(network: Network, caps: NetworkCapabilities): PhysicalNetworkSnapshot?`, `onLinkPropertiesChanged(network: Network, link: LinkProperties): PhysicalNetworkSnapshot?`, `onLost(network: Network): Unit`, `current(network: Network): PhysicalNetworkSnapshot?`, `networkFor(snapshot: PhysicalNetworkSnapshot): Network?`, and `close(): Unit`.
- Allocate a monotonic opaque ID for each new `onAvailable`, map it to the exact `Network` inside one synchronized adapter, and preserve callback invocation order. Translate supplied capabilities to usable/validated/captive/transport; copy `LinkProperties` before comparing it, increment a per-network `linkRevision` only on a changed copy, and pass that token plus DNS addresses in supplied order to the ledger. Do not call `ConnectivityManager` inside these methods or expose network IDs in logs.

- [ ] **Step 1: Write failing Android host tests** `sameNetworkAndEquivalentCallbackKeepRevision`, `dnsChangeAndLostNetworkInvalidateSnapshot`, `oldRegistrationCannotPublishIntoNewGeneration`, and `copiedLinkPropertiesIgnoreCallerMutation`. Use Robolectric `Network` instances and controlled `NetworkCapabilities`/`LinkProperties`; no real connection. Assert `networkFor` returns the exact object only for a current snapshot.
- [ ] **Step 2: Run RED:** `.\gradlew.bat --no-daemon :sharedUI:testAndroidHostTest --tests org.olcbox.app.net.AndroidPhysicalNetworkSnapshotsTest --console=plain`. Expected: missing adapter or asserted behavior fails.
- [ ] **Step 3: Implement only the adapter.** Keep callback work bounded; `close()` cancels the registration generation in the ledger and discards `Network` references. No service or UI wiring in this task.
- [ ] **Step 4: Run GREEN** with the Step 2 command; inspect XML and Android compile diagnostics. Expected: all four methods pass.
- [ ] **Step 5: Commit only these two files** with `feat: capture Android physical-network callback values`.

### Task 3: DNS-aware ordinary recovery policy and service wiring

**Files:**
- Create: `sharedUI/src/commonMain/kotlin/org/olcbox/app/vpn/OrdinaryNetworkRefreshPolicy.kt`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/vpn/OrdinaryNetworkRefreshPolicyTest.kt`
- Modify: `sharedUI/src/androidMain/kotlin/org/olcbox/app/vpn/service/OlcboxVpnService.kt` (network callback, monitor registration/cleanup, same-network refresh and watchdog branches only)

**Interfaces:**
- Consume snapshots from Task 2. Produce `internal enum class NetworkRefreshDecision { Ignore, Keep, Recover, AwaitFacts }` and `internal object OrdinaryNetworkRefreshPolicy` with `fun decide(eventIsSelected: Boolean, sameNetwork: Boolean, previousTransport: UpstreamTransport?, nextTransport: UpstreamTransport?, previousDns: List<String>?, nextDns: List<String>?, status: VpnStatus): NetworkRefreshDecision`.
- The policy returns `Ignore` for an event from a nonselected physical network; `Keep` only for `Connected` on the **same** Wi-Fi network with both DNS lists known and equal; `Recover` for changed known DNS, another network, cellular transition or `Reconnecting` when facts are complete. Missing current DNS/transport returns `AwaitFacts`, never a benign declaration or an unbounded recovery loop. This is an ordinary recovery decision, never an OlcRTC decision.
- The service creates an `AndroidPhysicalNetworkSnapshots` and callback object per monitor registration using that transport `generation`; `registerNetworkMonitor()` replaces the prior registration when `startTunnel` increments the generation. Each callback first updates the adapter synchronously from callback-supplied values, then queues existing recovery work with its captured generation and snapshot. Add `onLinkPropertiesChanged`. Under `tunnelMutex`, reject work if its `(generation, networkId, revision)` is no longer current before acting. Compare the snapshot DNS with a copied `lastConnectedOrdinaryDns` baseline, which is promoted only after ordinary transport verification succeeds and cleared on stop; do not overwrite it merely because `updateUnderlyingNetwork` observed a new network. On stop/unregister call `close()` before a new registration. Existing selected host and ordinary routing remain authoritative.

- [ ] **Step 1: Write failing policy tests** `sameWifiAndSameDnsCanRemainConnected`, `changedWifiDnsOrPhysicalNetworkRequiresRecovery`, `reconnectingNeverPromotesOnBridgeLiveness`, `incompleteFactsWaitWithoutLoop`, and `oldLteEventIsIgnoredAfterWifiSelection`. Assert exact decisions with literal DNS lists and `VpnStatus` values.
- [ ] **Step 2: Run RED:** `.\gradlew.bat --no-daemon :sharedUI:jvmTest --tests org.olcbox.app.vpn.OrdinaryNetworkRefreshPolicyTest --console=plain`. Expected: policy type missing or assertions fail.
- [ ] **Step 3: Implement the policy** and minimally wire the adapter into the service. Resolve the selected upstream before calling the policy; an event from another `Network` is `Ignore`. In the same-network Wi-Fi branch, `Recover` calls existing `requestTransportRecovery` for the selected ordinary host only if a recovery job is not already active; `AwaitFacts` does nothing until a complete callback arrives, and `Keep` preserves the current Connected state. Do not call `setStatus(Connected)` on a snapshot or on `Reconnecting` bridge liveness. Preserve the existing non-DNS recovery path and no-connection behavior.
- [ ] **Step 4: Run GREEN** for the policy tests, the Task 1 focused JVM tests and Task 2 focused host tests; compile Android. Inspect XML, check `git diff --check`, and review the service diff for callback-time `ConnectivityManager` re-queries, secret/IP logging, OlcRTC calls and new `Connected` assignments. Expected: focused tests pass, no new such calls/assignments, Android compiles. Run the full JVM suite once. The known full Android host suite remains a separate red baseline, not a success claim.
- [ ] **Step 5: Commit only the three owned files** with `fix: recover ordinary transport when physical DNS changes`.

## Handoff and remaining gates

This plan does not run the Stage 1a direct probes in the service, collect the active selected-transport 64-KiB bulk result, prove a full TUN path, display restricted-network UX, start OlcRTC, or alter the signed ordinary release. Those belong to a subsequent diagnostic coordinator plan and the separately gated server/native/handoff stages in the approved spec. A063 Wi-Fi/LTE validation is needed before claiming that DNS-aware recovery improved real-device behavior; no phone test is implied by source/host checks.
