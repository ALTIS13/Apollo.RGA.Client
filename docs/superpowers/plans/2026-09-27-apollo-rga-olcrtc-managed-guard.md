# Apollo.RGA OlcRTC Managed Admission Guard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the pinned OlcRTC fork capable of failing closed before opening a managed server without a mandatory authorization hook, and carry that change reproducibly in Apollo.RGA.Client.

**Architecture:** Patch only a separately materialized copy of the pinned Ghostlane source; the ignored `.deps/olcrtc` stays pristine. A managed-mode guard in the fork rejects a missing hook before transport startup, authentication rejections expose no private hook error, and session-open logs omit client claims. A checked-in patch and offline materializer verify the exact base commit and patch digest. This is still not a grant, quota, status-only path or deploy.

**Tech Stack:** Go 1.26.5 as pinned in `scripts/cores-pins.sh`, Python 3 standard library for the local patch materializer, Git; existing Go tests in the fork and Python unittest in Apollo.RGA.Client.

**Spec:** `docs/superpowers/specs/2026-09-27-apollo-rga-access-bridge-design.md` (pinned fork requirements in “Обновление подписки при ограниченной сети” and “Безопасное внедрение”).

## Global Constraints

- Patch base: `8d97e32e0b1af0a727d6e785c4c467f37543aa4d`. The patch must not write to `.deps/olcrtc`, only to a newly created separate checkout.
- No Remnawave, node, DNS, GA/Apollo.GAP, Apple Cores, release workflow, publication or production changes in this plan.
- Existing non-managed CLI behavior is preserved; managed server explicitly sets `RequireAuthHook=true` and refuses nil `AuthHook`. A PSK, `deviceID` or client claims are not subscription admission.
- Return only fixed generic authorization failure to peer and server log. In managed mode, do not log client claims or raw client-controlled `deviceID`/hook-produced `sessionID`; these may themselves contain bearer material. No user-facing OlcRTC mode is enabled.
- `policy.Evaluate` stays hardcoded default-off. Runtime admission, active close, streaming meter, quota and status-only carrier remain later gates.

## Review Focus

- Managed mode with a nil hook: return a fixed error before key-ring setup, provider, stats or media initialization.
- Non-managed CLI with a nil hook: retain legacy behavior until a deliberate migration, without accidentally advertising it as subscription-safe.
- Hook returns an error containing a marker secret: neither wire `SERVER_REJECT`, client error, returned server error nor server log contains it.
- Client claims, `deviceID` or hook-produced `sessionID` contain a marker secret: managed session-open/close and peer-list logs never print it; an empty hook `sessionID` is rejected.
- Source/pin/patch drift, repeated application, existing output (including symlink/junction) or source/output overlap: the carrier refuses without touching its source and without presenting a usable patched output.

---

### Task 1: Managed auth and redaction patch

**Files:** create tracked `scripts/patches/olcrtc-rga-managed-guard.patch`; patch target files in temporary source copy: `internal/server/server.go`, `internal/server/server_test.go`, `internal/server/serve.go`, `internal/server/peer.go`, `internal/handshake/handshake.go`, `internal/handshake/handshake_test.go`, `internal/app/session/run.go`, `internal/app/session/run_test.go`, `pkg/olcrtc/tunnel/tunnel.go`, `pkg/olcrtc/tunnel/tunnel_test.go`.

**Interfaces:** `server.Config.RequireAuthHook bool`, `tunnel.Config.RequireAuthHook bool`; `server.ErrAuthHookRequired` fixed sentinel; `session.logSessionOpen(sessionID,deviceID string,_ map[string]any)`. Managed `Server` stores a redaction flag for lifecycle logs.

- [ ] Materialize a separate local checkout at exact patch base. Write fork tests first for the managed guard, generic wire/client/server reject, empty hook session ID, no-claims logger and managed lifecycle-log redaction; verify each focused test fails for the intended missing behavior.
- [ ] Implement the smallest fork changes: guard before `setupRing`, propagate `RequireAuthHook` through public tunnel config, fixed `SERVER_REJECT` reason and returned error, reject empty session ID, replace CLI claim logging with tested redacted callback, and suppress raw client/hook IDs in managed server lifecycle logs. Preserve non-managed semantics.
- [ ] Run focused fork tests (`./internal/server`, `./internal/handshake`, `./internal/app/session`, `./pkg/olcrtc/tunnel`) with pinned Go toolchain; run the fork's full practical suite if available. Export a unified Git patch from only the owned changed files to the tracked patch path, and verify `git apply --check` against pristine pinned source without applying there.
- [ ] Commit the patch only after source tests and exact-file diff review.

### Task 2: Offline hash-guarded patch carrier

**Files:** create `scripts/olcrtc-managed-patch.json`, `scripts/materialize-olcrtc-managed.py`, `scripts/test_materialize_olcrtc_managed.py`.

**Interfaces:** `python scripts/materialize-olcrtc-managed.py --source <clean-local-clone> --output <nonexistent-directory>`; manifest records full base SHA, patch relative path and SHA-256 digest. No network fallback or source mutation.

- [ ] Write Python tests for wrong HEAD, dirty source, patch digest mismatch, existing output (including symlink/junction), source/output overlap, successful materialization and repeated patch refusal. RED against absent carrier.
- [ ] Implement offline clone into a sibling staging directory, verify exact HEAD and clean tracked tree, verify patch digest, apply once with `git apply --check` then `git apply`, verify the resulting diff, then atomically rename to output. Refuse `os.path.lexists(output)`; on failure clean only the script-created staging path after checking its resolved path remains within the explicitly named output parent. Never delete source or an existing output.
- [ ] Run Python tests, source test quartet from materialized output, `git diff --check`, and commit only carrier files.

## Completion boundary

This patch makes a managed guard available; it does not wire a trusted Bridge admission hook, short lease, active close, live cumulative meter or status-only control. All release/PR/gate and iOS Cores jobs still fetch the base engine and must be changed together in a separate parity plan before any patched binary is shipped. Until that and live quota/HWID proofs pass, OlcRTC remains hidden and disabled for subscribers.
