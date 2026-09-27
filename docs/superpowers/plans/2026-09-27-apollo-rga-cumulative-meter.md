# Apollo.RGA Durable OlcRTC Cumulative Meter Implementation Plan

> **For agentic workers:** use superpowers:executing-plans or subagent-driven-development. This is local accounting infrastructure only, not a common-quota implementation or production release.

**Goal:** Persist two-direction cumulative OlcRTC traffic per internally registered session with monotonic, idempotent updates and no double count after retries, reordering or process reconnect. Registration is not admission.

**Architecture:** Extend the independent Access Bridge PostgreSQL store. A session must first be explicitly registered against an existing canonical Remnawave subject; arbitrary traffic reports never create users or sessions. Each report holds a global session ID, increasing sequence and two cumulative byte counters. A transaction locks the session row, advances the committed high-water mark and rejects regression/conflicts. The bytes remain **unattributed to a Remnawave reset cycle** until installed-panel cycle semantics and Xray usage lag are proven. No admission, reservations, quota decisions or panel writes in this plan.

**Sources:** Approved `docs/superpowers/specs/2026-09-27-apollo-rga-access-bridge-design.md`; [Remnawave API v3.4.4](https://docs.rw/api/) documents `userTraffic.usedTrafficBytes`, strategies and reset/update actions, but no atomic external-byte injection; [webhook docs](https://docs.rw/features/webhooks/) do not promise an exactly-once ordered usage ledger.

## Constraints

- Work only in `D:/CodexProjects/Apollo.RGA.Client`; no Remnawave, node, DNS, Apollo.GAP, GA Panel or excluded-domain writes.
- `policy.Evaluate` keeps `enforcementReady=false`; no grant, lease, combined remaining bytes, traffic-limit change, user-facing OlcRTC or deployment.
- Methods are internal and must not be wired to a network handler in this slice. Later authenticated node identity and grant admission must bind the session ID to subject, installation and node before registration/reporting. Neither a bare session ID nor a stored row is authorization.
- The session ID is a 16–128-character opaque URL-safe identifier, globally unique. Subject is `(panelInstance,numericUserId)`. Counters and sequence are nonnegative signed 64-bit values; first report sequence starts at 1.
- Equal sequence with equal counters is a no-op; equal sequence with changed counters is conflict. Older sequence is stale and cannot alter totals. Higher sequence with lower counter is regression; gaps are accepted because counters are cumulative.
- Re-registering the same session ID for the same subject is an idempotent success and never resets counters; another subject receives a conflict. This permits a safe retry after an indeterminate database COMMIT result. Reusing that ID for a different physical session would cause a counter regression, not create a fresh meter.
- Database errors return fixed redacted sentinels; no report payload, session ID or database string in an error/log. Unknown session and a session belonging to another subject have the same error.
- PostgreSQL tests require `APOLLO_RGA_TEST_POSTGRES_DSN` and verify database name, dedicated role and loopback listener before schema writes. No test touches production.
- Reports return only an acknowledgement/error, never a delta for a caller-side aggregate. A future common-quota aggregate must be updated atomically in the same database transaction or derived from committed high-water rows.

## Review Focus

- Missing subject/session and wrong-subject session ID never create or reassign a row.
- Duplicate, out-of-order, counter-regressed and concurrent reports never overcount, even after a new database handle.
- No cycle or combined quota is inferred from the stored byte totals; they cannot accidentally set `AlternateAllowed`.
- A database outage returns `ErrUnavailable` without a successful meter acknowledgement. The future admission/runtime integration must deny or close OlcRTC on this result; no runtime fail-closed claim is made by this storage-only slice. Ordinary Xray is untouched.

### Task 1: Register an unprivileged meter session

**Files:** modify `access-bridge/internal/store/schema.sql`, `postgres_test.go`; create `access-bridge/internal/store/meter.go`, `meter_test.go`.

**Interface:** `RegisterMeterSession(ctx, subject, sessionID) error`; sentinels `ErrMeterSessionUnknown`, `ErrMeterSessionConflict`, `ErrMeterInvalid`, `ErrMeterStale`, `ErrMeterRegression`, `ErrMeterConflict` (latter report errors may be introduced in Task 2).

- [ ] Write real-PostgreSQL tests for idempotent migration, registration only for an existing subject, globally unique session ID, idempotent same-subject registration, no cross-subject reassignment, invalid IDs and redacted database errors. Reuse the dedicated test DB guard.
- [ ] RED: focused `go test ./internal/store -count=1 -run Meter` fails for absent API.
- [ ] Add `bridge_meter_sessions` with session ID PK, non-cascading subject FK, sequence zero, two byte counters zero, nonnegative SQL checks and timestamps; implement transaction-backed registration. Same-subject retry is an unchanged success; cross-subject reuse is rejected. No retry may reset counters.
- [ ] GREEN: focused tests, full `go test -count=1 ./...`, `go vet ./...`; commit only Task 1 files.

### Task 2: Monotonic cumulative updates

**Files:** modify `access-bridge/internal/store/meter.go`, `meter_test.go`.

**Interface:** `MeterReport{Subject; SessionID; Sequence; ClientToExitBytes; ExitToClientBytes}`, `RecordCumulative(ctx, report) error`. The committed high-water row is the only result; a retry after an indeterminate commit may acknowledge without applying a second delta.

- [ ] Write real-PostgreSQL tests for first/high-water delta, exact duplicate, same-sequence conflict, stale/out-of-order report, counter regression, skipped sequence, parallel reports, process/DB-handle restart and database failure redaction. Retry registration after traffic and assert no counter reset. Assert no subject/session creation from a report and no inferred cycle/quota columns.
- [ ] RED: focused `go test ./internal/store -count=1 -run Meter` fails for absent report API.
- [ ] Implement locked transaction and high-water update with nonnegative int64 guards. An exact duplicate returns nil without mutation; stale/conflict/regression returns a fixed sentinel without mutation. Do not return a caller-consumable delta.
- [ ] GREEN: full Go suite on dedicated database, vet, exact-file diff check; commit Task 2 only.

## Completion boundary

These bytes are not yet an authoritative shared subscription counter. A later isolated proof must establish Remnawave `lastTrafficResetAt`/strategy/manual-reset/renewal cycle identity, Xray usage delay, impact of limit/disable on active Xray sessions and measured overrun bound. Only then can a reservation ledger and node grants use combined quota. At unknown cycle, stale panel usage, panel/ledger outage or missing admission, new OlcRTC access must be denied; ordinary Xray continues normally. The pinned fork still needs mandatory admission, stream-time meter, active close and restricted status-only carrier. No production deployment follows from this plan.
