# Apollo.RGA Access Bridge — read-only foundation plan

**Goal:** Give Apollo.RGA a separate, testable subscription-policy module without a second user account, production access, grants or false claims of common quota enforcement. This is the first implementation slice of `../specs/2026-09-27-apollo-rga-access-bridge-design.md`, not an OlcRTC release.

**Ownership:** `access-bridge/` is an independent Go module in this checkout; `sharedUI` only consumes a redacted policy DTO. Remnawave remains the source of subscription facts. No tracked GA Panel changes, production calls, server/DNS changes, app release, or OlcRTC activation.

**Platform order:** Android first; Windows only after Android is complete; Android TV only after Android and Windows; macOS only after all three. A shared DTO is not evidence of platform acceptance. This foundation adds no Windows/TV/macOS runtime or UI.

**Resume (owner: `/root`, 2026-09-27):** Foundation tasks 1–3 implemented in `e34d86c`, `0409354`, `6d6428a`, `1e3e24d`; Go tests/vet and focused/full JVM plus Android compilation passed. Blocker for OlcRTC admission: no shared cycle ledger, active server close, limited status-only channel or proven production API. Next: contract/test plan for the pinned fork and Bridge session enforcement, without production activation.

**Review focus:** No URL/admin token in errors or logs; unknown status/expiry/usage denies OlcRTC; panel outage does not revoke the ordinary subscription; per-user OlcRTC flag defaults false; DTO cannot itself authorize native runtime; response field names and units stay aligned between Go and Kotlin.

### Task 1 — read-only Remnawave adapter (Go)

Files: `access-bridge/go.mod`, `access-bridge/internal/remnawave/client.go`, `access-bridge/internal/remnawave/client_test.go`.

Interface: `Client.Lookup(ctx context.Context, shortUUID string) (UserSnapshot, error)` against configured panel origin/token. Use documented `POST /api/users/resolve` with `shortUuid` in JSON then `GET /api/users/{id}`; do not put the bearer in a GET path or log it. `UserSnapshot` contains only numeric ID, status, expiry, traffic limit/reset strategy, current used bytes, and HWID limit. Validate HTTPS for nonloopback origins, positive ID, known status, sane nonnegative counters and response bounds. Client calls have finite timeout. No operation that writes to Remnawave.

- [x] RED: host tests for exact two-request lookup, unknown status, missing usage, non-200, oversized response, cancellation, and secret-free errors; reviewer-triggered rotated-bearer and 404/401/403 cases added.
- [x] GREEN: implement the small adapter with standard-library `net/http` and `encoding/json`; no logging of request URL/body or response.
- [x] Verify `go test -count=1 ./...` from `access-bridge/`, `go vet ./...`, and focused diff. Commit owned files only.

### Task 2 — pure availability policy (Go)

Files: `access-bridge/internal/policy/policy.go`, `access-bridge/internal/policy/policy_test.go`.

Interface: `Evaluate(now, UserSnapshot, ProtocolFlag) Decision`. `ProtocolFlag` is per `(panelInstance, numericUserId)`, absent means disabled. Decision has a redacted reason, subscription expiry, limit/used/remaining and `alternateAllowed=false` until both the flag and a later server-enforcement gate are true. Model the latter as explicit `enforcementReady=false` in this foundation. No ordinary-host disable action and no grant generation.

- [x] RED: active/disabled/limited/expired, edge-of-expiry, zero/unlimited limit, missing flag, exceeded bytes, and unavailable enforcement. Ensure every foundation case keeps `alternateAllowed=false`.
- [x] GREEN: implement pure evaluation; no API, DB or time access inside the function.
- [x] Verify `go test -count=1 ./...` and `go vet ./...`; commit owned files only.

### Task 3 — client-facing DTO without activation (Kotlin)

Files: `sharedUI/src/commonMain/kotlin/org/olcbox/app/net/AccessPolicySnapshot.kt`, `sharedUI/src/commonTest/kotlin/org/olcbox/app/net/AccessPolicySnapshotTest.kt`.

Interface: strict `PolicySnapshotV1` decoder for redacted status/expiry/remaining/flag/reason/revision/serverTime/refreshAfter; reject missing/unknown required fields and negative byte values. `alternateAllowed` is informational only: `RuntimeLocationPolicy.OrdinaryOnly` remains unchanged and `LocationKind.Olcrtc` cannot start. Do not store or log full subscription URL in DTO.

- [x] RED: valid active snapshot and malformed/unknown/negative/stale/quota-inconsistent variants, plus regression that ordinary release still denies OlcRTC.
- [x] GREEN: implement decoder and safe presentation classification only; no HTTP sync or native binding in this slice.
- [x] Verify focused JVM tests, full JVM suite, Android compile, diff check; commit owned files only.

### Remaining gates

This foundation cannot be deployed or exposed as an allowance. Its Kotlin parser is not yet a network DTO for shared quota: it lacks cycle end and confirmed OlcRTC usage/reservations. Next plans must add authenticated installation linking, durable per-user flag, server-side admission and incremental cumulative egress meter, bounded server-enforced lease/active close, Xray-side enforcement proof, a status-only bootstrap for expired users in a restricted network, client fetch/cache, and physical single-APK/TUN tests. Existing subscription refresh through SOCKS may update the same URL after connection, but it is not a revocation mechanism. Only after all gates and owner production decision may release policy change from `OrdinaryOnly`.
