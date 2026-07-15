# Shroud server plan

Source of truth for the Rust API (`server/`): product decisions, behavior, milestones, and locked HTTP contracts.

| | |
| --- | --- |
| **Status** | Auth API + Postgres schema locked; ready to implement **milestone 1** |
| **Last updated** | 2026-07-15 |
| **Related** | [architecture.md](./architecture.md) · [thought-collection.md](../thought-collection.md) · [README.md](../README.md) |

---

## Contents

1. [Goals and non-goals](#goals)
2. [Decision log](#decision-log)
3. [Security invariants](#security-invariants-server)
4. [Runtime topology](#runtime-topology)
5. [Domain model](#domain-model) (includes locked Auth SQL)
6. [Feature behavior](#feature-behavior)
7. [Implementation milestones](#implementation-milestones)
8. [API surface](#api-surface)
9. [Configuration](#configuration)
10. [Known risks](#known-product-risks)
11. [Still open](#still-open)

---

## Goals

- E2E messenger: server stores and relays **ciphertext only**.
- Stack: **Axum** + **PostgreSQL**, base path `/api/v1`, native iOS client.
- Scale API horizontally from day one (stateless handlers + shared **Redis**).
- Privacy-first identity: **no phone or email**.
- Session UX like Signal: stay logged in on a device until **explicit revoke**.

## Non-goals (v1)

- Group chats / MLS
- Server-side voice transcription / transcript sync APIs
- Call media (WebRTC media plane, SFU); custom TURN stack (use **coturn** later)
- Password recovery via email/SMS
- Username rename after registration
- Open username directory or prefix search

---

## Decision log

### Identity and auth

| Area | Decision |
| --- | --- |
| Account | Username + password only |
| Username | `[a-z0-9_]`, length 3–32, **case-insensitive** unique (store case-folded), **immutable** |
| Reserved names | Denylist + prefix rules — see [Reserved usernames](#reserved-usernames) |
| Password hash | **argon2id**, OWASP 2023 baseline (e.g. m≈19 MiB, t=2, p=1), pinned in code |
| Password policy | Min 8 chars; reject **embedded** common-password denylist |
| Password recovery | None (phrase does not re-authenticate to the server) |
| Password change | Revoke **other** devices’ sessions; keep current session |
| Sessions | Opaque token; store **hash** only; `Authorization: Bearer`; bound to `device_id` |
| Session lifetime | **No time expiry**; end on logout, device delete, password-change (others), account delete |
| Devices | Max **5** per account; optional `device_name` |
| Device limit | New device when full → `DEVICE_LIMIT` (no auto-evict) |
| Returning device | Optional `device_id` on login: reuse if owned by user; else new device (cap applies) |

### Crypto and keys

| Area | Decision |
| --- | --- |
| Protocol | Signal-style **X3DH + Double Ratchet**; server stores public material only |
| Key upload | Separate authenticated call after register/login |
| Keys gate | Session OK without keys; messaging / recipient key fetch needs bundle (`KEYS_REQUIRED`) |
| OTPKs | Upload **100**; client refills when remaining under **25** |
| History crypto (client) | Phrase → account backup key wraps history; server sees opaque blobs only |

### Messaging and social

| Area | Decision |
| --- | --- |
| Topology | **1:1 only** |
| Conversations | Explicit `conversations` row; messages reference `conversation_id` |
| Retention | Indefinite until user delete |
| History recovery | Login + encryption phrase on client → download ciphertext |
| Discovery | Share **`users.id` (UUID)** + deep link; username for login/display |
| First contact | **Contact request** required before full messaging |
| Block / delete | Block list; delete-for-me; delete-for-everyone **anytime** |
| Receipts | Delivery + optional read |
| Multi-device send | Server fan-out to sender’s other devices |
| History page | Keyset cursor `(created_at, id)` |
| Envelope metadata | Minimal relay fields only (no plaintext/previews) |
| Presence | Typing + online/last-seen; **accepted contacts only** |

### Media and real-time

| Area | Decision |
| --- | --- |
| Types (v1 messaging) | Text + encrypted voice/images |
| Object store | [Nebular OS](https://github.com/AsP3X/nebular-os) |
| Transfer | Presigned URLs via Shroud API (no large-body proxy) |
| Layout | Bucket `shroud-media`, key `{uploader_user_id}/{object_id}` |
| Presign TTL | **15 minutes** |
| Max object size | **25 MiB** |
| Transcripts | No transcript APIs in v1 |
| Real-time | WebSocket + Redis pub/sub |
| WS auth | First message with token within **10 seconds** |

### Ops

| Area | Decision |
| --- | --- |
| Rate limits | Redis; budgets in [Rate limits](#rate-limits-starting-budgets) |
| Account delete | Hard delete + cascade |
| Push | Data APNs (opaque ids) after online WS, same overall v1 |
| Calls | After messaging + data push; signaling + coturn |
| Compose | Postgres + Redis + Nebular + API; + coturn for calls |

### Reserved usernames

Match after case-folding.

**Denylist:**  
`admin`, `administrator`, `support`, `help`, `shroud`, `system`, `root`, `security`, `null`, `undefined`, `api`, `www`, `mail`, `email`, `mod`, `moderator`, `staff`, `official`, `everyone`, `all`, `me`, `self`, `owner`.

**Prefix blocks:**  
`shroud_`, `system_`, `admin_`, `support_`.

Keep as one shared constant in code; reject with `USERNAME_RESERVED`.

---

## Security invariants (server)

1. Never accept, store, or log message **plaintext**, **private keys**, or the **encryption phrase**.
2. Session tokens: persist **hash only**; return raw token once at register/login.
3. Pre-key APIs: **public** material only; one-time pre-keys consumed atomically.
4. Nebular objects are **ciphertext**; authorize with short-lived **presigned URLs**.
5. APNs: **opaque ids** only (message / conversation / call).
6. Enforce **contacts** and **blocks** before full message envelopes.
7. Rate-limit auth, lookups, contact requests, media presign, WS connects.
8. Presence visible only to **accepted contacts**.

---

## Runtime topology

```text
 clients (iOS) ──HTTPS + WebSocket──► shroud-server (N replicas)
                                         │
                    ┌────────────────────┼────────────────────┐
                    ▼                    ▼                    ▼
               Postgres              Redis               Nebular OS
               (durable)          (fan-out,            (ciphertext
                                   limits,               blobs)
                                   presence)
```

Calls phase adds **coturn** (TURN). Media bytes do not transit the Rust API.

| Service | Role | Horizontal scale |
| --- | --- | --- |
| `shroud-server` | HTTP + WS, auth, relay, later signaling | Yes |
| Postgres | Durable app state | Primary only (v1) |
| Redis | Pub/sub, rate limits, presence | Yes (later) |
| Nebular OS | Ciphertext objects | Per Nebular model |
| coturn | TURN when P2P fails | Yes |

**Scaling rules**

- Stateless HTTP; WS fan-out via Redis (`user_id` / `device_id` channels).
- Run migrations once (single job / `RUN_MIGRATIONS`), not on every replica concurrently.
- Configurable `DATABASE_POOL_MAX`.
- Idempotent message inserts via `client_message_id`.
- Liveness vs readiness (DB + Redis); graceful SIGTERM drain.

---

## Domain model

### Milestone 1 — Auth schema (locked)

Forward-only sqlx migrations under `server/migrations/postgres/`. Do not edit applied migrations; add `002_…` (or replace `001` only if no environment has applied it yet).

#### `users`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `UUID` PK | `gen_random_uuid()`; shareable public id |
| `username` | `TEXT` NOT NULL UNIQUE | Case-folded form only |
| `password_hash` | `TEXT` NOT NULL | argon2id PHC string |
| `created_at` | `TIMESTAMPTZ` NOT NULL | `now()` |

Indexes: unique on `username` (constraint). Optional non-unique not required.

#### `devices`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `UUID` PK | Client may send this back on login |
| `user_id` | `UUID` NOT NULL FK → `users(id)` **ON DELETE CASCADE** | |
| `name` | `TEXT` NULL | Optional display name |
| `created_at` | `TIMESTAMPTZ` NOT NULL | `now()` |
| `last_seen_at` | `TIMESTAMPTZ` NULL | Updated on authenticated activity |

Indexes: `(user_id)`. Max 5 devices enforced in application code (not a DB CHECK).

#### `sessions`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `UUID` PK | |
| `device_id` | `UUID` NOT NULL FK → `devices(id)` **ON DELETE CASCADE** | |
| `token_hash` | `BYTEA` NOT NULL UNIQUE | SHA-256 of opaque token (raw 32 bytes) |
| `created_at` | `TIMESTAMPTZ` NOT NULL | `now()` |
| `revoked_at` | `TIMESTAMPTZ` NULL | Set on logout / device delete / superseded login / password-change (others) |
| `last_used_at` | `TIMESTAMPTZ` NULL | Optional touch on auth |

Indexes:

- Unique on `token_hash`
- `(device_id)`
- **Partial unique:** `UNIQUE (device_id) WHERE revoked_at IS NULL` — one live session per device

**Retention:** keep revoked rows; purge job deletes where `revoked_at < now() - interval '30 days'`.

Auth lookup: `SELECT … FROM sessions JOIN devices … JOIN users … WHERE token_hash = $1 AND revoked_at IS NULL`.

### Later entities (sketch)

| Entity | Role |
| --- | --- |
| `identity_keys` / `signed_prekeys` / `onetime_prekeys` | Public keys per device; OTPK consume-on-fetch |
| `contact_requests` / `contacts` / `blocks` | Social graph |
| `conversations` | One row per 1:1 pair |
| `messages` | Envelope metadata + ciphertext |
| `message_receipts` / `message_deletions` | Delivered/read; for_me / for_everyone |
| `media_objects` | Nebular refs |
| `push_tokens` | APNs per device |

Redis: pub/sub, `rl:{scope}:{id}`, presence/typing keys.

---

## Feature behavior

### Auth and devices

- **Register** — validate username/password → user + first device + session token.
- **Login** — verify password; reuse `device_id` if owned, else new device if under cap; issue new session and **revoke any prior sessions on that same device** (one live token per device).
- **Logout** — revoke **current session only**; device row remains (still counts toward cap until deleted).
- **Delete device** — revoke all sessions for that device; free a slot.
- **Password change** — validate current; set new hash; revoke all sessions except current.
- Client: store `token` + `device.id` in Keychain.

### Contacts

- Share `users.id`; optional rate-limited exact username lookup.
- Messaging requires accepted contact; requests accept/reject; blocks stop spam.

### Messages

- Durable ciphertext; fan-out to recipient devices + sender’s other devices.
- Online: WS; offline: paginated catch-up + later APNs.
- Minimal metadata; delete-for-me / delete-for-everyone (unlimited window).

### Media

- Client encrypts → presign (≤25 MiB, 15m TTL) → PUT Nebular → envelope refs key.
- Path: `shroud-media` / `{uploader_user_id}/{object_id}`.

### Rate limits (starting budgets)

| Scope | Budget |
| --- | --- |
| Auth (register/login) | 10/min per IP; 5/min per username |
| User lookup | 30/min per IP |
| Contact requests | 10/hour per user |
| Media presign | 60/min per user |
| WebSocket connect | 30/min per IP |

Env-tunable later. Key pattern: `rl:{scope}:{id}`.

### Presence and push

- Typing ephemeral; online/last-seen via Redis TTL; contacts only.
- WS must auth within 10s.
- Data APNs with opaque ids when offline.

### Calls (later)

- Signaling in API; coturn; VoIP push.

---

## Implementation milestones

| # | Milestone | Deliverables |
| --- | --- | --- |
| **1** | **Auth** | Routes in [API surface](#milestone-1--auth-locked); username rules + reserved list; argon2id; password policy; devices (max 5, names, reuse); opaque sessions; password change; middleware; tests. Redis rate limits for auth if Redis is available in Compose. |
| 2 | Key bundles | PUT/GET bundle; OTPK 100 / refill under 25; atomic consume; `KEYS_REQUIRED` |
| 3 | Contacts | UUID share; requests; block; messaging gate |
| 4 | Messages | Conversations; envelopes; WS + Redis fan-out; sender sync; delivery receipts; cursors |
| 5 | Media | Nebular presign; 25 MiB; attachments |
| 6 | Receipts & presence | Read receipts; typing; online/last-seen |
| 7 | Deletes | for me / everyone; account hard-delete |
| 8 | APNs | Tokens; opaque data push |
| 9 | Calls | Signaling + coturn; VoIP push |

**Compose:** Postgres + Redis + Nebular + API → later + coturn.

---

## API surface

- Base: `/api/v1`
- Errors: `{ "error": { "code": string, "message": string } }`
- Timestamps: ISO-8601 UTC
- UUIDs: lowercase strings
- Optional JSON fields may be omitted or `null`

### Milestone 1 — Auth (locked)

#### `POST /auth/register` → `201`

```json
{
  "username": "alice",
  "password": "correct-horse-battery",
  "device_name": "iPhone 16"
}
```

| Field | Required | Notes |
| --- | --- | --- |
| `username` | yes | 3–32, `[a-z0-9_]` |
| `password` | yes | min 8; not common |
| `device_name` | no | Display label |

Success:

```json
{
  "token": "<opaque>",
  "user": { "id": "<uuid>", "username": "alice" },
  "device": { "id": "<uuid>", "name": "iPhone 16" }
}
```

`username` is the **case-folded** stored form. Client persists `token` and `device.id`.

#### `POST /auth/login` → `200`

```json
{
  "username": "alice",
  "password": "correct-horse-battery",
  "device_name": "iPhone 16",
  "device_id": "<uuid>"
}
```

| Field | Required | Notes |
| --- | --- | --- |
| `username` | yes | |
| `password` | yes | |
| `device_name` | no | Refresh name when reusing device |
| `device_id` | no | Reuse if owned by user; else new device |

Success body: same as register.

#### `POST /auth/logout` → `204`

`Authorization: Bearer <token>` — empty body; revokes current session only.

#### `GET /auth/me` → `200`

```json
{
  "user": { "id": "<uuid>", "username": "alice" },
  "device": { "id": "<uuid>", "name": "iPhone 16" }
}
```

#### `POST /auth/password` → `204`

```json
{
  "current_password": "...",
  "new_password": "..."
}
```

Revokes all **other** sessions.

#### `GET /devices` → `200`

```json
{
  "devices": [
    {
      "id": "<uuid>",
      "name": "iPhone 16",
      "created_at": "2026-07-15T12:00:00Z",
      "last_seen_at": "2026-07-15T12:05:00Z",
      "is_current": true
    }
  ]
}
```

`name` / `last_seen_at` may be null.

#### `DELETE /devices/:id` → `204`

Revokes sessions for that device. Deleting the current device invalidates the caller’s token.

#### Auth error codes

| Code | When |
| --- | --- |
| `VALIDATION_ERROR` | Bad JSON / username charset / length |
| `USERNAME_TAKEN` | Register conflict |
| `USERNAME_RESERVED` | Denylist or prefix |
| `PASSWORD_TOO_SHORT` | Fewer than 8 characters |
| `PASSWORD_TOO_COMMON` | On embedded denylist |
| `INVALID_CREDENTIALS` | Failed login (no user enumeration) |
| `DEVICE_LIMIT` | Would exceed 5 devices |
| `UNAUTHORIZED` | Missing / invalid / revoked token |
| `FORBIDDEN` | Authenticated but not allowed |
| `NOT_FOUND` | Device not found for user |
| `RATE_LIMITED` | Budget exceeded |

Later (keys milestone): `KEYS_REQUIRED`.

### Later routes (outline)

| Area | Routes |
| --- | --- |
| Health | `GET /health`; readiness (DB + Redis) |
| Keys | `PUT /keys/bundle`, `GET /keys/bundle/:user_id`, `POST /keys/otpk` |
| Users | `GET /users/:user_id`; optional exact username lookup |
| Contacts | requests, accept/reject, list, blocks |
| Messages | send, list (cursor), receipts, delete |
| Media | presign upload/download |
| Push | `PUT /push/token` |
| Real-time | `WS /ws` |
| Calls | signaling (post-messaging) |

---

## Configuration

| Variable | Purpose |
| --- | --- |
| `DATABASE_URL` | Postgres |
| `DATABASE_POOL_MAX` | Pool size per instance |
| `REDIS_URL` | Pub/sub, limits, presence |
| `HOST` / `PORT` | Bind (default localhost:8080) |
| `RUN_MIGRATIONS` | Prefer single migrator when scaled |
| `NEBULAR_URL` | Nebular base URL |
| `NEBULAR_SIGNING_SECRET` | Presign material (name may match Nebular docs) |
| `NEBULAR_MEDIA_BUCKET` | Default `shroud-media` |
| APNs credentials | When push ships |

---

## Known product risks

| Risk | Note |
| --- | --- |
| No password recovery | No live session ⇒ no server history access even with phrase. Document in UI; optional recovery codes later. |
| Indefinite retention | Storage growth; rely on user delete + account hard-delete + capacity planning. |
| Shareable UUIDs | Still require contact accept; rate-limit lookups and requests. |

---

## Still open

Deferred until the relevant milestone (not blocking Auth):

1. **Envelope ciphertext encoding** — client crypto; server stores opaque bytes.
2. **Nebular presign wire format** — exact signing API mapping when media lands.

---

## Related docs

- [architecture.md](./architecture.md) — system overview
- [thought-collection.md](../thought-collection.md) — calls, WebRTC, scaling notes
- [README.md](../README.md) — local run
