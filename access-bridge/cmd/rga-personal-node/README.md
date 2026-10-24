# Personal A063 OlcRTC node (source-only)

This executable embeds the managed OlcRTC tunnel directly. It never invokes
the ordinary OlcRTC CLI/default auth and does not expose general subscriber
admission. The server-owned JSON file binds one existing Telemost room, one
Bridge subject, one linked installation and one country. The bare `room_id` is
used **identically** for the signed join proof and native `RoomURL`; `provider`
and `transport` are fixed to `telemost` and `vp8channel`.

Explicit managed roles separate the provider locator from the signed authority
channel. Their engine-compatible pairs are fixed: `telemost` / `vp8channel` and
`salutejazz` / `datachannel`. SaluteJazz has no video-track API; assigning VP8
fails before connection. Neither discovery nor client input may select another
pair. Lease, admission, metering and role boundaries remain required.

Set `RGA_PERSONAL_NODE_CONFIG` to the absolute path of a protected JSON file:

```json
{
  "postgres_dsn": "<server-only PostgreSQL URI>",
  "panel_origin": "https://<trusted Remnawave panel origin>",
  "panel_admin_token": "<server-only API token or ephemeral administrator JWT>",
  "panel_auth_kind": "api_token",
  "panel_instance": "<configured panel instance>",
  "user_id": 0,
  "installation_id": "<pre-bound A063 installation ID>",
  "installation_public_key_sha256": "<64 lowercase hex characters>",
  "room_id": "<bare existing Telemost room ID>",
  "country": "NL",
  "key_hex": "<64 lowercase hex characters shared with the personal client>",
  "lease_ttl_seconds": 300,
  "max_snapshot_age_seconds": 30,
  "bootstrap_tls_cert_file": "<optional server certificate file>",
  "bootstrap_tls_key_file": "<optional server private key file>"
}
```

Replace every placeholder before use; never commit this file or put it in an
APK. The parser rejects unknown/duplicate fields and files larger than 16 KiB.
On Linux the node refuses a non-regular config file or any group/other mode
bits: provision it as `0400` or `0600` owned by the service user (or root with
an explicitly controlled launch identity). Keep the containing directory
protected as well. Windows source builds do not attempt to infer ACL safety.
`panel_auth_kind` may be omitted (the default dedicated API-token path),
`api_token`, or explicitly `browser_session` for a short-lived personal-local
administrator JWT. Only `browser_session` adds
`X-Remnawave-Client-Type: browser` to panel requests. It creates no cookie jar
and never follows redirects; the ordinary API-token path is unchanged. The
JWT remains server-only in the protected config file. This setting does not
retrieve a token, log in, grant access, or provision a user.
The two bootstrap TLS fields are optional but must appear together. With both
present, the node serves **HTTPS only** on `127.0.0.1:18443` over `tcp4`;
without them, no bootstrap listener starts. The certificate and private key
must form a valid pair. On Linux, the private key must be a regular owner-only
file (`0400` or `0600`); the certificate may be public. Neither TLS filenames
nor TLS parser errors are logged. Provision a test certificate trusted/pinned
by the A063 client separately; do not use a public listener or reverse proxy.

The bounded JSON API is two exact POST paths, without queries or redirects:

- `/v1/personal/link/begin`: `shortUuid`, `installationId`, `publicKeyDer`
  (unpadded base64url SPKI DER) to `challengeId`, `panelInstance`, `nonce`
  (unpadded base64url), `expiresAt` (UTC RFC3339).
- `/v1/personal/bootstrap`: `shortUuid`, `challengeId`, `publicKeyDer`,
  `signature` (unpadded base64url) to the existing `BootstrapV1` JSON.

All responses are `Cache-Control: no-store`. Denial is HTTP 403 with
`{"error":"denied"}`, a temporary backend error is 503 with
`{"error":"unavailable"}`, invalid input is 400, and rate excess is 429.
No request body, bearer, proof, room key or backend error is logged. The
single server-owned installation is checked before either service call;
there is a bounded global 30/minute and installation 20/minute guard. Headers
such as `X-Forwarded-For` never establish identity. Local `adb reverse` and
Android test-certificate trust are separate device-test steps, not performed
by this source-only executable.
The per-session byte cap is fixed server-side at 1 TiB as a technical guard,
not a monthly quota; it is not configurable here and is not charged to the
shared Remnawave cycle. Durable QA meter acknowledgements and the native
30-second pending-report watchdog still fail closed. The existing Bridge schema
and A063 subject must be provisioned separately: this executable does not run
migrations or change account policy.

For an offline source build, supply a **clean checkout at the pinned OlcRTC
base** to `build-verified.ps1`, plus a local output filename. The script checks
the authoritative base pin and managed patch hashes, materializes the fork in
a temporary directory, creates a transient `go.work`, runs node tests, and
builds the executable without adding a `replace` to tracked `go.mod`.

```powershell
./build-verified.ps1 -PinnedSource <clean-pinned-olcrtc-checkout> -Output <local-exe-path>
```

After a successful join, Bridge renews only that session every two minutes.
A temporary Bridge/Panel outage retries after 25 seconds without extending the
old lease; native expiry still cuts traffic. Definitive denial cuts the exact
session immediately. Native disconnect and shutdown attempt a bounded journal
close. If the database is unavailable during final close, the remaining QA
row requires operator reconciliation; this source-only build is not deployment
or proof of provider-side room deletion.
