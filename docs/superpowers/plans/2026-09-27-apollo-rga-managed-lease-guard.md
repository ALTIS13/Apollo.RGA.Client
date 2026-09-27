# Apollo.RGA managed OlcRTC lease guard — local-only plan

**Approved design:** `../specs/2026-09-27-apollo-rga-access-bridge-design.md`. This stage only hardens the pinned OlcRTC engine. No Bridge grant is issued, no production room is started, and ordinary subscribers cannot see the mode. The committed managed cutoff carrier at `cac7187` is the baseline.

**Source boundary:** Work in a separate ignored materialization of exact Ghostlane `8d97e32e0b1af0a727d6e785c4c467f37543aa4d` plus the reviewed Apollo.RGA patch. `.deps/olcrtc` stays pristine. Only after source verification may the cumulative patch and SHA-256 manifest change. Non-managed `AuthHook` and CLI behavior must remain compatible.

## Task 1 — trusted lease contract

- [ ] RED: managed lease mode rejects a missing hook, empty/reused session ID, zero/past deadline, overlong deadline and an already-revoked ID before any `SERVER_WELCOME` or data readiness. Legacy `RequireAuthHook` remains a cutoff primitive, not subscription-safe admission.
- [ ] Add an opt-in `RequireLease` with a separate trusted `ManagedLeaseAuthHook` returning `{SessionID, NotAfter}`. Do not derive a lease from PSK, device ID, client claims or the accounting meter. Keep all new admission default-off.

## Task 2 — close the handshake publication window

- [ ] RED: in singleton, peer-control and data-only peer paths, block after the managed hook returns but before WELCOME, revoke the ID, then release the handshake. No path may activate the ID, signal readiness, accept a CONNECT or leave a peer running.
- [ ] Maintain bounded pending/active/revoked lease state with a process-local generation. Register pending before WELCOME; a trusted in-process revoke records a tombstone even if the session is not yet published. Publish then activate only after rechecking revoke and deadline, before callbacks/readiness. Abort pending on failed handshakes and teardown. Reject duplicate IDs while remembered. Never evict a live tombstone to accept new access; overflow fails closed.
- [ ] Preserve the existing `CloseSession(activeID)` contract and add a distinct authoritative lease-revoke method for the Bridge integration; a stale timer must target the exact generation and not close a replacement.

## Task 3 — finite server-side lifetime

- [ ] RED: a frozen client with an open TCP stream is cut off by the node at `NotAfter` without a client refresh or Bridge push. Revoke before deadline also cuts off. A failed or absent renewal cannot extend the local deadline.
- [ ] The node owns the deadline timer/context and closes the exact active managed generation. Ordinary non-managed sessions retain their current behavior. No UDP grant in managed mode.

## Task 4 — independent validation and carrier

- [ ] Repeat focused concurrency/handshake tests, affected Go packages and vet. Review the diff for deadlocks, stale-generation closure and secret-bearing logs. Run exact-base `git apply --check`, offline carrier tests, materialize a new clean copy and rerun affected package tests. Update patch digest only with reviewed bytes. Do not call this production-ready or a common quota: stream-time meter, Bridge admission, Xray-side proof, status-only restricted carrier and physical A063 checks remain separate gates.
