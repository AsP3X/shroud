# Shroud server plan

Source of truth for the Rust API (`server/`): product decisions, behavior, milestones, and locked HTTP contracts.

| | |
| --- | --- |
| **Status** | Milestones 1–2 **implemented**. Milestone 3 (Contacts) **API + schema locked** — ready to implement |
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
| Key scope | **Per device** (identity, signed pre-key, OTPK pool each tied to `devices.id`) |
| Key upload | Separate authenticated call after register/login |
| Keys gate | Session OK without keys; messaging / being fetchable as recipient needs bundle (`KEYS_REQUIRED`) |
| PUT semantics | Upsert **identity + signed pre-key**; **merge/add** OTPKs by `(device_id, key_id)` (do not wipe unconsumed OTPKs) |
| GET target | Single bundle for user: device with keys, prefer **most recently `last_seen_at`** among devices that have identity+SPK |
| OTPK on GET | **Atomically consume** one OTPK if available; omit field if pool empty (still return identity + SPK) |
| OTPKs | Client uploads batches of up to **100** per request; refills when remaining under **25** (`GET /keys/status`) |
| OTPK pool max | **200** per device; reject uploads that would exceed |
| Key field sizes | After base64 decode: public keys **32–64** bytes; signatures **64–128** bytes; reject empty/oversized |
| ID ranges | `registration_id` **0–16383**; `key_id` **0–0xFFFFFF** (non-negative) |
| Encoding | Public keys / signatures as **standard Base64** of raw bytes; `key_id` / `registration_id` as integers |
| Fetch ACL (m2) | **Any authenticated user** may fetch; contact gate deferred to contacts/messaging |
| Key fetch rate limit | **60/min per user** + **120/min per IP** (when Redis limits land; document now) |
| History crypto (client) | Phrase → account backup key wraps history; server sees opaque blobs only |

### Messaging and social

| Area | Decision |
| --- | --- |
| Topology | **1:1 only** |
| Conversations | Explicit `conversations` row; messages reference `conversation_id` |
| Retention | Indefinite until user delete |
| History recovery | Login + encryption phrase on client → download ciphertext |
| Discovery | Share **`users.id` (UUID)** + deep link; username for login/display |
| Profile lookup | `GET /users/:user_id` → `{ id, username }` (auth, rate-limited) |
| First contact | **Contact request by target UUID only** before full messaging |
| Contact request | No expiry in v1; pending until accept / reject / cancel / block |
| Mutual request | If reverse pending exists → **auto-accept** both ways |
| Contacts storage | **Two directed rows** A→B and B→A |
| Block | Drop contact edges + cancel pending either way; store block; unblock does not re-friend |
| Message delete | Delete-for-me; delete-for-everyone **anytime** (messages milestone) |
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

### Milestone 2 — Key bundle schema (locked)

All public material only. Cascade delete with `devices`.

#### `device_identity_keys`

| Column | Type | Notes |
| --- | --- | --- |
| `device_id` | `UUID` PK FK → `devices(id)` **ON DELETE CASCADE** | One identity row per device |
| `registration_id` | `INT` NOT NULL | Client Signal registration id (0–16380 typical) |
| `public_key` | `BYTEA` NOT NULL | Identity public key raw bytes |
| `created_at` | `TIMESTAMPTZ` NOT NULL | `now()` |
| `updated_at` | `TIMESTAMPTZ` NOT NULL | Bumped on PUT replace |

#### `device_signed_prekeys`

| Column | Type | Notes |
| --- | --- | --- |
| `device_id` | `UUID` PK FK → `devices(id)` **ON DELETE CASCADE** | One **current** SPK per device (replace on PUT) |
| `key_id` | `INT` NOT NULL | Client-assigned SPK id |
| `public_key` | `BYTEA` NOT NULL | |
| `signature` | `BYTEA` NOT NULL | Signature over SPK by identity key |
| `uploaded_at` | `TIMESTAMPTZ` NOT NULL | `now()` on each replace |

#### `device_one_time_prekeys`

| Column | Type | Notes |
| --- | --- | --- |
| `device_id` | `UUID` NOT NULL FK → `devices(id)` **ON DELETE CASCADE** | |
| `key_id` | `INT` NOT NULL | Client-assigned; unique per device |
| `public_key` | `BYTEA` NOT NULL | |
| `created_at` | `TIMESTAMPTZ` NOT NULL | `now()` |
| PK | `(device_id, key_id)` | Merge insert: conflict skip or update public_key |

Indexes: `(device_id)` on OTPK for count/consume. Consume: `DELETE … WHERE device_id = $1 LIMIT 1 RETURNING *` (or `FOR UPDATE SKIP LOCKED` pattern).

**Eligible device for GET:** has rows in `device_identity_keys` and `device_signed_prekeys`; order by `devices.last_seen_at DESC NULLS LAST`, then `devices.created_at DESC`.

### Milestone 3 — Contacts schema (locked)

#### `contact_requests`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `UUID` PK | |
| `from_user_id` | `UUID` NOT NULL FK → `users` CASCADE | Requester |
| `to_user_id` | `UUID` NOT NULL FK → `users` CASCADE | Target |
| `status` | `TEXT` NOT NULL | `pending` \| `accepted` \| `rejected` \| `cancelled` |
| `created_at` | `TIMESTAMPTZ` NOT NULL | |
| `responded_at` | `TIMESTAMPTZ` NULL | Set on accept/reject/cancel/block cleanup |

Constraints:

- `from_user_id <> to_user_id`
- **Partial unique:** at most one `pending` pair `(from_user_id, to_user_id)` where `status = 'pending'`
- Indexes: `(to_user_id, status)`, `(from_user_id, status)`

#### `contacts`

| Column | Type | Notes |
| --- | --- | --- |
| `user_id` | `UUID` NOT NULL FK → `users` CASCADE | Owner of this edge |
| `contact_user_id` | `UUID` NOT NULL FK → `users` CASCADE | Peer |
| `created_at` | `TIMESTAMPTZ` NOT NULL | |
| PK | `(user_id, contact_user_id)` | Directed; always insert **both** directions on accept |

#### `blocks`

| Column | Type | Notes |
| --- | --- | --- |
| `blocker_id` | `UUID` NOT NULL FK → `users` CASCADE | |
| `blocked_id` | `UUID` NOT NULL FK → `users` CASCADE | |
| `created_at` | `TIMESTAMPTZ` NOT NULL | |
| PK | `(blocker_id, blocked_id)` | |
| Check | `blocker_id <> blocked_id` | |

On **block**: delete both `contacts` rows for the pair; set any `pending` requests either way to `cancelled`; upsert block row.  
On **unblock**: delete block row only.

### Later entities (sketch)

| Entity | Role |
| --- | --- |
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

### Key bundles

- After auth, client `PUT /keys/bundle` for the **current device** (identity + SPK + OTPK batch).
- Replenish OTPKs via `PUT /keys/bundle` (merge) or `POST /keys/otpk`; poll `GET /keys/status` and refill when `otpk_count < 25`.
- Peer `GET /keys/bundle/:user_id` (authenticated): pick most recently active device with keys; attach one consumed OTPK if any.
- Server never validates cryptographic correctness of signatures beyond size/presence checks (optional later); stores bytes only.
- Messaging (later) may need **per-device** fetches for multi-device fan-out; m2 ships single-device GET only.

### Contacts

- Share `users.id`; open link → `GET /users/:id` → `POST /contacts/requests` with `user_id`.
- Reject self-request, existing contact, existing pending (idempotent or conflict), and if **either** direction is blocked.
- Mutual pending → auto-accept: both `contacts` rows + mark requests `accepted`.
- Accept/reject only by **to_user**; cancel only by **from_user**.
- Messaging (later) requires a `contacts` edge and no block either way.

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
| Key bundle GET | 60/min per user; 120/min per IP |
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
| **1** | **Auth** | **Done** — register/login/logout/me/password, devices, sessions, migration 002, tests |
| **2** | **Key bundles** | **Done** — migration 003; PUT/GET/status/otpk; atomic OTPK consume; tests |
| **3** | **Contacts** | Schema + routes below; requests; auto-mutual accept; blocks; user card |
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

### Milestone 2 — Key bundles (locked)

All routes require `Authorization: Bearer` unless noted. Key material fields are **standard Base64** strings of raw bytes.

#### `PUT /keys/bundle` → `204`

Uploads/replaces keys for the **authenticated current device**.

```json
{
  "registration_id": 12345,
  "identity_key": "<base64>",
  "signed_pre_key": {
    "key_id": 1,
    "public_key": "<base64>",
    "signature": "<base64>"
  },
  "one_time_pre_keys": [
    { "key_id": 1, "public_key": "<base64>" }
  ]
}
```

| Field | Required | Notes |
| --- | --- | --- |
| `registration_id` | yes | Integer |
| `identity_key` | yes | Base64 public key |
| `signed_pre_key` | yes | Replaces current SPK for device |
| `one_time_pre_keys` | no | Array max **100** items; merge by `key_id`. Reject if resulting pool would exceed **200** |

#### `GET /keys/bundle/:user_id` → `200`

Fetch a pre-key bundle to start a session with that user (any authenticated caller in m2).

```json
{
  "user_id": "<uuid>",
  "device_id": "<uuid>",
  "registration_id": 12345,
  "identity_key": "<base64>",
  "signed_pre_key": {
    "key_id": 1,
    "public_key": "<base64>",
    "signature": "<base64>"
  },
  "one_time_pre_key": {
    "key_id": 42,
    "public_key": "<base64>"
  }
}
```

- `one_time_pre_key` **omitted** if pool empty (not an error).
- If user has **no** device with identity+SPK → `404` + `KEYS_REQUIRED`.
- OTPK row deleted in the same transaction as the read when present.

#### `GET /keys/status` → `200`

Status for the **current device** (for refill logic).

```json
{
  "device_id": "<uuid>",
  "has_identity": true,
  "signed_pre_key_id": 1,
  "otpk_count": 87
}
```

`signed_pre_key_id` null / omitted if no SPK yet. Client refills when `otpk_count < 25`.

#### `POST /keys/otpk` → `204`

Replenish OTPKs only for current device (merge by `key_id`). Max **100** keys per request; pool cap **200**.

```json
{
  "one_time_pre_keys": [
    { "key_id": 101, "public_key": "<base64>" }
  ]
}
```

#### Key error codes

| Code | When |
| --- | --- |
| `VALIDATION_ERROR` | Missing fields, bad base64, wrong byte lengths, invalid `registration_id`/`key_id`, array &gt; 100 |
| `PREKEY_POOL_FULL` | Upload would exceed **200** OTPKs on the device |
| `KEYS_REQUIRED` | Target user missing or has no publishable bundle (GET) |
| `UNAUTHORIZED` | No/invalid bearer |
| `RATE_LIMITED` | Budget exceeded |

**Enumeration:** unknown `user_id` and “no keys” both return `404` + `KEYS_REQUIRED` (identical body).

### Milestone 3 — Contacts (locked)

All routes require Bearer auth.

#### `GET /users/:user_id` → `200`

```json
{ "id": "<uuid>", "username": "alice" }
```

- `404` + `NOT_FOUND` if user does not exist.
- Still returns card if blocked (block only affects requests/messaging); optional harden later.

#### `POST /contacts/requests` → `201` (or `200` if auto-accepted)

```json
{ "user_id": "<uuid>" }
```

Response:

```json
{
  "id": "<request-uuid>",
  "from_user_id": "<uuid>",
  "to_user_id": "<uuid>",
  "status": "pending",
  "created_at": "..."
}
```

If mutual auto-accept: `status` is `accepted` and contacts exist both ways.

Errors: `VALIDATION_ERROR` (self), `NOT_FOUND`, `FORBIDDEN` (blocked either way), `CONFLICT` / `ALREADY_EXISTS` if already contacts or pending outbound.

#### `GET /contacts/requests` → `200`

Query: `?box=incoming|outgoing` (default `incoming`), optional `status=pending` (default pending only).

```json
{
  "requests": [
    {
      "id": "<uuid>",
      "from_user_id": "<uuid>",
      "to_user_id": "<uuid>",
      "status": "pending",
      "created_at": "...",
      "user": { "id": "<peer-uuid>", "username": "bob" }
    }
  ]
}
```

#### `POST /contacts/requests/:id/accept` → `200`

Only `to_user`. Creates both contact edges; sets request `accepted`.

#### `POST /contacts/requests/:id/reject` → `200`

Only `to_user`. Sets `rejected`.

#### `POST /contacts/requests/:id/cancel` → `200`

Only `from_user`. Sets `cancelled`.

#### `GET /contacts` → `200`

```json
{
  "contacts": [
    { "user_id": "<uuid>", "username": "bob", "created_at": "..." }
  ]
}
```

#### `DELETE /contacts/:user_id` → `204`

Remove both directed edges (unfriend). Does not create a block.

#### `POST /blocks` → `204`

```json
{ "user_id": "<uuid>" }
```

Block side effects as in schema section.

#### `DELETE /blocks/:user_id` → `204`

Unblock only.

#### `GET /blocks` → `200`

```json
{
  "blocks": [
    { "user_id": "<uuid>", "username": "mallory", "created_at": "..." }
  ]
}
```

#### Contact error codes

| Code | When |
| --- | --- |
| `VALIDATION_ERROR` | Self-target, bad body |
| `NOT_FOUND` | User or request not found / not yours |
| `FORBIDDEN` | Blocked either way; not allowed to accept others’ requests |
| `ALREADY_EXISTS` | Already contacts or pending request |
| `UNAUTHORIZED` | No bearer |
| `RATE_LIMITED` | Contact request budget |

### Later routes (outline)

| Area | Routes |
| --- | --- |
| Health | readiness (DB + Redis) |
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

1. **Envelope ciphertext encoding** — client crypto; server stores opaque bytes (messaging milestone).
2. **Nebular presign wire format** — when media lands.
3. **Multi-device key fetch for send** — m2 is single best-device GET; messaging will likely add list/fetch-all-device bundles for fan-out.
4. **Redis rate-limit wiring** — budgets documented; enforce when Redis is in the stack (auth/keys can ship without Redis first).

---

## Related docs

- [architecture.md](./architecture.md) — system overview
- [thought-collection.md](../thought-collection.md) — calls, WebRTC, scaling notes
- [README.md](../README.md) — local run
