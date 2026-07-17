# Shroud server plan

Source of truth for the Rust API (`server/`): product decisions, behavior, milestones, and locked HTTP contracts.

| | |
| --- | --- |
| **Status** | Server m1–m9 **done**. iOS: auth session solid + real on-device identity crypto (phrase → keys → `PUT /keys/bundle`). Next: live chats/contacts wiring |
| **Last updated** | 2026-07-16 |
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
| Conversations | Explicit `conversations` row; **lazy-create on first message** |
| Message address | `POST /messages` with **`peer_user_id`** (not conversation_id required) |
| Ciphertext wire | **Base64** in JSON → `BYTEA` in DB |
| content_type | `text` \| `media` |
| Max ciphertext | **64 KiB** decoded |
| Multi-device store | **One message row** + `message_deliveries` per device |
| Real-time m4 | HTTP send + history (done) |
| Real-time 4b | **WebSocket** in-process fan-out (**done**) |
| Redis fan-out | **Optional** when `REDIS_URL` set: local hub + pub/sub on `shroud:user:{user_id}` |
| WS events | `message.new`, `message.delivered`, `message.read`, `message.deleted`, `typing`, `presence.update` |
| WS recipients | Peer devices + sender’s **other** devices (not the sending device for new) |
| Delivery receipts | `POST /messages/:id/delivered` for current device (m4); read later |
| Retention | Indefinite until user delete |
| History recovery | Login + encryption phrase on client → download ciphertext |
| Discovery | Share **`users.id` (UUID)** + deep link; username for login/display |
| Profile lookup | `GET /users/:user_id` → `{ id, username }` (auth, rate-limited) |
| First contact | **Contact request by target UUID only** before full messaging |
| Contact request | No expiry in v1; pending until accept / reject / cancel / block |
| Mutual request | If reverse pending exists → **auto-accept** both ways |
| Contacts storage | **Two directed rows** A→B and B→A |
| Block | Drop contact edges + cancel pending either way; store block; unblock does not re-friend |
| Message delete | Delete-for-me via `message_hides`; delete-for-everyone **anytime** (tombstone + clear ciphertext) |
| Delete WS | `message.deleted` fan-out for for-everyone |
| Account delete | Hard delete user cascade; **tombstone** messages they sent for peers |
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

### Milestone 4 — Messages schema (locked)

#### `conversations`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `UUID` PK | |
| `user_a_id` | `UUID` NOT NULL FK → `users` | Store **ordered** pair: `user_a_id < user_b_id` |
| `user_b_id` | `UUID` NOT NULL FK → `users` | |
| `created_at` | `TIMESTAMPTZ` NOT NULL | |
| UNIQUE | `(user_a_id, user_b_id)` | One 1:1 conversation per pair |

#### `messages`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `UUID` PK | Server id |
| `conversation_id` | `UUID` NOT NULL FK → `conversations` CASCADE | |
| `sender_user_id` | `UUID` NOT NULL FK → `users` | |
| `sender_device_id` | `UUID` NOT NULL FK → `devices` | |
| `client_message_id` | `UUID` NOT NULL | Idempotency key from client |
| `content_type` | `TEXT` NOT NULL | `text` \| `media` |
| `ciphertext` | `BYTEA` NOT NULL | Decoded envelope; max 64 KiB |
| `created_at` | `TIMESTAMPTZ` NOT NULL | |

Constraints:

- UNIQUE `(sender_user_id, client_message_id)` — idempotent POST
- Indexes: `(conversation_id, created_at DESC, id DESC)` for cursor pagination

#### `message_deliveries`

| Column | Type | Notes |
| --- | --- | --- |
| `message_id` | `UUID` NOT NULL FK → `messages` CASCADE | |
| `device_id` | `UUID` NOT NULL FK → `devices` CASCADE | Recipient (or sender’s other) device |
| `delivered_at` | `TIMESTAMPTZ` NULL | Set when device acks |
| PK | `(message_id, device_id)` | |

On send: insert delivery rows for **all devices of peer** + **all other devices of sender** (not the sending device, or include with delivered_at=now for sender device — prefer create rows for all devices of both users except mark sender device delivered immediately).

### Milestone 5 — Media schema (locked)

#### `media_objects`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `UUID` PK | |
| `uploader_user_id` | `UUID` NOT NULL FK → `users` | |
| `uploader_device_id` | `UUID` NOT NULL FK → `devices` | |
| `bucket` | `TEXT` NOT NULL | Default `shroud-media` |
| `object_key` | `TEXT` NOT NULL | `{uploader_user_id}/{id}` |
| `size_bytes` | `BIGINT` NULL | Declared size at presign; optional verify later |
| `content_type` | `TEXT` NULL | Client-declared opaque type (e.g. `application/octet-stream`) |
| `message_id` | `UUID` NULL FK → `messages` | Set when message posts with this media |
| `created_at` | `TIMESTAMPTZ` NOT NULL | |
| UNIQUE | `(bucket, object_key)` | |

Extend `messages` (or keep ciphertext as envelope that may contain media keys; server also accepts):

- Optional `media_object_id UUID NULL REFERENCES media_objects` on `messages` via new migration — link server-side for ACL.

### Milestone 6 — Read receipts schema (locked)

#### `message_reads`

| Column | Type | Notes |
| --- | --- | --- |
| `message_id` | `UUID` NOT NULL FK → `messages` CASCADE | |
| `user_id` | `UUID` NOT NULL FK → `users` CASCADE | Reader (recipient) |
| `read_at` | `TIMESTAMPTZ` NOT NULL | |
| PK | `(message_id, user_id)` | User-level (not per-device) |

Presence uses existing `devices.last_seen_at` + in-process / Redis online sets — no extra tables.

### Milestone 7 — Deletes schema (locked)

#### `messages` columns (add)

| Column | Type | Notes |
| --- | --- | --- |
| `deleted_for_everyone_at` | `TIMESTAMPTZ` NULL | Set on unsend |
| `ciphertext` | `BYTEA` NULL | **Become nullable**; cleared on for-everyone |

#### `message_hides`

| Column | Type | Notes |
| --- | --- | --- |
| `user_id` | `UUID` NOT NULL FK → `users` CASCADE | |
| `message_id` | `UUID` NOT NULL FK → `messages` CASCADE | |
| `created_at` | `TIMESTAMPTZ` NOT NULL | |
| PK | `(user_id, message_id)` | |

History `GET /messages` excludes rows hidden for the caller; for-everyone rows return with empty/null ciphertext and a deleted flag.

### Milestone 8 — Push schema (locked)

#### `push_tokens`

| Column | Type | Notes |
| --- | --- | --- |
| `device_id` | `UUID` PK FK → `devices` CASCADE | One token per device |
| `apns_token` | `TEXT` NOT NULL | Hex device token |
| `environment` | `TEXT` NOT NULL | `sandbox` \| `production` |
| `updated_at` | `TIMESTAMPTZ` NOT NULL | |

### Later entities (sketch)

| Entity | Role |
| --- | --- |
| (presence/typing keys) | Redis TTLs later |

Redis: pub/sub fan-out, online sets, future rate limits.

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

### Messages (m4 HTTP)

- Require **accepted contact** and no block either way.
- `POST` with `peer_user_id` + `client_message_id` + `content_type` + base64 `ciphertext`.
- Lazy-create conversation (ordered user pair).
- Idempotent on `(sender_user_id, client_message_id)` → return existing message.
- Create `message_deliveries` for peer devices + sender’s other devices; sending device marked delivered at insert.
- History: `GET /messages?peer_user_id=&before_created_at=&before_id=&limit=` keyset page.
- `POST /messages/:id/delivered` marks current device delivery.
- WS / Redis push deferred after m4.
- **Note:** one ciphertext blob shared across devices is a server simplification; true per-device sealed ciphertext can upgrade later.

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

- **Typing** — ephemeral WS only: client `{ "type": "typing", "peer_user_id", "is_typing" }` → peer gets same shape plus `user_id` / `device_id`. Contacts only; no DB.
- **Online / last-seen** — online = at least one live WS (in-process hub + optional Redis `shroud:online:{user_id}` set). `last_seen_at` = max `devices.last_seen_at`. `GET /presence/:user_id` contacts-only (self always allowed). On connect/disconnect, fan-out `presence.update` to accepted contacts.
- **Read receipts** — user-level (`message_reads`); not per-device. Recipient only; idempotent. Single + bulk up-to cursor. WS `message.read`.
- WS must auth within 10s.
- **Data APNs** when recipient has **no** online WS: silent `content-available` payload with opaque `message_id` / `conversation_id` / `peer_user_id` only. Requires `APNS_KEY_PATH` or `APNS_KEY_PEM` + `APNS_KEY_ID` + `APNS_TEAM_ID` + `APNS_TOPIC`. Per-device host from `push_tokens.environment`. Permanent APNs token errors delete the row.

### Calls (m9)

- **1:1 only**; contacts required; one ringing/active call per user.
- State machine: `ringing` → `active` | `rejected` | `cancelled` | `missed`; `active` → `ended`.
- SDP/ICE are **opaque client blobs** (relayed, not stored).
- `GET /calls/ice-servers` returns STUN (default) + optional TURN from env.
- Compose: `docker compose --profile calls up` starts **coturn** (host network, local-only credentials).
- Offline callee: opaque APNs data push with `call_id` / `peer_user_id` / `modality` (VoIP cert path later).

---

## Implementation milestones

| # | Milestone | Deliverables |
| --- | --- | --- |
| **1** | **Auth** | **Done** — register/login/logout/me/password, devices, sessions, migration 002, tests |
| **2** | **Key bundles** | **Done** — migration 003; PUT/GET/status/otpk; atomic OTPK consume; tests |
| **3** | **Contacts** | **Done** — migration 004; user card; requests; mutual accept; contacts; blocks |
| **4** | **Messages (HTTP)** | **Done** — migration 005; send/list/conversations/delivered |
| **4b** | **WebSocket** | **Done** — `/ws`, in-process hub, message.new + message.delivered |
| **5** | **Media** | **Done** — migration 006; upload/download presign (stub/Nebular); media on messages |
| **6** | **Receipts & presence** | **Done** — migration 009 `message_reads`; `POST /messages/:id/read` + bulk; `GET /presence/:user_id`; WS `typing` + `presence.update` + `message.read` |
| **7** | **Deletes** | **Done** — migration 007; for me / everyone; account delete; message.deleted WS |
| **8** | **APNs** | **Done** — migration 008; `PUT /push/token`; offline WS gate; HTTP/2 ES256 JWT client (`.p8` / `APNS_KEY_PEM`); drop invalid tokens |
| **9** | **Calls** | **Done** — migration 010; ring/accept/reject/hangup/signal; `GET /calls/ice-servers`; WS events; coturn compose profile; opaque call data push |

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

Fetch a pre-key bundle for the **most recently active** publishable device (any authenticated caller). Prefer multi-device list for fan-out.

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

#### `GET /keys/bundles/:user_id` → `200`

All publishable devices (identity + signed pre-key) for multi-device sealed send.

```json
{
  "user_id": "<uuid>",
  "bundles": [
    {
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
  ]
}
```

- Ordered by `last_seen_at DESC`, then `created_at DESC`.
- **One OTPK consumed per device** when that device’s pool is non-empty (omitted otherwise).
- Empty publishable set → `404` + `KEYS_REQUIRED` (same enumeration posture as single GET).
- Same rate limits as single bundle GET (`keys_ip` / `keys_user`).

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

### Milestone 4 — Messages (locked)

#### `POST /messages` → `201` (or `200` if idempotent replay)

```json
{
  "peer_user_id": "<uuid>",
  "client_message_id": "<uuid>",
  "content_type": "text",
  "ciphertext": "<base64>"
}
```

Success:

```json
{
  "id": "<uuid>",
  "conversation_id": "<uuid>",
  "sender_user_id": "<uuid>",
  "sender_device_id": "<uuid>",
  "client_message_id": "<uuid>",
  "content_type": "text",
  "ciphertext": "<base64>",
  "created_at": "..."
}
```

Errors: `FORBIDDEN` (not contacts / blocked), `VALIDATION_ERROR` (size, content_type, base64), `UNAUTHORIZED`.

#### `GET /messages` → `200`

Query: `peer_user_id` (required), `limit` (default 50, max 100), optional `before_created_at` + `before_id` cursor.

```json
{
  "conversation_id": "<uuid>|null",
  "messages": [ /* same fields as POST, newest or oldest first — **descending by created_at, id** */ ]
}
```

Empty conversation (no messages yet / no row) → `messages: []`, `conversation_id: null`.

#### `GET /conversations` → `200`

List conversations for me with peer card + last message preview metadata (optional thin):

```json
{
  "conversations": [
    {
      "id": "<uuid>",
      "peer": { "id": "<uuid>", "username": "bob" },
      "created_at": "...",
      "last_message_at": "..."
    }
  ]
}
```

#### `POST /messages/:id/delivered` → `204`

Marks `message_deliveries.delivered_at = now()` for **current device**. `404` if message not addressed to this user/device.

### Milestone 5 — Media (locked)

#### `POST /media/uploads` → `201`

```json
{
  "size_bytes": 12345,
  "content_type": "application/octet-stream"
}
```

`size_bytes` required, 1…25 MiB. Response:

```json
{
  "media_object_id": "<uuid>",
  "upload_url": "https://…",
  "object_key": "<user_id>/<media_object_id>",
  "expires_at": "…"
}
```

#### `POST /media/:id/download` → `200`

```json
{
  "download_url": "https://…",
  "expires_at": "…"
}
```

#### `POST /messages` extension

Optional field: `"media_object_id": "<uuid>"` required when `content_type` is `media`.

#### Media error codes

| Code | When |
| --- | --- |
| `VALIDATION_ERROR` | size/type |
| `FORBIDDEN` | not allowed to download / not owner of media |
| `NOT_FOUND` | media missing |
| `ALREADY_EXISTS` | media already linked to a message |

### Milestone 4b — WebSocket (locked)

#### `GET /api/v1/ws` → WebSocket upgrade

1. Client connects (no auth header required; optional).
2. Within **10 seconds**, client sends text JSON:
   ```json
   { "type": "auth", "token": "<session token>" }
   ```
3. Server validates token (same as Bearer), binds socket to `(user_id, device_id)`, replies:
   ```json
   { "type": "auth.ok", "user_id": "<uuid>", "device_id": "<uuid>" }
   ```
4. On failure or timeout: close connection.

#### Server → client events

```json
{
  "type": "message.new",
  "message": { /* same fields as POST /messages response */ }
}
```

```json
{
  "type": "message.delivered",
  "message_id": "<uuid>",
  "device_id": "<uuid>",
  "delivered_at": "..."
}
```

#### Fan-out (in-process)

- Keep `HashMap<device_id, mpsc::Sender<Event>>` (or user→devices) behind `tokio::sync::RwLock` on `AppState`.
- On `POST /messages` success: notify all online devices of peer + sender except `sender_device_id`.
- On `POST /messages/:id/delivered`: notify online devices of the **message sender** (other devices / peer may care — notify **sender_user**’s online devices and peer’s other devices; minimum: notify **sender’s devices** so ticks update).
- **Recommended delivered notify:** all online devices of both conversation users except the device that just acked.

#### Not in 4b (partially lifted in m6)

- Redis pub/sub — optional when `REDIS_URL` set
- Typing / presence — **m6**
- Client→server after auth: **`typing`** (m6); unknown types ignored

### Milestone 6 — Presence & read receipts (locked)

#### `GET /presence/:user_id` → `200`

```json
{
  "user_id": "<uuid>",
  "online": false,
  "last_seen_at": "2026-07-15T12:00:00Z"
}
```

- Self always allowed. Other users: **accepted contacts only** → else `403` + `FORBIDDEN`.
- `online`: any live WebSocket for that user (local hub / Redis online set).
- `last_seen_at`: max `devices.last_seen_at` (omitted if null).

#### `POST /messages/:id/read` → `204`

- Caller must be a conversation participant and **not** the sender.
- Upserts `message_reads (message_id, user_id)`; idempotent.
- WS fan-out to conversation users except acking device:

```json
{
  "type": "message.read",
  "message_id": "<uuid>",
  "conversation_id": "<uuid>",
  "user_id": "<reader>",
  "device_id": "<device>",
  "read_at": "..."
}
```

#### `POST /messages/read` → `200`

```json
{
  "peer_user_id": "<uuid>",
  "up_to_message_id": "<uuid>"
}
```

Marks all messages **from** `peer_user_id` **to** the caller in that conversation with `(created_at, id) <=` the cursor message. Response:

```json
{ "marked": 2, "read_at": "..." }
```

WS `message.read` includes `up_to_message_id` + `marked` when bulk.

#### WebSocket client → server (after auth)

```json
{ "type": "typing", "peer_user_id": "<uuid>", "is_typing": true }
```

Server → peer only (contacts required):

```json
{
  "type": "typing",
  "user_id": "<sender>",
  "device_id": "<device>",
  "peer_user_id": "<uuid>",
  "is_typing": true
}
```

#### WebSocket server → contacts (connect / full disconnect)

```json
{
  "type": "presence.update",
  "user_id": "<uuid>",
  "online": true,
  "last_seen_at": "..."
}
```

Offline fan-out only when the user has **no** remaining online devices.

### Milestone 9 — Calls (locked)

#### Schema `calls`

| Column | Notes |
| --- | --- |
| `id` | UUID |
| `caller_user_id` / `caller_device_id` | Originator |
| `callee_user_id` / `callee_device_id` | Target; device set on accept |
| `modality` | `voice` \| `video` |
| `status` | `ringing` \| `active` \| `ended` \| `rejected` \| `busy` \| `missed` \| `cancelled` |
| `ended_reason` / timestamps | Minimal metadata only |

#### Routes

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/calls/ice-servers` | Auth; STUN/TURN for WebRTC |
| POST | `/calls` | `{ peer_user_id, modality?, sdp_offer? }` → `201` ringing |
| GET | `/calls/:id` | Participant only |
| POST | `/calls/:id/accept` | Callee; `{ sdp_answer? }` |
| POST | `/calls/:id/reject` | Callee while ringing |
| POST | `/calls/:id/hangup` | Cancel / end |
| POST | `/calls/:id/signal` | `{ signal_type, payload }` relay to peer |

#### WebSocket events

- `call.ring`, `call.accepted`, `call.ended`, `call.signal`

Errors: `CALL_BUSY` (409) when peer or self already in ringing/active call; `FORBIDDEN` non-contacts.

### Milestone 8 — Push (locked)

#### `PUT /push/token` → `204`

```json
{ "token": "<apns device token>", "environment": "sandbox" }
```

- `environment`: `sandbox` | `production` (selects APNs host at send time).
- Upserts one token per `device_id`.

#### Offline data push (server-internal)

On successful `POST /messages`, if the **peer** has no online WebSocket:

1. Load `push_tokens` for peer's devices.
2. If APNs client not configured → log only.
3. Else HTTP/2 POST `https://api[.sandbox].push.apple.com/3/device/{token}` with:
   - JWT bearer (`iss` = team, `kid` = key id, ES256, cached ~50m)
   - `apns-topic`, `apns-push-type: background`, `apns-priority: 5`
   - Body: `{ "aps": { "content-available": 1 }, "message_id", "conversation_id", "peer_user_id" }` only

`BadDeviceToken` / `Unregistered` / `DeviceTokenNotForTopic` / `ExpiredToken` → delete token row.

### Milestone 7 — Deletes (locked)

#### `DELETE /messages/:id?scope=me|everyone` → `204`

- **me** (default): insert `message_hides` for caller if participant; no WS required (optional multi-device later).
- **everyone**: only **sender** may call; set `deleted_for_everyone_at`, set `ciphertext = NULL`, unlink media (`media_objects.message_id` null / message.media_object_id null). WS:

```json
{
  "type": "message.deleted",
  "message_id": "<uuid>",
  "conversation_id": "<uuid>",
  "scope": "everyone"
}
```

Fan-out: both conversation users' online devices.

#### Message response fields

Add optional:

- `deleted_for_everyone`: bool
- `ciphertext`: null when deleted for everyone

#### `DELETE /auth/account` → `204`

- Authenticated; optional body `{ "password": "..." }` verify.
- Tombstone all messages where `sender_user_id = me` (clear ciphertext, set deleted_for_everyone_at).
- `DELETE FROM users WHERE id = me` (cascades devices, sessions, keys, contacts, blocks, media ownership, hides, deliveries via FKs).
- Remaining conversation rows may still exist for peer with tombstoned messages.

### Later routes (outline)

| Area | Routes |
| --- | --- |


---

## Configuration

| Variable | Purpose |
| --- | --- |
| `DATABASE_URL` | Postgres |
| `DATABASE_POOL_MAX` | Pool size per instance |
| `REDIS_URL` | Optional; enables multi-replica WS fan-out (+ future limits/presence) |
| `HOST` / `PORT` | Bind (default localhost:8080) |
| `RUN_MIGRATIONS` | Prefer single migrator when scaled |
| `NEBULAR_URL` | Nebular base URL |
| `NEBULAR_SIGNING_SECRET` | Presign material (name may match Nebular docs) |
| `NEBULAR_MEDIA_BUCKET` | Default `shroud-media` |
| `APNS_KEY_PATH` or `APNS_KEY_PEM` | PKCS#8 AuthKey `.p8` (path or inline PEM) |
| `APNS_KEY_ID` | Key ID from Apple developer |
| `APNS_TEAM_ID` | Apple Team ID (JWT `iss`) |
| `APNS_TOPIC` | App bundle id (`apns-topic`) |
| `TURN_URLS` / `TURN_USERNAME` / `TURN_CREDENTIAL` | Optional TURN for ICE response |
| `ICE_SERVERS_JSON` | Full ICE server JSON array (overrides TURN_* defaults) |

---

## Known product risks

| Risk | Note |
| --- | --- |
| No password recovery | No live session ⇒ no server history access even with phrase. Document in UI; optional recovery codes later. |
| Indefinite retention | Storage growth; rely on user delete + account hard-delete + capacity planning. |
| Shareable UUIDs | Still require contact accept; rate-limit lookups and requests. |

---

## Still open

1. **Nebular presign wire format** — harden real signing when not stub (Compose Nebular works for local).
2. **Multi-device key fetch for send** — **done** (`GET /keys/bundles/:user_id` returns all publishable devices with optional OTPK each; single-device `GET /keys/bundle/:user_id` kept).
3. **Redis rate-limit wiring** — **done** (`rate_limit` module; Redis fixed windows when `REDIS_URL` set, else in-process; scopes: auth IP/username, user lookup IP, keys IP/user, contact requests, media presign, WS connect).
4. **VoIP / CallKit push** — dedicated PushKit cert path (currently same data-push channel as messages).
5. **iOS polish** — media messages, call UI/WebRTC, presence polish, unread badges, multi-device own-message decrypt without local cache.
6. **Double Ratchet** — client sealed ECDH+AES-GCM envelopes ship first; upgrade sessions to Signal-style DR later.
7. **Envelope ciphertext encoding** — server stores opaque bytes; client currently uses JSON sealed envelope inside Base64 ciphertext field.

---

## Related docs

- [architecture.md](./architecture.md) — system overview
- [thought-collection.md](../thought-collection.md) — calls, WebRTC, scaling notes
- [README.md](../README.md) — local run
