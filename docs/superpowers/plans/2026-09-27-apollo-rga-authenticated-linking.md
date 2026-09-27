# Apollo.RGA Authenticated Installation Linking Implementation Plan

> **For agentic workers:** use superpowers:executing-plans or superpowers:subagent-driven-development. This plan is local-only and does not authorize a public API or OlcRTC access.

**Goal:** Bind an installation to a canonical Remnawave subject only after proof of possession, fresh bearer resolution and explicit device eligibility. Keep OlcRTC default-off.

**Architecture:** `access-bridge/internal/access` verifies a domain-separated P-256 challenge and orchestrates the existing read-only Remnawave adapter. `internal/store` durably issues single-use challenges and atomically consumes one with the installation binding and subject creation. The subscription bearer and full public key are never persisted; a SHA-256 fingerprint of canonical X.509 SPKI DER is persisted. The default device eligibility implementation denies all until installed Remnawave HWID semantics are proven.

**Tech:** Go 1.25 standard `crypto/ecdsa`, `crypto/x509`, SHA-256, PostgreSQL via existing pgx stdlib; local dedicated `apollo_rga_bridge_test` database.

**Spec:** `docs/superpowers/specs/2026-09-27-apollo-rga-access-bridge-design.md` (canonical subject, original subscription, HWID gate, no grant before common quota and node admission).

**Cross-platform key choice:** P-256 ECDSA with SHA-256, X.509 SPKI DER public key and ASN.1 DER signature. Android Keystore supports this at the app's `minSdk=23`; Windows CNG and Apple Secure Enclave support P-256. Windows raw signature conversion is a later client task. Sources: [Android Keystore](https://developer.android.com/privacy-and-security/keystore), [Windows CNG providers](https://learn.microsoft.com/en-us/windows/win32/seccertenroll/cng-key-storage-providers), [Apple Secure Enclave P256](https://developer.apple.com/documentation/cryptokit/secureenclave/p256).

## Constraints

- Only `D:/CodexProjects/Apollo.RGA.Client`; no Remnawave, DNS, node or GA/Apollo.GAP writes; never touch excluded domains.
- No public HTTP handler, grant, meter, quota, user-facing toggle or release flag. `policy.Evaluate` retains `enforcementReady=false`.
- `shortUuid` is a one-time bearer, not an identity or ledger key. It is not logged, stored, returned or passed to an OlcRTC node.
- Challenge is bound to configured panel instance, installation ID and canonical public-key fingerprint. The signed message includes protocol version, panel instance, installation ID, challenge ID and random nonce with unambiguous encoding.
- Only the server chooses panel instance/origin. Proof alone and bearer alone never authorize binding. Unknown HWID/device eligibility denies by default.
- Database errors are redacted. A failed proof, stale bearer, unknown eligibility or database failure never creates a subject or binding.
- Tests use only the dedicated local database and must verify its name before schema mutation.

## Review Focus

- One challenge cannot bind two subjects or keys, including concurrent completion; invalid/replayed/expired challenges have no side effects.
- A valid bearer with wrong signature and a valid signature with wrong bearer both fail; no fallback to bearer-only authorization.
- Fresh Remnawave status, expiry and quota must be valid; unknown device eligibility fails closed.
- Successful test-only eligibility binds canonical `(panelInstance,numericUserId)` and leaves OlcRTC disabled.
- Neither a rotated bearer nor a panel outage can silently continue with stale identity; errors reveal no bearer, DSN or key material.

### Task 1: Pure P-256 possession proof

**Files:** create `access-bridge/internal/access/proof.go`, `proof_test.go`.

- [ ] Write tests for valid Android-compatible P-256/SPKI/DER signatures, changed panel/install/challenge/nonce, wrong curve/key, noncanonical SPKI, oversized input and zero fingerprint; errors fixed/redacted.
- [ ] RED: `go test ./internal/access -count=1` fails because proof API is absent.
- [ ] Implement `LinkChallenge` validation and `VerifyLinkProof(challenge, publicKeyDER, signature) (InstallationBinding,error)` using standard crypto, `crypto/rand`-generated opaque challenge ID/nonce in `NewLinkChallenge`; no I/O.
- [ ] GREEN: focused test then full `go test -count=1 ./...`; commit only Task 1 files.

### Task 2: Durable one-time challenge consumption

**Files:** modify `access-bridge/internal/store/schema.sql`, `postgres.go`, `postgres_test.go`.

- [ ] Add PostgreSQL integration tests for create/read, expiry, idempotent migration, proof-context mismatch, one-time consume, concurrent two-subject race, key rebind denial, rollback and redacted DB outage. Reuse test helper that verifies `current_database() = 'apollo_rga_bridge_test'`.
- [ ] RED: `go test ./internal/store -count=1 -v` fails for absent methods.
- [ ] Implement `CreateChallenge`, `LoadChallenge`, `ConsumeChallengeAndBind` with database clock, row lock and one transaction; subject creation occurs only inside successful consume. Expose `ErrChallengeInvalid` without revealing challenge existence.
- [ ] GREEN: full `go test -count=1 ./...` with dedicated DSN and `go vet ./...`; commit only Task 2 files.

### Task 3: Internal link coordinator, no public access

**Files:** create `access-bridge/internal/access/link.go`, `link_test.go`.

- [ ] Add tests with fake resolver/store/eligibility for proof-before-lookup, panel/HTTP failure, rotated bearer, expired/limited/disabled account, unknown/denied eligibility, allowed test-only binding, replay and redaction. No live accounts.
- [ ] RED: `go test ./internal/access -count=1` fails for absent service.
- [ ] Implement internal `LinkService` using configured panel instance, existing `remnawave.Lookup`, `policy.Evaluate` for ordinary eligibility and an explicit `DeviceEligibility` interface whose default denies all. Do not expose admin token, bearer or raw public key in errors/logs.
- [ ] GREEN: full Go suite and vet; commit only Task 3 files.

## Completion boundary

The result is authenticated *association infrastructure*, not OlcRTC authorization. The default device gate denies even a valid proof until real installed-panel HWID semantics are confirmed. An external endpoint needs separate URL allowlist, rate limiting, request logging redaction, CSRF/replay protection review and private deployment design. Grant issuance remains blocked on shared quota/Xray-side enforcement, node admission/active close and restricted status-only carrier. No production or user-visible activation follows from this plan.
