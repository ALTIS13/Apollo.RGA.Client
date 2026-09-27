# Apollo.RGA managed OlcRTC stream-time meter — next local stage

**Approved design:** `../specs/2026-09-27-apollo-rga-access-bridge-design.md`. Start only after the managed lease guard is reviewed. This plan does not grant subscribers access, change Remnawave or prove an enforced common Xray+OlcRTC quota. Existing `access-bridge/internal/store.RecordCumulative` is accounting only; registration is not admission.

## Task 1 — demonstrate the current accounting gap

- [ ] RED: a long-lived TCP stream has transferred bytes but the existing `OnTraffic` still reports none before EOF. Two concurrent streams/directions can exceed a proposed per-session byte lease if they reserve independently. A short write counts only successful bytes.
- [ ] RED: missing/expired byte lease prevents dial and CONNECT acknowledgement; an unavailable reporter never expands the local byte ceiling. Client sleep/kill cannot preserve an expired data stream.

## Task 2 — bounded local egress

- [ ] Give each authorized managed session one generation-bound aggregate byte budget for client→exit and exit→client across all TCP streams. Reserve before each write, commit only successful `n`, refund unwritten bytes, and close on exhaustion or arithmetic overflow. Ordinary non-managed copy behavior stays unchanged. The lease deadline and byte budget must both pass before dial/ACK.
- [ ] Use a per-session cancellation context for managed streams. Expiry/revoke closes only that generation's copies; a replaced peer or another subscription remains alive. Managed UDP remains disabled.

## Task 3 — monotonic cumulative report

- [ ] Observe successful writes in the copy path, not only final `OnTraffic`. Serialize immutable `(sessionID, sequence, clientToExitBytes, exitToClientBytes)` snapshots. Retry an ambiguous/failed acknowledgement with the identical sequence and bytes, single-flight and bounded in memory; never call the Bridge from the write hot path.
- [ ] Do not add final `OnTraffic` counts to managed cumulative totals. On shutdown, wait for active copy workers before a terminal snapshot or keep the unused/unconfirmed reservation held for reconciliation. A process crash must not release unacknowledged reservation as available quota.
- [ ] Match the existing durable `RecordCumulative` contract: same sequence and values replay safely; stale, conflicting or regressed reports fail. A future authenticated node adapter must bind the report to subject/session/room; a caller-supplied session ID is not authority.

## Task 4 — verification boundary

- [ ] Prove snapshots before EOF, bidirectional and multi-stream cap under concurrency, short writes, duplicate/unknown acknowledgement, reporter outage, expiry and no double count. Rebuild the exact-base patch and digest only after independent source review; materialize a fresh copy and run affected Go packages plus vet.
- [ ] Keep `policy.Evaluate` default-off. Xray-side quota behavior, installed Remnawave version, restricted LTE carrier, status-only path, physical A063 tests and release provenance remain separate gates before any subscriber rollout.
