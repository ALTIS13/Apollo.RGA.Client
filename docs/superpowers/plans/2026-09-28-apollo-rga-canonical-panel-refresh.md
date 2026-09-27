# Apollo.RGA Access Bridge Canonical Remnawave Refresh Plan

**Goal:** Read the currently linked Remnawave user by canonical numeric ID for later policy reconciliation, without reusing the subscription bearer or issuing any OlcRTC grant.

**Scope:** `access-bridge/internal/remnawave/client.go` and its tests only. The existing `GET /api/users/{id}` path is already used after initial bearer resolution. This plan adds an internal `LookupByID(ctx, id)` entry point; it does not add a route, service wiring, BudgetEvidence producer, lease call, deployment, or panel write.

**Safety contract:** Reject nonpositive IDs before network access. Require response ID to match the requested ID and retain strict status, expiry, traffic, strategy and response-size checks. Return the existing redacted `UserSnapshot` and fixed error sentinels; never return or log `shortUuid`, subscription URL, admin token, or raw response. Preserve the initial `Lookup(shortUuid)` anti-rotation check. Nonloopback HTTPS, timeout and redirect guard remain mandatory.

- [x] **RED:** Add fake-server tests for `LookupByID`: exactly one authenticated `GET /api/users/42`, no bearer in path/body; mismatched/absent ID, missing or unknown policy facts, invalid ID before network, redacted HTTP/transport errors, cancellation, oversized response, redirect and bounded timeout deny safely. Keep the existing `Lookup` tests passing.
- [x] **Run RED:** `cd access-bridge; go test -count=1 ./internal/remnawave -run 'LookupByID|Lookup'`. Confirm the new tests actually fail on the missing method.
- [x] **GREEN:** Reuse one strict response parser for bearer-based lookup and numeric-ID lookup; require short UUID equality only when the initial bearer was supplied, and require numeric ID equality in both paths. No new endpoint or broad client abstraction.
- [x] **Verify/review:** Run focused Remnawave tests and `go vet ./internal/remnawave`; inspect exact diff and independent review for response drift, credential leakage and fail-open paths. Commit only the two owned source files, then mark this plan complete.

**Completion boundary:** A valid `UserSnapshot` does not prove stable reset-cycle identity, measured Xray usage lag, active Xray-side enforcement, installation eligibility or room identity. It must not be transformed into trusted `BudgetEvidence` or positive `AlternateAllowed`; policy remains default-off. Installed-panel compatibility and production admission require separate evidence and approval.
