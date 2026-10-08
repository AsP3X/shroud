# Server admin console — implementation plan (Grok = backend, Claude = frontend)

**Status:** proposed 2026-10-08. Nothing here is built yet; `design/admin.pen` is the only artefact.
**Scope:** a console for the operator of one Shroud server, built as its own application: its own
code, container, hostname, database role and sign-in. It is not part of `web/` and not part of
the API in `server/crates/shroud-server`.
**Split:** Grok builds the backend (`admin/api`, Rust) and the one API change this needs. Claude
builds the frontend (`admin/ui`, TypeScript). Both build against the frozen contract in §3; neither
changes it without a new entry in §3 that the other agent reads before continuing.

---

## 1. Goal

Let an operator see the state of their server and do the few things an operator legitimately
does (remove a device, delete an account, sign an account out everywhere) without the console
ever learning more about people than the API already stores. Every page shows only what the
server really knows; see the rules in §2.

## 2. Shape and rules

```
admin/
├── api/                     Grok · crate `shroud-admin`, its own Cargo workspace
│   ├── src/                 axum, sqlx, TOTP, sessions, audit log, operator-API client
│   ├── migrations/          schema `admin` (operators, operator_sessions, audit_log)
│   └── fixtures/            JSON samples of every response in §3 (the contract's test data)
├── ui/                      Claude · Vite + React 19 + TypeScript, no runtime dependency on web/
│   ├── src/
│   ├── public/
│   └── dev-server.ts        serves admin/api/fixtures as a fake backend (`npm run dev:fixtures`)
└── Dockerfile               stage 1 builds ui/, stage 2 builds api/, final: distroless, non-root
```

- **One deployable.** The Rust binary serves `/api/admin/*` and the built UI from `/` (static files,
  `index.html` fallback for client routes, long cache headers for hashed assets). No nginx in the
  image. Compose service `admin` under profile `admin`, port `127.0.0.1:${ADMIN_PORT:-8082}` in
  `local` mode, `admin.<domain>` on `proxy-network` in `npm` mode. Never behind the web client's
  nginx; `web/` never links to it.
- **Reads:** Postgres as role `shroud_admin` (column grants, R3) and the API's existing
  `GET /api/v1/health/ready`, `/metrics`, `/client-version` over `shroud-internal`.
- **Writes:** only through a new internal operator listener on the API server (`OPERATOR_PORT`,
  `OPERATOR_TOKEN`), because closing WebSockets, the `DEVICE_REMOVED` wake push, Redis fan-out and
  media unlinking live there. The console never writes the API's tables.
- **Privacy filter at the API boundary.** The backend's JSON never contains a username, a device
  name, a message, a media path, a push token or endpoint, an IP or a user agent. The frontend
  cannot show what it never receives (R2). Grok's contract tests assert this on every response.

| # | Rule | Source |
| - | ---- | ------ |
| R1 | A page shows only data the server stores or computes today. Where it records nothing (push outcomes, client versions in use, limit hits) the page says so. | `design/admin.pen` as reworked 2026-10-08 |
| R2 | Accounts by id only. No username (the server holds a hash), no device name (sealed), no message or media content, no IP, no user agent. "Last active" to the day. | `docs/anonymity-plan.md` 0.5, `docs/architecture.md` Sealed device names |
| R3 | Role `shroud_admin` can `SELECT` only the columns the backend's queries use. A test asserts it cannot read `messages.body`, `devices.sealed_name`, `users.username_hash`, media paths, `push_tokens.apns_token`, `web_push_subscriptions.endpoint`. | this plan |
| R4 | Every write is an operator-API call, idempotent, and leaves one `admin.audit_log` row. No write without a TOTP re-authentication in the last 5 minutes. | frames "Confirm it's you", "Audit log" |
| R5 | Operator passwords as argon2id, TOTP secrets encrypted with `ADMIN_SECRET_KEY`, session ids hashed at rest. Nothing else of the operator is stored. | [[no-plaintext-unless-required]] |
| R6 | No deprecated APIs; worktrees from an up-to-date `dev`; every visible change also lands in `design/admin.pen`; no AI attribution. | `docs/agent-rules/` |
| R7 | The repository is public: no secret in code, fixtures or docs. | [[repo-is-public]] |
| R8 | Counts over large tables come from `pg_class.reltuples` or a cache refreshed at most once a minute, never `COUNT(*)` per page view. | this plan |
| R9 | The contract (§3) is frozen. A change is a new numbered line in §3.9 with the date, the field, and which agent asked; the other agent reads §3.9 before its next task. Fixtures change in the same commit as the contract. | `docs/android-handover.md` §2 |

### Decisions to confirm before phase 1

| # | Decision | Recommendation | Alternative |
| - | -------- | -------------- | ----------- |
| D1 | Rendering | A separate TypeScript SPA (Vite + React 19, like `web/`), served by the Rust binary. The split makes this the natural cut: Grok never touches a template, Claude never touches Rust. | Server-rendered askama (rejected for the split: UI and backend would be one crate) |
| D2 | The console's own tables | Schema `admin` in the same Postgres database, owned by `shroud_admin` | A second database |
| D3 | Second factor | TOTP (RFC 6238) with 8 recovery codes, as the Sign in frame shows | WebAuthn passkeys |
| D4 | Writes | Internal operator listener on the API server | Direct SQL from the console (rejected: duplicates realtime and push code) |
| D5 | First operator | `shroud-admin bootstrap` run once in the container prints a one-time link | A bootstrap password in `.env` (rejected) |
| D6 | Who writes the fixtures | Grok, in `admin/api/fixtures/`, before any UI work; they are the contract's examples and the backend's own test data | Claude writes them from the contract (risk: two readings of one contract) |

---

## 3. Frozen contract

All routes are under `/api/admin/`. JSON everywhere, `Cache-Control: no-store`, UTC timestamps in
RFC 3339, ids as lowercase UUID strings, day-level dates as `YYYY-MM-DD`. Errors are
`{ "code": "<UPPER_SNAKE>", "message": "<English sentence>" }` with the status codes below. A
`401 UNAUTHENTICATED` on any route sends the UI to sign-in; `403 REAUTH_REQUIRED` opens the
"Confirm it's you" dialog; `403 FORBIDDEN` is a role problem; `429 RATE_LIMITED` carries
`Retry-After`; `502 UPSTREAM` means the API or Postgres did not answer, with
`"upstream": "postgres" | "api"` so the UI can render the "Database down" or "Couldn't load"
frames.

### 3.1 Session (C1)

| Route | Body → Response | Notes |
| ----- | --------------- | ----- |
| `POST /session` | `{ operator, password, totp }` → `204` + cookie `__Host-admin` | `401 BAD_CREDENTIALS` for any wrong part, same timing; `429` after 10 tries per 15 min per operator |
| `POST /session/recovery` | `{ operator, password, recovery_code }` → `204` | burns the code; `audit_log` row |
| `DELETE /session` | → `204` | |
| `GET /session` | → `{ operator: { id, name, role: "read" \| "write" }, expires_at, reauth_until }` | `reauth_until` null when a fresh TOTP is needed for writes |
| `POST /session/reauth` | `{ totp }` → `{ reauth_until }` | extends by 5 minutes |
| `POST /setup/{token}` | `{ password, totp }` → `{ recovery_codes: [8 strings] }` | one-time link from bootstrap or operator invite; `410 LINK_USED` |
| `GET /setup/{token}` | → `{ operator_name, totp_uri, qr_svg }` | the enrolment page's data |

Cookie: `Secure; HttpOnly; SameSite=Strict; Path=/`, 12 hours, idle timeout 30 minutes. CSRF:
every non-GET carries `X-Admin-CSRF` equal to the `admin_csrf` cookie the backend sets at sign-in.

### 3.2 Overview (C2) — `GET /overview`

```json
{ "server": { "version": "0.1.0", "started_at": "…", "checked_at": "…" },
  "stats": { "accounts": 1284, "accounts_7d": 12, "accounts_deleted": 9,
             "devices_active_30d": 2071, "ws_connections": 347, "messages_sent_total": 182406 },
  "ready": { "status": "ok" | "not_ready", "database": "ok" | "error",
             "redis": "ok" | "error" | "skipped", "media": "ok" | "error" },
  "configured": { "apns": { "environment": "production" | "sandbox", "topic": "…" } | null,
                  "web_push": { "subscriptions": 318 } | null,
                  "unifiedpush": { "allowed_hosts": ["ntfy.sh"], "public_hosts": false } | null,
                  "turn": { "urls": ["turn:…"], "credential_ttl_secs": 43200 } | null,
                  "link_relay": { "max_per_account": 6 } },
  "metrics": { "http_requests_total": 0, "http_errors_total": 0, "media_puts_total": 0,
               "media_gets_total": 0, "media_store_errors_total": 0, "media_legacy_reads_total": 0,
               "media_migrated_total": 0, "calls_created_total": 0 },
  "attention": [ { "kind": "no_min_version" | "legacy_media" | "not_ready", "count": 3 } ] }
```
`postgres` details (migrations applied, pool) are **not** in the contract: the console has no view
of the API's pool. The Overview frame drops "pool 4 of 10 in use" (design task C0.3).

### 3.3 Users (C3)

| Route | Response |
| ----- | -------- |
| `GET /users?q=<id prefix>&cursor=&limit=50` | `{ items: [ { id, created_on, devices, last_active_on \| null, push: "apns" \| "web" \| "unifiedpush" \| "none" \| "mixed", status: "active" \| "deleted" } ], next_cursor }` |
| `GET /users/{id}` | `{ id, created_on, status, last_active_on, counts: { contacts, blocks, conversations, media_objects, media_bytes }, devices: [ { id, platform: "ios" \| "web" \| "android" \| "unknown", added_on, last_seen_on, revoked: bool, push: "apns" \| "apns+voip" \| "web" \| "unifiedpush" \| "none", session: "live" \| "none" } ], pin_guard: bool }` |

`platform` comes only from the push registration kind; the devices table stores no platform.
`q` must be 4–36 hex/dash characters; anything else is `400 VALIDATION_ERROR`.

### 3.4 Read-only pages (C4)

| Route | Response, in brief |
| ----- | ------------------ |
| `GET /storage` | backend `"nebular" \| "local"`, bucket or data dir, `objects`, `bytes`, `unlinked_objects`, `legacy_reads_total`, `migrated_total`, `max_object_bytes` |
| `GET /client-versions` | per platform `{ latest, minimum, update_url }` (null when unset), `web: { build_file, deployed_build, fixed_build }`, `server_version`, and `told: { ios: [ { versions: "<1.1", status } ], … }` computed by calling `/client-version` |
| `GET /rate-limits` | `[ { what, counted: "ip" \| "account" \| "username" \| "device", limit, window_secs, note } ]` generated from the API crate's `budgets` module at build time |
| `GET /retention` | `automatic: [ { record, after_secs, every_secs, stays } ]`, `kept: [ { record, goes_when } ]`, `jobs: [ { name, every_secs \| null, detail } ]` — constants from the API crate |
| `GET /push` | `{ apns_tokens, apns_voip_tokens, web_push_subscriptions, unifiedpush_subscriptions, notifications_off, channels: [ { name, registered, sent_to, dropped_when } ] }` |
| `GET /calls` | `{ created_total, ice_servers: [ { urls } ] (no credentials), turn, gc: { ringing_timeout_secs: 60, participant_timeout_secs: 45, sweep_secs: 10 } }` |
| `GET /privacy-checks` | `[ { item, state: "sealed" \| "hashed" \| "not_stored" \| "stored", detail } ]` — the frame's rows, each backed by a column check or a constant |
| `GET /configuration` | `[ { group, variables: [ { name, value \| null, secret: bool, set: bool } ] } ]` — values from the console's own environment; secrets only as `set` |
| `GET /audit-log?cursor=&limit=100` | `{ items: [ { at, operator, action, target_kind, target_id, outcome: "ok" \| "refused" \| "failed", detail } ], next_cursor }` |

### 3.5 Writes (C5) — all need role `write` and a fresh re-auth

| Route | Effect | Audit action |
| ----- | ------ | ------------ |
| `POST /users/{id}/devices/{device_id}/remove` | operator API `POST /operator/devices/{id}/remove` | `device.remove` |
| `POST /users/{id}/sign-out-all` | `POST /operator/users/{id}/sign-out-all` | `user.sign_out_all` |
| `POST /users/{id}/delete` with `{ confirm: "<id>" }` | `POST /operator/users/{id}/delete` | `user.delete` |

Responses are `204`, or `502 UPSTREAM` when the operator API refused or did not answer (the audit
row then says `failed`). `409 ALREADY_DONE` for a device already revoked or an account already a
placeholder.

### 3.6 Operators (C6) — role `write`

`GET /operators` → `[ { id, name, role, enabled, last_sign_in_at, totp_enrolled } ]`;
`POST /operators` `{ name, role }` → `{ setup_url }`; `PATCH /operators/{id}` `{ role?, enabled? }`;
`POST /operators/{id}/reset-totp` → `{ setup_url }`. Disabling an operator ends their sessions.

### 3.7 Internal operator API on the API server (C7, Grok, in `shroud-server`)

Second axum router on `OPERATOR_PORT` (default 8090), started only when `OPERATOR_TOKEN` is set,
bound inside the container, never published by compose. Every request needs
`Authorization: Bearer <OPERATOR_TOKEN>` (constant-time compare) or gets `403`. Routes above in
§3.5; each reuses the inner function of the existing user-facing handler so the side effects are
identical. `/health*`, `/metrics` and every public route are **not** on this port, and
`/operator/*` is `404` on the public port.

### 3.8 Fixtures (C8)

`admin/api/fixtures/<route>.json` for every response above, plus `<route>.<state>.json` for the
states the frames show: `overview.database-down.json`, `users.empty.json`, `audit-log.empty.json`,
`storage.new-server.json`, `client-versions.nothing-set.json`. Grok's integration tests compare
real responses against the fixtures' JSON Schema (`admin/api/fixtures/schema/*.json`); Claude's
dev server serves the same files. Both agents treat a mismatch as a contract bug, not a UI bug.

### 3.9 Contract changes

| # | Date | Change | Asked by |
| - | ---- | ------ | -------- |
| 1 | 2026-10-08 | §3.3 `GET /users/{id}`: `counts.contacts`, `counts.blocks` and `counts.conversations` are `integer \| null`; `null` means the console does not show them. The User detail frame says "Not visible" for these three, and a contact or conversation count is a view of the social graph the console has no reason to hold. The backend sends `null` and never queries them; the UI renders "Not visible". | Grok (G0.3), from the frame |
| 2 | 2026-10-08 | §3.2 `attention[].count` is optional; `no_min_version` and `not_ready` carry none. | Grok (G0.3) |
| 3 | 2026-10-08 | §3.3 `GET /users` takes an optional `status=active \| deleted` filter (the Users frame's All / Active / Deleted segments, filtered on the server so paging stays right), and its response carries `totals: { accounts, active, deleted }` for the header and the segment labels. `users.json`, `users.empty.json` and `schema/users.schema.json` updated in the same commit. | Claude (C1.3) |
| 4 | 2026-10-08 | §3.4 `GET /audit-log` takes an optional `target=<id>` that returns only entries whose `target_id` is that account or one of its devices (User detail's "Admin actions on this account"). No fixture change: the dev server ignores the parameter and the UI filters the page it gets. | Claude (C1.3) |
| 5 | 2026-10-08 | Design: the Users frames lose "Export CSV" (no route exports anything) and gain a "Mixed" legend key; User detail frames lose "Suspend" (out of scope, §8) and the "Keys and storage" rows the contract has no field for (one-time prekeys, unattached media, calls in 30 days), keeping media stored and adding the PIN-guard row. | Claude (C1.3) |

---

## 4. Grok backlog (backend: `admin/api`, `shroud-server`, compose)

### Phase G0 — Skeleton, contract fixtures, deployment
- [x] **G0.1 Crate and image.** `admin/api` as `shroud-admin` in the workspace; `/healthz`;
  serves `admin/ui/dist` when present (static, `index.html` fallback, hashed assets cached a year,
  `index.html` `no-store`); `admin/Dockerfile` two-stage (Node 22 for `ui`, Rust for `api`,
  distroless final, non-root, `read_only: true`).
  - Done 2026-10-08: `docker compose --profile admin` serves `/healthz` (`ok`) and the built
    `index.html` on `127.0.0.1:8082`. The symlink that first put the crate under `server/crates/`
    is removed in G0.5; `admin/api` is its own workspace.
- [x] **G0.5 Make `admin/api` its own Cargo workspace; remove the symlink.** G0.1 wired the
  crate into `server/Cargo.toml` through `server/crates/shroud-admin → ../../admin/api`, plus a
  copy of its manifest in `server/docker/shroud-admin-member.toml` for the API image. That couples
  three things that must now be kept in step by hand: the manifest copy (a dependency added to
  `admin/api/Cargo.toml` and not to the copy breaks the API image's `cargo build` against the
  shared `Cargo.lock`), the API's `Dockerfile` (it now builds a dummy `shroud-admin` on every
  image), and every checkout on Windows (`deploy.ps1` and `scripts/setup.ps1` exist; Git there
  checks a symlink out as a text file unless `core.symlinks` is on, and the workspace fails to
  load). It also puts the console's build in the API's critical path, which §2 and §7 set out to
  avoid.
  - Change: `admin/api/Cargo.toml` gets its own `[workspace]` table (empty) and its own
    `Cargo.lock` and `rust-toolchain.toml` (copy the server's); remove `crates/shroud-admin` from
    `server/Cargo.toml`, the symlink, `server/docker/shroud-admin-member.toml`, the admin lines
    in `server/Dockerfile` and the admin entries in `server/Cargo.lock`. `admin/Dockerfile` copies
    `admin/api` as a plain directory. For G1.4 the constants come in through
    `shroud-server = { path = "../../server/crates/shroud-server", default-features = false }`,
    a path dependency a standalone crate may have; if that pulls too much, expose the constants
    from a tiny `shroud-constants` crate under `server/crates/` that both depend on.
  - Done 2026-10-08: `cargo build` succeeds in `admin/api` and in `server/` separately. `git
    ls-files -s server/crates` has no symlink. The API image builds from `./server` with no admin
    file in its Dockerfile or context, and the admin image builds from the repository root.
- [ ] **G0.2 Compose, env, deploy.** Service `admin` with `profiles: ["admin"]`; host port in
  `docker-compose.host-ports.yml`, `proxy-network` in `docker-compose.npm.yml`; `ADMIN_PORT`,
  `ADMIN_PUBLIC_URL`, `ADMIN_DATABASE_URL`, `ADMIN_SECRET_KEY`, `OPERATOR_PORT`, `OPERATOR_TOKEN`
  in `.env.example` (placeholders) and `docker-compose.yml`; the API's non-secret variables passed
  to `admin` too, secrets as `<NAME>_SET: ${NAME:+true}`; `scripts/compose-env.sh` knows the
  `admin` profile like `calls`; `./deploy.sh` asks whether to enable it and prints the URL.
  - Done when: a fresh `./deploy.sh` with the profile on brings the console up in both proxy
    modes; with the profile off nothing of it runs; `docker inspect` of `admin` shows no secret.
- [ ] **G0.3 Fixtures and schemas (C8).** Every file in §3.8, hand-written from the frames'
  numbers, plus JSON Schemas. This unblocks Claude's C1.
  - Done when: `npm run dev:fixtures` in `admin/ui` (Claude's C0.2) serves them unchanged.
- [ ] **G0.4 Role, schema, grants (R3).** `001_admin.sql`: schema `admin`, tables `operators`
  (`id, name, role, password_hash, totp_secret_enc, enabled, created_at, last_sign_in_at`),
  `operator_sessions` (`id_hash, operator_id, created_at, last_used_at, reauth_until, csrf`),
  `recovery_codes` (`operator_id, code_hash, used_at`), `audit_log`, `setup_links`
  (`token_hash, operator_id, expires_at, used_at`). Role `shroud_admin` created by the Postgres
  init script from `ADMIN_DATABASE_URL`; column grants for phases G1–G2; `sqlx::migrate!` under an
  advisory lock.
  - Done when: the grant test passes against a throwaway `postgres:16`, including a failing
    `SELECT body FROM messages`.

### Phase G1 — Session and read routes (no API change)
- [ ] **G1.1 Bootstrap and session (C1).** `shroud-admin bootstrap [--recover]`, setup links,
  argon2id, TOTP (RFC 6238, 30 s, ±1 step), recovery codes, cookie session, CSRF, 10-per-15-min
  limit, `audit_log` rows for sign-in, failed sign-in, sign-out, setup.
  - Done when: fixtures' error cases reproduce; a `psql` dump shows no secret in the clear.
- [ ] **G1.2 Overview (C2).** Postgres counts (R8), `/health/ready`, `/metrics` parsed by name,
  `configured` from the console's environment, `attention` rules.
  - Done when: stopping Postgres yields `502 UPSTREAM upstream=postgres`, and
    `/health/ready` not ok yields `ready.status = not_ready`.
- [ ] **G1.3 Users and user detail (C3).** Cursor pagination on `(created_at, id)`, id-prefix
  search, platform and push from registrations, day-level dates, placeholder detection.
  - Done when: responses validate against the schemas, and a response for a seeded account with a
    sealed device name contains no byte of that name (test).
- [ ] **G1.4 Read-only pages (C4).** `storage`, `client-versions`, `rate-limits`, `retention`,
  `push`, `calls`, `privacy-checks`, `configuration`, `audit-log`. Rate limits and retention come
  from the API crate's constants through a tiny `shroud-server` dependency on the `budgets` module
  and the retention constants (`budgets`, `REVOKED_SESSION_RETENTION_DAYS`, `ORPHAN_TTL_MINUTES`,
  `RINGING_TIMEOUT_SECS` and `PARTICIPANT_TIMEOUT_SECS` are `pub` already; the three interval
  constants `SESSION_PURGE_INTERVAL_SECS`, `ORPHAN_GC_INTERVAL_SECS` and `CALL_GC_INTERVAL_SECS`
  become `pub` in the same change), so a constant change rebuilds the table.
  - Done when: each response matches a `psql` query or the constant in the source.

### Phase G2 — Writes
- [ ] **G2.1 [api] Operator listener (C7)** in `shroud-server`: config, second router, token check,
  three routes reusing the inner functions of device removal, sign-out-all and account deletion.
  Document in `docs/server-plan.md`; `.env.example` gets `OPERATOR_PORT`/`OPERATOR_TOKEN`.
  - Done when: public port `404`s `/operator/*`; wrong token `403`; existing server tests pass; a
    removed device's app wipes on its next connection (simulator check).
- [ ] **G2.2 Console writes (C5).** Re-auth check (`reauth_until`), role check, operator-API
  client with a 10 s timeout, `409 ALREADY_DONE`, audit rows for ok, refused and failed.
- [ ] **G2.3 Operators (C6).** Invite links, role changes, disable ends sessions, TOTP reset.

### Phase G3 — Hardening and tests
- [ ] **G3.1** CSP `default-src 'self'; img-src 'self' data:` (the enrolment QR is inline SVG),
  `Referrer-Policy: no-referrer`, bodies capped at 16 KiB, request ids in logs, no IP in logs.
- [ ] **G3.2** Tests: grants (R3), session flow, re-auth timing, every route against a seeded
  throwaway database with schema validation, operator-API token checks, `cargo clippy -D warnings`.
  They run with `cargo test` in the workspace.
- [ ] **G3.3** `docs/admin.md` operations note: backup (schema `admin`), lost `ADMIN_SECRET_KEY`
  (re-enrol TOTP), `bootstrap --recover`.

## 5. Claude backlog (frontend: `admin/ui`, `design/admin.pen`)

### Phase C0 — Project, tokens, fixtures server
- [x] **C0.1 Project.** `admin/ui` with Vite + React 19 + TypeScript (strict, like `web/`, which
  has no ESLint either), no dependency on `web/`; `npm run build` to `dist/`; routes for every frame.
  - Done 2026-10-08: `npm run build` passes `tsc -b`; every frame has a route (pages are stubs
    until C1/C2).
- [x] **C0.2 Fixture dev server.** `dev-server.ts` serving `admin/api/fixtures` under
  `/api/admin/*`, with `?state=` to pick the `<route>.<state>.json` variants, a fake session, and
  latency/failure switches for the loading and error frames. Unblocked by G0.3.
  - Done 2026-10-08: `npm run dev:fixtures`; switches on `/__fixtures`; `?state=error:<name>`
    answers with that error file and status; writes need the fake re-auth (`admin/ui/README.md`).
- [x] **C0.3 Tokens and shell.** The `.pen` variables as CSS custom properties (both themes,
  `prefers-color-scheme` with a manual override), Inter and JetBrains Mono self-hosted, the Sidebar,
  NavItem, StatTile, StatusPill, Button, SearchField and table components; the phone top bar.
  Design task: drop "pool 4 of 10 in use" from the three Overview frames (§3.2).
  - Done 2026-10-08: `/gallery` renders each component beside its frame's values; the phone layout
    (≤ 600 px) shows the top bar and a drawer with the sidebar.

### Phase C1 — Sign-in and read-only pages (against fixtures)
- [x] **C1.1 Sign in** [Sign in, Sign in · Error], setup/enrolment page with the QR, recovery
  codes shown once with a "I saved them" step.
  - Done 2026-10-08 against the fixtures: `/sign-in` (with a recovery-code switch) and
    `/setup/:token` (password, QR and key, code, recovery codes once; used and invalid links).
    New frames "Set up sign-in", "Save your recovery codes" and "Sign in · Recovery code" in
    `design/admin.pen`.
- [x] **C1.2 Overview** [Overview, · Database down, · Light]: all three states from fixtures;
  the attention items link to their pages.
  - Done 2026-10-08: `/` renders the fixture, `?state=database-down` (Not ready, PostgreSQL
    Down) and `?state=error:upstream-postgres` (the console's own database unreachable, with
    Try again). Service details use only contract fields: no migration count, pool or open-pipe
    number.
- [ ] **C1.3 Users** [Users, · Loading, · No results, · Couldn't load] and **User detail**
  [User detail] read-only; actions rendered disabled with the frames' footnote.
- [ ] **C1.4 Storage, Client versions (both states), Rate limits, Data retention, Push delivery,
  Calls, Privacy checks, Configuration, Audit log (both states)**: one route each, data from C4.
- [ ] **C1.5 Phone layout** [Phone · Overview, Phone · Users] at ≤ 600 px.
  - Done when: every frame in `design/admin.pen` has a route that matches it in both themes, and
    `npm run build` passes `tsc` with no `any`.

### Phase C2 — Writes and operators
- [ ] **C2.1 Re-auth dialog** [User detail · Confirm it's you] driven by `403 REAUTH_REQUIRED`;
  retries the write after a successful `POST /session/reauth`.
- [ ] **C2.2 Confirmations and outcomes** [· Remove device, · Sign out all devices, · Delete
  account, · Device removed]; `409 ALREADY_DONE` and `502 UPSTREAM` shown with the frames' copy.
- [ ] **C2.3 Operators** [Operators]: list, invite (shows the setup link once), role, disable,
  TOTP reset; `read` operators see the page without buttons.
- [ ] **C2.4 Audit log** complete, with the frames' action wording.

### Phase C3 — Integration and polish
- [ ] **C3.1** Run against the real backend (`docker compose --profile admin up`), fix every
  difference as a contract bug (§3.9) rather than a UI special case.
- [ ] **C3.2** Keyboard and screen-reader pass; focus order in dialogs; no colour-only state.
- [ ] **C3.3** `design/admin.pen` brought level with anything that had to change (R6), and the
  owner saves it (Pen edits live only in the running app).

## 6. Order and integration points

| Step | Needs | Unblocks |
| ---- | ----- | -------- |
| G0.3 fixtures | the contract (§3) | C0.2, all of C1 |
| C0.1–C0.3 | nothing | C1 |
| G0.1–G0.2, G0.4 | nothing | G1 |
| G1 complete | G0 | C3.1 for read pages |
| G2.1 operator listener | owner's go on D4 | G2.2 |
| G2.2–G2.3 | G2.1 | C3.1 for writes |
| C2 | fixtures for C5/C6 (part of G0.3) | C3.1 |

Claude's C1 and Grok's G1 run in parallel from day one. The first integration (C3.1 on read pages)
happens when G1.4 is done; the second when G2.3 is done.

## 7. Do-not-touch (either agent; changes need a new handover)

- `web/`, `ios/`, `android/`: the console shares nothing with the clients.
- `server/crates/shroud-server` outside G2.1 and the `pub` visibility of `rate_limit::budgets` and
  the retention constants. No new public route. No change to existing handlers beyond extracting
  an inner function.
- Postgres tables outside schema `admin`. The console's role never gets `INSERT`, `UPDATE` or
  `DELETE` on them.
- The contract in §3, except through §3.9.

## 8. Out of scope, and why

| Idea in the design | Why not now |
| ------------------ | ----------- |
| Suspending an account [User detail · Suspend account, · Suspended] | No suspended state in the API; product work first. |
| Sign-up modes and invite codes [Sign-ups] | Registration is open by design; a closed mode is a server feature to design first. |
| Push statistics, delivery rates, failure reasons | The API logs outcomes and counts nothing; counting is a server change and a privacy question. |
| Editable retention, "Run now", "Run cleanup", timestamp precision | The jobs are constants; nothing rounds receipts. |

## 9. Handover prompt for Grok

> You are building the backend of the Shroud server admin console. Read `docs/admin-plan.md`
> fully, then `docs/agent-rules/*.md`, `docs/server-plan.md` (Client versions, Rate limits,
> Configuration), `server/crates/shroud-server/src/{rate_limit.rs,client_version.rs,metrics.rs}`,
> `routes/{health.rs,devices.rs,auth.rs}`, `docker-compose*.yml`, `scripts/compose-env.sh`,
> `.env.example`. Work only in `admin/api`, `admin/Dockerfile`, compose files, `scripts/`,
> `.env.example`, and in `shroud-server` only for task G2.1. Start with G0.3 (fixtures) because
> Claude is blocked on it, then G0.1, G0.2, G0.4, then G1 in order. Keep the contract in §3
> frozen; a needed change goes into §3.9 first. Every response must pass the privacy filter (no
> username, device name, message, media path, token, endpoint, IP, user agent). Work in a worktree
> based on an up-to-date `dev`; commit per task with a plain message and no AI attribution.
> Report done tasks by their ids with the test that proves each.

## 10. Resume prompt for Claude

> You are building the frontend of the Shroud server admin console in `admin/ui`. Read
> `docs/admin-plan.md` §2, §3, §5, §6, §7, then open `design/admin.pen` in Pen and read each frame
> through the Pencil tools as you build its route. Build against `admin/api/fixtures` through
> `dev-server.ts`; never special-case a backend difference in the UI, file it in §3.9. Every visible
> change also lands in `design/admin.pen`, and the owner saves it. Start with C0.1–C0.3, then C1.
> No dependency on `web/`.

## 11. Open questions for the owner

1. D1–D6 above (D1 is now a consequence of the split; confirm anyway).
2. Hostname: `admin.<domain>` as a second NPM host, or SSH tunnel only.
3. Whether the console lives in this public repository (this plan assumes yes, R7).
4. Whether Grok has usage again; the last note (2026-10-05) said it had none, in which case Claude
   takes §4 as well and the split stays as a code boundary.
