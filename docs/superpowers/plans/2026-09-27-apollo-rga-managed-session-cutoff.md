# Apollo.RGA managed OlcRTC session cutoff — local-only plan

**Approved design:** `../specs/2026-09-27-apollo-rga-access-bridge-design.md`, especially server-side active close and fail-closed expiry. This plan only prepares the pinned engine. It does not issue a grant, infer a quota, deploy a room, expose OlcRTC to subscribers or alter Remnawave.

**Boundary:** All fork edits happen in a separate ignored checkout of pinned Ghostlane `8d97e32e0b1af0a727d6e785c4c467f37543aa4d`. The tracked output is the exact-base patch and digest manifest; `.deps/olcrtc` remains pristine. Go 1.26.5 is the pinned test toolchain.

## Task 1 — prevent unmanaged UDP lifetime in managed mode

- [x] RED: managed-mode config must force UDP off even if the operator omitted `UDPDisabled`; non-managed default/explicit flags retain their behavior.
- [x] GREEN: `server.Run` computes effective UDP disablement from `RequireAuthHook || UDPDisabled`. Keep the mode hidden. Commit `4c7611b`.

**Why:** peer UDP flows are currently keyed by peer ID and flow ID, not an authenticated session generation. They may survive peer teardown and be reused by a replacement. Restricted-network OlcRTC may be TCP-only in this local stage; ordinary Hysteria 2 UDP is unaffected.

## Task 2 — addressable managed TCP cutoff

- [x] RED fork tests: revoke one peer while another remains; unknown/empty ID is a no-op; replacement peer with the same transport peer ID is not cut by stale revoke; a blocked data stream/connection closes before `CloseSession` returns. Cover the single-link path separately. Test an already-stopped server.
- [x] Add an internal controller bound only for the lifetime of `server.Run`, accepting exact session ID and closing all matching managed sessions. Reject calls in non-managed mode. Peer revocation removes only the captured peer generation and uses immediate data cutoff, not the 1.5-second graceful notice path. Single-link revocation checks the expected ID under the same lock that detaches its data plane, without closing unrelated peers.
- [x] Expose `CloseSession(sessionID)` on the embeddable `tunnel.Server`; keep callback errors and client-controlled identifiers out of logs. No implicit trust in PSK or device ID.
- [x] GREEN: affected server/tunnel/handshake/session packages, repeated focused tests, exact-base patch and digest, clean-base `git apply --check`, offline carrier tests, fresh materialization and source diff. Go race detector remains unavailable in this Windows environment (`CGO_ENABLED=0`, no GCC); no race-detector claim.

Independent source review found and the focused tests reproduced two additional important failures: a stale single-link handshake could mark a replacement carrier ready, and a blocked control FIN could delay data cutoff. Generation validation now precedes ID publication under `sessMu`; single-link data closes before the control stream. Both regressions pass repeatedly in the final materialized source.

## Completion boundary

An addressable close method is not yet an expiry mechanism. `CloseSession` operates on published active IDs only: a revoke arriving before handshake publication can miss the session. A trusted Bridge admission must eventually return a server-enforced deadline and revocation signal, with atomic registration or equivalent reconciliation for that publication gap; a streaming meter must fail closed before the budget is exhausted. The current `AuthHook` returns only an ID; no lease or quota is available. The server method remains unused by production and `policy.Evaluate` remains hardcoded default-off. Status-only refresh over restricted LTE needs a separate pre-admission control protocol and proof, never general egress after expiry.
