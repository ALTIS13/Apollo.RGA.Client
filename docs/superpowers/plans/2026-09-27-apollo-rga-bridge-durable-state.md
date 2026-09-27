# Apollo.RGA Bridge Durable State Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist canonical Remnawave subject identity, installation-key binding and the per-subscription OlcRTC switch/revision without issuing a network grant.

**Architecture:** The independent `access-bridge/` Go module owns a PostgreSQL-backed state store. Remnawave remains the authority for status, expiry and Xray bytes; this store holds only the extra OlcRTC switch and installation association. Node admission, byte leases, shared quota, public HTTP and client activation remain absent.

**Tech Stack:** Go 1.25; PostgreSQL 14+; `github.com/jackc/pgx/v5/stdlib` pinned to v5.11.0; `database/sql` transactions; local ephemeral PostgreSQL 18 for integration checks.

**Spec:** `docs/superpowers/specs/2026-09-27-apollo-rga-access-bridge-design.md` and `docs/superpowers/specs/2026-09-27-apollo-rga-restricted-network-failover-design.md`.

## Global Constraints

- Work only in `D:/CodexProjects/Apollo.RGA.Client`; do not touch GA Panel, Apollo.GAP, DNS, nodes, Remnawave production or excluded domains.
- Existing Remnawave subscription link remains the sole user link; this slice neither accepts nor stores that bearer.
- `olcrtc_enabled` defaults to false, and `policy.Evaluate` retains `enforcementReady=false`; no release/runtime gate changes.
- `panelInstance` is an operator-configured stable identifier, never a client-selected URL; numeric Remnawave user ID is not globally unique by itself.
- A stored key fingerprint is not proof of possession; no caller may treat `BindInstallation` as admission until authenticated linking is implemented.
- All database errors returned to callers are redacted sentinels; never include DSNs, SQL parameters, installation IDs or keys in logs.
- Local PostgreSQL integration tests use only a dedicated `apollo_rga_bridge_test` database; they must not mutate an arbitrary configured database.

## Review Focus

- Two panel instances reuse numeric user ID 42: switch, revision and installation ownership remain isolated.
- A missing subject or database outage never enables OlcRTC or disables an ordinary Xray host.
- Concurrent installation binding cannot reassign one installation ID to a different subject/key.
- Concurrent switch changes cannot lose a revision; repeated identical updates do not invent policy changes.
- Process/database connection restart preserves the disabled state and revision; schema migration is idempotent.

---

### Task 1: Canonical identity and input validation

**Files:**
- Create: `access-bridge/internal/access/subject.go`
- Test: `access-bridge/internal/access/subject_test.go`

**Interfaces:**
- Produces: `Subject{PanelInstance string; UserID int64}` with `Validate() error`; `InstallationBinding{ID string; PublicKeySHA256 [32]byte}` with `Validate() error`; `SubjectState{Subject; OlcRTCEnabled bool; Revision int64}`.
- `PanelInstance` is lowercase ASCII `[a-z0-9][a-z0-9-]{0,63}`, ID is positive; installation ID is 16–128 URL-safe opaque characters; fingerprint is non-zero. Validation errors are fixed redacted sentinels.

- [ ] **Step 1: Write tests** for accepted canonical pair, rejected empty/URL/mixed-case instance and nonpositive user ID, rejected raw URL/short installation ID and zero fingerprint. Tests assert only sentinel errors, never echo inputs.
- [ ] **Step 2: Run RED:** `go test ./internal/access -count=1`; expected failure because `Subject`/`InstallationBinding` do not exist.
- [ ] **Step 3: Implement** the three types and validation without I/O or logging.
- [ ] **Step 4: Run GREEN:** `go test ./internal/access -count=1`; expected pass.
- [ ] **Step 5: Commit only Task 1 files** after `git diff --check`.

### Task 2: Durable default-off switch and installation binding

**Files:**
- Modify: `access-bridge/go.mod`, `access-bridge/go.sum`
- Create: `access-bridge/internal/store/schema.sql`, `access-bridge/internal/store/postgres.go`
- Test: `access-bridge/internal/store/postgres_test.go`

**Interfaces:**
- Consumes: Task 1 `access.Subject`, `access.InstallationBinding`, `access.SubjectState`.
- Produces: `New(db *sql.DB) (*Store, error)` with an unexported database handle, and methods `Migrate(ctx) error`, `EnsureSubject(ctx, subject) (SubjectState, error)`, `Load(ctx, subject) (SubjectState, error)`, `BindInstallation(ctx, subject, binding) error`, `SetProtocolEnabled(ctx, subject, enabled) (SubjectState, error)`; sentinels `ErrUnavailable`, `ErrNotFound`, `ErrInstallationConflict`.
- PostgreSQL tables have composite `(panel_instance,user_id)` subject key, `olcrtc_enabled BOOLEAN NOT NULL DEFAULT FALSE`, `policy_revision BIGINT NOT NULL DEFAULT 1`, and globally unique opaque `installation_id` with `(panel_instance,user_id)` foreign key and 32-byte fingerprint. State mutations use transactions/row locks; unchanged flag returns the same revision. No grants, meter or quota columns.

- [ ] **Step 1: Add real-PostgreSQL tests** for idempotent migration, default false, same numeric ID on two panels, state persistence after recreating the Store/database handle, idempotent bind, competing rebind denial, repeated flag update, concurrent updates, and redacted database failure. Tests require `APOLLO_RGA_TEST_POSTGRES_DSN` and verify `current_database() = 'apollo_rga_bridge_test'` before any schema write.
- [ ] **Step 2: Run RED:** `go test ./internal/store -count=1 -v` with the dedicated test DSN; expected failure because store API does not exist.
- [ ] **Step 3: Implement** PostgreSQL schema and transaction-backed store; pin pgx v5.11.0. Use fixed sentinel errors for database failures; reject invalid Task 1 inputs before SQL.
- [ ] **Step 4: Run GREEN:** `go test -count=1 ./...` with the dedicated test DSN, then `go vet ./...`; expected all tests pass, no skipped store integration tests or vet findings.
- [ ] **Step 5: Commit only Task 2 files** after inspecting `go.mod`/`go.sum`, SQL and `git diff --check`.

## Completion boundary

This plan produces durable control state only. It does **not** authorize OlcRTC traffic. Before a grant can be issued, separately implement authenticated linking/HWID reconciliation, shared Xray+OlcRTC cycle accounting and atomic reservations, node-side admission/renewal/active close, stream-time metering, and a restricted status-only carrier. The pinned fork currently lacks mandatory admission, exported active close and that status-only channel. No production deployment or normal-user release follows from passing these tests.
