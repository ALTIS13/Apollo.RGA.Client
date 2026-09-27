# Apollo.RGA Access Bridge Per-Cycle Reservation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a fail-closed, PostgreSQL-atomic small-byte reservation ledger that can later support server-side OlcRTC admission without claiming a proven shared Xray/OlcRTC quota today.

**Architecture:** A reservation binds the canonical subject, existing installation, room/country, one session ID and one `jti` to one verified subscription cycle. The transaction locks the subject and cycle, refreshes confirmed Xray usage, checks `Xray + committed OlcRTC + held reservations + requested <= finite limit`, and creates the lease and meter session together. Cumulative meter updates transfer bytes from held to committed in the same transaction; unused or unconfirmed bytes are never refunded in this slice.

**Tech Stack:** Go 1.25, `database/sql`, PostgreSQL, existing `access-bridge/internal/{access,store,remnawave}` module and dedicated PostgreSQL test fixture.

**Spec:** `docs/superpowers/specs/2026-09-27-apollo-rga-access-bridge-design.md`, especially “Контракт синхронизации и допуска” and “Общая квота: обязательное доказательство”. This is one local ledger slice, not the complete spec or a release plan.

## Global Constraints

- Work only in `D:/CodexProjects/Apollo.RGA.Client/access-bridge/` when executing. No production/Remnawave calls, panel writes, node endpoint, deployment, client rollout, or secret-bearing fixtures.
- Keep `policy.Evaluate`'s `enforcementReady=false` and `AlternateAllowed=false`. Neither a stored lease nor `RegisterMeterSession` authorizes a network session. Do not add a grant signer, HTTP/RPC handler or production evidence provider.
- Subject is `(panelInstance,numericUserId)`; no `shortUuid`, subscription URL, admin token or client-supplied budget fact enters ledger keys, errors or logs. Installation possession and same-country room availability still need separate authenticated admission checks.
- `BudgetEvidence` below is **trusted server-side input**, synthetic in these tests. Its real producer is blocked until the installed panel's stable cycle/reset identity, Xray-usage delivery lag and active Xray-side enforcement are independently established. Missing/unknown proof denies; a boolean in a client/node request must never stand in for proof.
- One immutable lease per session in this slice. A new `jti` cannot renew or enlarge that session; renewal and runtime grant semantics are later work. `MaxLeaseBytes` and `LeaseTTL` have no production values yet: zero/absent configuration denies, and tests use explicitly synthetic values.
- A new cycle must satisfy `CycleStart <= database now < CycleEnd` and must not overlap any earlier cycle of the same subject. A distinct ID alone is never evidence of a reset. Early reset needs a separately proven protocol that closes old leases; until then, deny it.
- Exact `jti` replay is idempotent accounting, not renewed authority. Before returning a stored lease, recheck the current enabled flag/revision, fresh matching-subject evidence, status and expiry; after disable or policy change, return denial rather than an old grant.
- Existing meter rows have no cycle attribution. Never backfill or guess a cycle. A subject with any unattributed meter session cannot reserve until a separate, evidenced reconciliation; once a cycle ledger exists, legacy `RegisterMeterSession` must not create another unattributed session for that subject.
- Only the dedicated, loopback PostgreSQL test database guarded by `testDatabase` and `APOLLO_RGA_TEST_POSTGRES_DSN` may be used for DB tests. No real subscriber, live panel or node is involved.

## File Map

- Modify `access-bridge/internal/store/schema.sql`: additive cycle and immutable lease tables, constraints and foreign keys; no destructive migration of existing meter rows.
- Create `access-bridge/internal/store/reservation.go` and `reservation_test.go`: input validation, atomic reservation, idempotency and concurrent budget tests.
- Modify `access-bridge/internal/store/meter.go` and `meter_test.go`: refuse legacy registration after cycle activation and atomically attribute reserved-session cumulative reports; preserve existing unreserved high-water behavior without treating it as admission.
- Do not change `access-bridge/internal/remnawave/client.go` or `internal/policy/policy.go` in this slice. The former currently reads by `shortUuid` and lacks verified cycle/lag facts; the latter intentionally keeps alternate access off.

## Review Focus

1. Old un-attributed meter bytes could be omitted from a new cycle: Task 1 test must deny any such subject, including a zero-byte registered session.
2. Ambiguous COMMIT and duplicate `jti` could reserve twice: Task 1 test must retry through a new DB handle and compare the same immutable lease while still eligible; conflicting bindings or an intervening disable/revision change must fail.
3. Two installations or a concurrent flag/revision change could overdraw the same subject: Task 1 test must race distinct `jti`s and the flag update; the sum of successful reservations stays within the limit.
4. A crash, terminal callback or late report could release unconfirmed bytes: Task 2 test must preserve the full hold until confirmed cumulative bytes replace it, including after reconnect and cycle expiry.
5. Counter overflow, over-lease traffic, a reset-looking Xray decrease, overlapping cycle ID or stale/unknown cycle proof could increase available quota: Tasks 1–2 tests must deny new reservations and retain actual reported high-water/overdrawn state.

---

### Task 1: Atomically reserve one cycle-bound lease and meter session

**Files:** modify `access-bridge/internal/store/schema.sql`, `meter.go`, `meter_test.go`; create `reservation.go`, `reservation_test.go`.

**Interfaces:**

```go
type BudgetEvidence struct {
    Subject access.Subject // must match ReservationRequest.Subject exactly
    CycleID string
    CycleStart, CycleEnd, ObservedAt, SubscriptionExpiresAt time.Time
    Status remnawave.Status
    TotalLimitBytes, XrayUsedBytes int64
    MeasuredXrayLag time.Duration // zero means unknown, not instant
    ActiveXrayEnforcementProven bool
}
type ReservationRequest struct {
    Subject access.Subject
    InstallationID, RoomID, Country, SessionID, JTI string
    PolicyRevision, RequestedBytes int64
}
type ReservationLimits struct { MaxLeaseBytes int64; LeaseTTL, MaxEvidenceAge, MaxXrayLag time.Duration }
type Reservation struct { JTI, SessionID string; ByteLimit int64; ExpiresAt time.Time }
func (s *Store) ReserveLease(ctx context.Context, req ReservationRequest, evidence BudgetEvidence, limits ReservationLimits) (Reservation, error)
```

`Country` is an uppercase two-letter country code; room authenticity/same-country verification is **not** established by this store API. `CycleID` is opaque and stable only if a future trusted panel adapter proves it. Reject empty/invalid identity fields, nonpositive or oversized request/limits, unlimited (`TotalLimitBytes=0`) in this finite-budget slice, non-active/expired subscription, zero/unknown cycle boundaries, unknown lag/enforcement, stale observation, lag above the configured maximum and arithmetic overflow. Derive lease expiry from database time as the minimum of `now+LeaseTTL`, subscription expiry and cycle end. Store errors are fixed redacted sentinels.

- [x] **RED:** Add `TestReservationRequiresVerifiedFiniteCycleAndBinding`: an existing bound installation, enabled flag/revision and synthetic active cycle can reserve 40 of a 100-byte limit with 60 Xray bytes; empty cycle, unknown status, missing lag/enforcement proof, stale observation, wrong installation, swapped-subject evidence, disabled flag, changed revision, expired subscription and `41` bytes all deny without a row. Add `TestReservationRejectsUnattributedMeterRows`, including a zero-byte legacy session.
- [x] **Run RED:** `cd access-bridge; go test ./internal/store -count=1 -run 'Reservation|MeterRegistration'`. Expect missing `ReserveLease`/schema behavior, not a skipped or vacuous test; the dedicated DB guard may skip DB cases when no safe test DSN exists.
- [x] **GREEN:** First require exact canonical subject equality between trusted evidence and request. Add `bridge_budget_cycles` keyed by subject/cycle with immutable boundaries, finite limit, latest confirmed Xray, committed OlcRTC, held bytes and overdrawn marker; add `bridge_budget_leases` with globally unique `jti` and `session_id`, subject/cycle FK, installation, room/country, revision, immutable byte limit and expiry. In one transaction lock subject before cycle, verify flag/revision and bound installation, refuse unattributed meter rows, reject same-cycle identity/limit drift or Xray regression, update Xray evidence, lock/check checked-int64 available budget, insert the `bridge_meter_sessions` row then its lease, and COMMIT. A valid newer Xray sample that exhausts the budget must still be committed (and deny the reservation), so a later older sample cannot reopen headroom. Keep every budget-changing path on the same subject→cycle→session lock order.
- [x] **RED:** Add `TestReservationDuplicateAndParallelBudget`: exact same `jti`/payload retry after a new DB handle returns the same unexpanded lease only while current eligibility still holds; different payload, expired/revoked retry, or same-jti replay after disable/revision change denies; same session with new `jti` denies; and two concurrent 60-byte requests against only 100 available yield at most one success. Race `SetProtocolEnabled(false)` and require no successful reservation after the new revision commits. Add unknown cycle/lag, Xray counter decrease, overlapping distinct cycle, cycle-boundary and int64-overflow cases; a valid higher Xray sample that denies a lease must remain durable and reject a later lower sample.
- [x] **GREEN:** Make the `jti` conflict path read the persisted immutable payload before any new hold; never overwrite or revive it, and revalidate current policy/evidence before returning it. A cycle rollover is accepted only with a distinct trusted cycle identity, `CycleStart <= database now < CycleEnd`, no overlap with any prior same-subject cycle and no lease beyond the previous cycle end. Keep `RegisterMeterSession` for legacy accounting only, but deny new legacy registration once a subject has a cycle row.
- [x] **Verify/commit:** Run the focused store tests and `go vet ./internal/store` with the dedicated test DB; inspect the exact owned-file diff and commit only this task's files. If the DB guard skips, report PostgreSQL concurrency as unverified instead of claiming GREEN.

### Task 2: Attribute immutable cumulative reports without refunding uncertainty

**Files:** modify `access-bridge/internal/store/meter.go`, `meter_test.go`; adjust `reservation.go`, `reservation_test.go` only for shared aggregate queries/tests.

**Interfaces:** Keep `MeterReport` and `RecordCumulative(ctx, report) error` unchanged. For a reserved session, its two counters and sequence remain the only traffic source; the lease's `ByteLimit` is a cap, not a second usage counter. No terminal/refund API is produced in this slice.

- [x] **RED:** Add `TestReservedMeterMovesHoldToCommittedOnce`: reserve 100, report `(seq=1, client=25, exit=15)`, and assert committed `40`, held `60`, unchanged total encumbrance `100`; exact replay, skipped sequence and new DB handle never count twice. Existing stale/conflict/regression tests remain valid. A legacy unreserved report must never be silently attributed to a new cycle.
- [x] **Run RED:** `cd access-bridge; go test ./internal/store -count=1 -run 'ReservedMeter|Reservation'`. Expect the new aggregate assertions to fail against the current high-water-only meter.
- [x] **GREEN:** Find the reserved session/cycle, lock subject→cycle→meter row in the same order as Task 1, compute checked per-direction cumulative deltas, update the existing high-water row and cycle committed/held counters in one transaction. For an exact replay do no aggregate mutation. Convert `min(delta, max(0, lease_limit - previous_session_total))` from held to committed; if trusted actual bytes exceed the lease, persist the full per-direction high-water, mark the cycle overdrawn and deny future leases rather than discard traffic. If signed-int64 aggregate addition overflows, persist the per-direction high-water and overdrawn marker without claiming an exact aggregate or any available budget. Do not add managed final `OnTraffic` totals as another delta.
- [x] **RED:** Add `TestReservedMeterCrashLateAndOverLimit`: after an indeterminate acknowledgement/reconnect, duplicate report is unchanged; unreported or terminal-only bytes leave the hold intact; an authenticated late report after lease/cycle expiry updates only its original cycle; over-lease bytes and checked-sum overflow never create available budget. No method refunds unused bytes on close or timeout.
- [x] **GREEN:** Keep the unused hold charged for the entire verified cycle, even after close, expiry or crash. Later reconciliation/refund needs a separately proven terminal handshake and a new plan. Never move a late report to a new cycle or reset the session's sequence/counters.
- [x] **Verify/commit:** Run `go test -count=1 ./...` and `go vet ./...` from `access-bridge/` against only the dedicated guarded DB; inspect exact-file diff, then commit only Task 2 files. If DB tests skip, state the missing proof explicitly.

## Completion Boundary and Unresolved Contracts

Passing local tests proves only PostgreSQL serialization, durable high-water conversion and conservative holds under synthetic trusted facts. It does **not** prove a usable server-side admission path or strict shared quota. Before any real grant: establish a canonical-ID Remnawave refresh (current `Client.Lookup` requires a bearer), stable reset/cycle identity and renewal semantics, measured Xray-usage lag and active-session enforcement/overrun bound on an isolated subscription, authenticated installation possession, authoritative room/country and node identity, grant wire/signing/replay rules, active server close, and status-only restricted-network carrier. Unknown or unavailable evidence denies new OlcRTC access; ordinary Xray remains untouched. `AlternateAllowed` stays false, with no endpoint, deployment or subscriber exposure from this plan.
