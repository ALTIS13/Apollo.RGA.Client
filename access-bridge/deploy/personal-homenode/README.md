# Personal HomeNode packaging

This is isolated Linux/Coolify Compose packaging for the existing single-installation
managed node. The coordinator activated the personal test on 2026-10-01 through
a separate deployment-only Raw Compose application, not a general subscriber rollout.
Its existing QA journal/binding was preserved; ordinary Remnawave quota is not debited.
The database network is **internal-only**;
the node also has a dedicated carrier-control bridge for direct HomeNode RU
Panel/Telemost signalling and media, per the coordinator's explicit scope.
The server source now accepts a required SOCKS destination dialer and an explicit
private TLS listener. The deployed personal configuration enables that dialer;
Home -> RU Relay -> NL destination routing and external-browser traffic were
verified. Complete provider-peer attribution and process-stop acceptance remain
separate checks, not implied by listener health.
Do not deploy this packaging against an older executable or disable the required
proxy to make a failed route appear available.

## Build inputs

Use the existing `scripts/materialize-olcrtc-managed.py` with a clean checkout at
the authoritative pin in `scripts/olcrtc-managed-patch.json`. It checks both patch
hashes before producing managed sources. In a transient Go workspace containing
`access-bridge` and those managed sources, run the existing node tests on the
native build host, then build `./cmd/rga-personal-node` with `GOOS=linux`, the exact
HomeNode `GOARCH`, and `CGO_ENABLED=0`. Use the Go toolchain required by both modules;
do not add a tracked `replace`. Record source/pins/patch hashes/toolchain/test
result and binary SHA-256. Place only the resulting static ELF in
`bin/rga-personal-node`. Run `python validate-inputs.py --binary bin/rga-personal-node
--sha256 <recorded-sha256> --arch <amd64-or-arm64>` before building. A Windows PE
artifact or a mismatched architecture is not a Linux deployment input.

Build context is this directory, not the repository. `.dockerignore` admits only
the two Dockerfiles and exact binary. No config, password, TLS key, room key, or DSN
goes into an image. Environment inputs (not secrets):

- `RUNTIME_BASE`: reviewed Alpine runtime reference pinned as `name@sha256:...`.
- `NODE_SHA256`: exact verified static ELF SHA-256; image build enforces it.
- `PERSONAL_NODE_IMAGE`: private versioned artifact tag; record resulting digest.
- `PG_IMAGE`: reviewed PostgreSQL **18** image pinned by digest, preserving the
  existing personal QA dump's major version. PG18 mounts `/var/lib/postgresql`,
  not the pre-18 `/var/lib/postgresql/data` path. Production Remnawave DB is separate.
- `PERSONAL_CONFIG_DIR`: existing protected absolute host directory.
- `PG_PASSWORD_FILE`: existing protected absolute host password file.
- `GATEWAY_IMAGE`: private versioned SSH gateway artifact tag; record its digest.
- `GATEWAY_CONFIG_DIR`: protected host directory for forwarding-only keys, exact
  host pins and SSH client config. The gateway's private SOCKS port has no host
  publication and only the node shares its internal `ru-egress` network.
The current HomeNode Caddy is a host systemd service. The node publishes TLS
only at host `127.0.0.1:18443`; it is not exposed on public/LAN interfaces. Caddy
must verify the protected upstream certificate and identity for that loopback
endpoint. Do not add a second container ingress network or automatic Coolify
public proxy labels merely to connect the existing host Caddy.

From this directory: `docker compose config --quiet`, then `docker compose build
personal-node`. Image build/runtime testing must occur on a Linux Docker engine.
Coolify must retain the same mounts, limits, signal/grace, network restriction,
and persistence; do not attach its public proxy or add automatic public labels.
The gateway supplies SOCKS on the stack-owned internal network. Unique forwarding-
only service keys reach RU Relay and then the NL private address; neither account
has shell, sudo, remote forwarding or password login. RU permits only the NL SSH
target. Persisted keys never enter images. Validate the running path independently;
source configuration is not runtime proof.

## Protected config and persistence

Provision `/run/personal/node.json` from the example outside Git and build context.
The example uses the implemented fields `bootstrap_listen_address`,
`require_egress_proxy`, and `egress_socks_*`; older executables reject them.
Set `require_egress_proxy=true`, exact gateway address/port and server-only auth.
Its directory is owner-only (0700); node JSON and TLS private key are regular
0400/0600 files owned by host UID/GID 10001:10001. TLS cert is public-readable.
The running non-root node must read those files. Mounts deliberately require
existing paths and are read-only. Browser-session auto-refresh, if explicitly
selected, additionally requires `RGA_PERSONAL_PANEL_CREDENTIALS_FILE` and a
protected matching-origin credentials file; the default skeleton uses an API
token and never transports administrator passwords to the client.

The QA DB initializer password file must be regular owner-only and readable by
the reviewed Postgres image's initialization identity. Its `rga_qa_admin` role is
not the node runtime role. After restore, provision `rga_personal` NOSUPERUSER,
NOCREATEDB, NOCREATEROLE with only schema usage and table/sequence access.
Keep the distinct runtime password and DB DSN only in node.json;
never put it in Compose environment, commands, logs, or Coolify public variables.
`sslmode=disable` in the example is only for this isolated same-host container
network, not remote PostgreSQL. Provision the existing schema and the exact QA
subject/binding/revision separately before starting the node: the executable
requires them and never creates/migrates them. Use the existing schema and scoped
provisioner, not a fresh production account or ordinary subscription rewrite.

`qa-postgres` persists admissions and cumulative QA journal across restarts.
Native active sessions are deliberately not persistent. Back up and test restore
of the isolated DB; do not use `docker compose down --volumes`. No new container
or room is created per client session; the one existing provider room is reused.

## Stop/restart and health semantics

Stop the **node first**, leaving PostgreSQL up: `docker compose stop personal-node`.
Compose forwards SIGTERM via init; main's signal context cancels native Run,
Lifecycle.Shutdown closes exact sessions and attempts bounded journal close,
and terminal reporter closes exactly only after copy/reservation settlement and
durable terminal ack. The 60-second grace permits these bounded attempts before
Docker SIGKILL; it does not guarantee an exact terminal close on crash or DB outage.
Check no active QA row/native participant remains before stopping the DB. Preserve
historical unconfirmed rows and reconcile ambiguous tails instead of rewriting
them to zero. Validate signal-stop under traffic on HomeNode; A063 manual Stop
proof is not proof of process-SIGTERM ordering.

`unless-stopped` restarts an exited process (including after daemon restart) but
does not restart merely unhealthy containers. A fresh process must perform fresh
admission; it must not revive an old lease. A crash/SIGKILL can leave unconfirmed
DB state that requires scoped operator reconciliation. Coolify autoheal must not
kill active sessions on this listener-only health signal.

Node health checks local HTTPS and malformed-body rejection (400), without PoP,
room data, traffic or secrets. It is **not** Panel, DB, provider, admission, egress,
or physical-TUN health. DB `pg_isready` likewise is not schema/binding readiness.
The protected upstream TLS certificate must include IP SAN 127.0.0.1 for this
health probe, plus the reviewed identity trusted by Caddy. Never use `curl -k`
or disable Caddy upstream TLS verification to make the probe/route pass.

## Coordinator-owned acceptance boundaries

1. Remote bootstrap: the server's loopback-default configuration now supports an
   explicit private container listener with a host-loopback-only port. The coordinator added
   only the two POST `https://rga.apollot.ru/v1/personal/...` host Caddy paths
   (no new DNS) and verified method rejection and public TLS. Caddy must
   trust the protected upstream TLS certificate/identity. Android remote mode
   uses public-CA HTTPS plus compiled exact-origin rules, while USB retains exact
   self-signed certificate pin; do not transfer that USB-only pin unchanged.
   Preserve hostname verification, installation PoP, bounded JSON, rate guards,
   no redirects and no secret logging. The host port is loopback-only; no
   public/LAN bootstrap listener is added here.
2. Egress: server Config and `newTunnelConfig` SOCKS wiring are implemented.
   The pinned native tunnel already has SOCKSProxyAddr/Port/User/Pass and its
   destination dial fails without direct fallback when proxy is specified.
   Those fields are provisioned in protected personal runtime config; no native patch is
   required solely for that wiring. Setting HTTP_PROXY/ALL_PROXY is not evidence
   of that coverage (browser credential refresh explicitly sets Proxy=nil).
   Carrier signalling/media use the HomeNode RU path directly; user destinations
   use HomeNode -> RU Relay -> foreign exit. Ordinary TCP SOCKS does not route
   UDP carrier media. Verify actual ICE/TURN peers separately against the
   no-direct-foreign-media-peer requirement, including DNS and timeout/reconnect.
3. The isolated forwarding-only gateway, networks and RO mounts are active.
   A063 Wi-Fi-to-restricted-LTE admission/traffic, lease renewal and exact manual
   Stop were physically observed. A bounded media metadata capture did not include
   known foreign backend node addresses, but does not prove complete provider-peer
   attribution. Cold start directly on restricted LTE and SIGTERM under traffic
   are concrete remaining checks; crash/DB-outage tails must remain unconfirmed
   until reconciliation, rather than being rewritten to zero.

These files do not activate ordinary subscribers, alter production Remnawave, or
change DNS/firewall. Keep operational identifiers, credentials, dumps and detailed
private acceptance evidence outside tracked source.
