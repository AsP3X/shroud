# Anonymity hardening — plan

Working plan from the 2026-10-03 audit: what someone with access to the server, its logs, Redis,
the proxies, the TURN server or a push provider could use to identify a Shroud user or profile
them. Message content is end-to-end encrypted and out of scope; this is about **metadata** and
**links to a real person**.

Tick a box only when the change is built **and** its "Done when" is verified. Paths are relative
to the repo root; server code is under `server/crates/shroud-server/src/`.

Ground rules:

- Keep only what the server needs to deliver messages and enforce privacy settings. Seal
  anything else, or don't store it (see the "no plaintext unless required" rule).
- A deletion or retention change also covers the copies: logs, Redis, backups and dead Postgres
  rows.
- Every change that moves a field out of a server response lands in iOS, web and Android
  together, and in the matching `.pen` file if it changes what a screen shows.

Priority order: **phase 1 first** (each item cuts a direct path from an account to a person or
to their chat partners), then phase 2, then 3. Phase 4 lists what can only be reduced.

---

## Phase 0 — Usernames off the server

Built on `dev`: clients send `SHA-256(case-folded name)` as standard Base64 (`username_hash`);
migration `server/migrations/postgres/029_private_usernames.sql` hashes the stored name and drops
`users.username`; mutual contacts learn a name only from `contact_sealed_names`
(`PUT /contacts/{id}/sealed-name`); register, login, `/auth/me`, contacts, chats, blocks, calls
and push do not carry a username; `GET /users/by-username` is gone; register and login logs name
the account id only. `caller_username` and `callee_username` remain on the call type and are
always omitted. `discoverable_by_username` is still stored and no longer changes discovery.
The privacy screens no longer offer "Find me by username". The iOS, Android and web `.pen`
files still show that switch until a design pass.

- [x] **0.1 Review and commit the change** (server, web, iOS, Android, migration 029).
  - Done when: committed; server tests pass with a real database (they skip silently on a DB
    error, so check with `--nocapture`); `grep -rn username` over server `src/` finds only
    validation, error texts, the unused discovery flag, the omitted call fields and TURN.
  - Verified 2026-10-03 against a throwaway Postgres (`postgres:16`), not the dev database:
    `cargo test -p shroud-server` passed (one ignored ntfy test). `cargo clippy -p shroud-server
    --all-targets -- -D warnings` passed. Web `tsc` and `username.selftest` passed. Android unit
    tests for contacts, chats, calls, DTOs and the name cache passed. The iOS app built.
- [ ] **0.2 Make the username hash expensive to guess.** Today it is unsalted SHA-256 over
  `[a-z0-9_]{3,32}`, so a dictionary reverses most names in seconds from the DB or from the
  Redis keys `rl:auth_user:{hash}`.
  - Change: a slow, salted hash on the client (for example argon2id with a per-server salt from
    `GET /config`); migrate existing hashes at each user's next login. A server-side pepper alone
    doesn't help against someone who controls the server.
  - Where: `auth/username.rs`, `web/src/crypto/username.ts`, `ios/shroud/Services/Auth/UsernameHash.swift`,
    `android/.../core/auth/UsernameHash.kt`.
  - Done when: one guess costs ≥ 100 ms on a server CPU; all three clients log in to the same
    account; old accounts migrate on login.
- [ ] **0.3 Scrub the plaintext usernames left behind.** Logs from before 029 contain
  `username = …` on every register and login; backups, Redis RDB snapshots and dead Postgres
  rows still hold names.
  - Change: rotate and delete old API logs; `VACUUM FULL users`; replace or re-encrypt older
    backups; restart Redis without its old `dump.rdb`.
  - Done when: `grep` over kept logs and a fresh backup finds no username.
- [ ] **0.4 Stop registration from confirming that a name exists.** `USERNAME_TAKEN`, together
  with the spoofable rate limit (3.4), lets anyone check guesses.
  - Change: a per-IP limit that really holds (3.4), plus a slower path (proof of work, or the
    same delay as success) for taken names. Fully hiding "taken" isn't possible while names are
    unique.
  - Done when: checking names from one client costs ≥ 1 s per name after the first few.
- [x] **0.5 Update `design/admin.pen`.** The Users and User detail frames show `@usernames` the
  server can no longer know. Show user ids; decide whether the share code (itself an
  identifier) belongs in the console at all.
  - Done when: no admin frame shows a username; the user has saved the `.pen` file.
  - Verified 2026-10-03: accounts appear by ID only, with neutral avatars and day-level "last
    active"; the share code is left out of the console. The saved file contains no username,
    including layer names.

---

## Phase 1 — Cut the direct links to a person

- [ ] **1.1 Turn down the app logs.** At the default `info` level the API logs who talks to
  whom and when.
  - What's logged today:
    - `messages.send ok` with sender, device, peer, conversation and ciphertext size
      (`routes/messages.rs`)
    - key fetches as requester → target (`routes/keys.rs`)
    - contact requests and accepts, calls, media up/downloads
    - every WebSocket connect and disconnect with the foreground flag (`routes/ws.rs`,
      `realtime/mod.rs`)
    - one line per push sent (`push/mod.rs`)
    - the full request URI, including share codes and target user ids (`lib.rs` trace layer)
  - Change: move per-event lines to `debug`; log only the route template, never the path values
    or the query string; log outcomes and counts, not ids; set a retention period (Docker
    `json-file` `max-size`/`max-file`, or the host's journald).
  - Done when: an `info` log of a session that registers, adds a contact, sends a message, calls
    and uploads media contains no user, device, conversation, media or call id, and no share code.
- [ ] **1.2 Stop the proxies logging IPs and user-agents.** *(web nginx done; NPM is a manual step)* The web nginx (`web/nginx.conf.template`)
  uses the image's default `access_log`, and NPM keeps per-host logs. Matched by time against
  the API logs, they give user ↔ IP.
  - Change: `access_log off;` (or a format without `$remote_addr`, `$http_user_agent` and
    `$http_x_forwarded_for`); the same in NPM's advanced config; document it for self-hosters.
  - Done when: a request through both proxies leaves no IP or user-agent in any log.
  - 2026-10-03: `access_log off;` in `web/nginx.conf.template`; `nginx -t` passed and requests
    left no access line. NPM: the step is documented in `docker-compose.npm.yml` (each proxy
    host's Advanced tab) but not applied or verified on the live NPM. nginx's error log still
    names the client on upstream errors.
- [x] **1.3 Take the user id out of the TURN login.** `turn.rs` builds the username as
  `"<expiry>:<user_id>"`, and coturn logs to stdout (`docker-compose.yml`, `--log-file=stdout`)
  with client and peer IPs.
  - Change: a random value per credential (`"<expiry>:<random>"`); turn coturn's logging down
    (`--no-stdout-log`, or `--simple-log` without verbose).
  - Done when: a call's coturn output and its credentials contain no user or device id.
  - Verified 2026-10-03: logins are `<expiry>:<32 random hex>` (unit test plus
    `ice_servers_require_auth_and_mint_turn_logins` on a throwaway Postgres); coturn 4.6 with
    `--no-stdout-log --log-file=/dev/null --simple-log` ran, printed nothing and wrote no file.
- [ ] **1.4 Seal the ids in APNs pushes.** Each push carries `c` (conversation), `p` (peer user)
  and `m` (message), plus the call id as `apns-collapse-id` and `thread-id`, in plaintext
  (`push/payload.rs`, `push/mod.rs`). Both people in a chat get the same conversation id, so
  Apple can link their device tokens.
  - Change: put the ids inside the sealed `e` field with the existing `payload_key` (it seals
    nothing today, since the sender name is no longer sent). Use a random per-push collapse id
    and a per-device HMAC of the conversation as `thread-id`. The notification extension opens
    the seal.
  - Done when: a captured APNs payload contains no UUID that also appears in another user's
    pushes; notifications still group by chat and open the right chat.
- [x] **1.5 Password-protect Redis and stop it persisting.** Redis has no password and keeps RDB
  snapshots. Subscribing to `shroud:user:*` shows every live event; keys hold who is online and
  in the foreground (`realtime/mod.rs`) and IP-keyed rate limits (`rate_limit.rs`).
  - Change: `requirepass` (and `REDIS_URL` with the password); `--save "" --appendonly no`; remove
    the `127.0.0.1:6379` port in the local overlay unless needed.
  - Done when: `redis-cli` without the password is refused; no `dump.rdb` after a day of use.
  - Verified 2026-10-03: `REDIS_PASSWORD` (generated by `deploy.sh`/`deploy.ps1` and the setup
    wizards); `redis-cli ping` without it answers `NOAUTH`; `config get save` is empty, AOF off,
    and the `shroud_redis_data` volume is no longer mounted. The Redis-backed server tests pass
    over `redis://:<password>@…`. An existing deployment still has its old volume with a
    `dump.rdb`: remove it (`docker volume rm <project>_shroud_redis_data`, see 0.3).

---

## Phase 2 — Cut the metadata that profiles a known user

- [ ] **2.1 Pad ciphertexts.** Message and reaction ciphertexts (`web/src/crypto/messageCrypto.ts`,
  `web/src/reactions.ts` and the iOS and Android equivalents), Web Push bodies
  (`push/payload.rs`) and media blobs are unpadded, so sizes reveal text length, emoji count and
  message type.
  - Change: pad to fixed buckets (for example 256 B steps for text, powers of two for media)
    before encrypting; pad push bodies to one size.
  - Done when: two messages of different lengths in the same bucket store the same
    `octet_length(ciphertext)`; old clients still read new messages.
- [ ] **2.2 Delete what isn't needed any more.** These rows are kept forever today:
  - `conversations` rows, even after an account is deleted
  - `calls`
  - rejected and cancelled `contact_requests` (`routes/contacts.rs`)
  - message tombstones and their `message_deliveries` and `message_reads`
  - `conversation_clears`
  - sessions of removed devices (`auth/session.rs`)

  - Change: a background sweep with set retention periods (for example calls 30 days, resolved
    requests 7 days, deliveries once read); account deletion removes the user's conversation
    rows and tombstones.
  - Done when: after the sweep, a deleted account's id appears in no table except `users`
    (placeholder) and `devices`/`sessions` rows that a later sweep also removes.
- [ ] **2.3 Make hidden presence actually hidden.**
  - What leaks today:
    - delivery receipts are always sent (`routes/messages.rs`, `message.delivered`)
    - `devices.last_seen_at` is written on every connect and disconnect (`routes/presence.rs`)
    - key fetches return devices sorted by it (`routes/keys.rs`)
  - Change: when either side hides presence, hold delivered receipts back or batch them; sort key
    bundles by device id; write `last_seen_at` only when presence is shared, and coarsely.
  - Done when: with presence hidden, a contact who polls every API they can reach can't tell
    when the user was last online to better than a day.
- [ ] **2.4 Hide device ids from other people.** `sender_device_id` on messages, read and
  delivered events, typing, and `from_device_id` on call signals let a contact count someone's
  devices and notice new ones.
  - Change: send peers an opaque per-conversation device handle instead of the device UUID
    (the ratchet only needs a stable handle per peer device).
  - Done when: two contacts of the same user see different handles for the same device.
- [ ] **2.5 Stop `GET /users/{id}` returning the share code** (`routes/users.rs`). Anyone who
  knows the id gets the current code, so rotating it doesn't cut them off.
  - Change: return the code only to the account itself, or drop it from the response.
  - Done when: after a rotation, a non-contact who knows the id can't get the new code.
- [ ] **2.6 Coarsen timestamps that don't need precision.** Every timestamp is `now()` to the
  microsecond.
  - Change: keep message order with a sequence number; store delivered and read times to the
    minute, or delete them once read.
  - Done when: no stored delivery or read time is finer than one minute.

---

## Phase 3 — Infrastructure

- [ ] **3.1 Require auth for `/metrics` and `/health/ready`** (`routes/mod.rs`, `routes/health.rs`).
  On a small server the live counters show when people are active.
  - Done when: both return 401 without a token; the deploy's own health check still works.
- [ ] **3.2 Move push secrets out of plaintext columns.** `server_keys.secret` (VAPID private
  key) together with `web_push_subscriptions.p256dh`/`auth` lets a DB reader send fake pushes
  to every browser and Android device.
  - Change: read the VAPID key from the environment or a secrets file only; encrypt subscription
    keys at rest with a key that isn't in the DB.
  - Done when: a DB dump alone can't produce a valid Web Push request.
- [ ] **3.3 Protect the web PIN pepper.** `device_pin_guards.pepper` plus a copy of the browser
  profile allows offline PIN guessing.
  - Change: keep the pepper server-only (an HMAC the server computes on unlock) so the online
    attempt limit always applies.
  - Done when: a DB dump plus a browser profile can't verify a PIN guess offline.
- [ ] **3.4 Trust `X-Forwarded-For` only from known proxies.** `rate_limit.rs` (`client_ip`)
  uses the first entry, which the client controls, so IP limits can be dodged. With
  `TRUST_FORWARDED_HEADERS=false` every client shares the key `"unknown"`.
  - Change: take the right-most untrusted entry, given a list of trusted proxy addresses.
  - Done when: a forged `X-Forwarded-For` header doesn't change which budget a request uses.
- [x] **3.5 Let the in-memory rate limiter forget.** Without Redis, `memory_check` resets counts
  but never removes keys, so every IP and id seen stays in RAM until restart.
  - Done when: a key is gone from memory one window after its last use.
  - Verified 2026-10-03: finished windows are dropped by a sweep at most every 60 s (unit test
    `finished_windows_are_dropped_at_the_next_sweep`). The Redis-failure warning now logs the
    scope, not the key (which held IPs, user ids and username hashes).
- [x] **3.6 Never fall back to Google STUN.** Without `TURN_URLS` and `TURN_SECRET`, clients use
  Google's STUN server (`turn.rs`), so Google sees both people's IPs on each call.
  - Change: no default ICE servers; warn at startup when TURN isn't configured.
  - Done when: a call on a server without TURN contacts no third-party host.
  - Verified 2026-10-03: with nothing configured `GET /calls/ice-servers` returns no servers
    (unit test), the API warns at startup, and no client has its own STUN fallback (grep over
    web, iOS, Android). Without TURN, calls now connect only where a direct path exists.
- [ ] **3.7 Re-key legacy media.** Old blobs are stored as `{uploader_user_id}/{media_id}`
  (`media_store/mod.rs`) and copied to Nebular under the same key.
  - Change: copy them to `media/{xx}/{media_id}` and delete the old keys.
  - Done when: no object key in the bucket contains a user id.

---

## Phase 4 — Reduce only (needs a different architecture to remove)

Not tasks to tick, but kept here so nobody mistakes them for fixed:

- **The social graph.** `contacts`, `conversations` and `contact_sealed_names` say who talks to
  whom. Removing it needs sealed sender or similar.
- **Push provider tokens.** Apple and Google can tie APNs/FCM tokens to an account. Self-hosted
  UnifiedPush avoids Google on Android; nothing avoids Apple on iOS.
- **Delivery and read timing.** The server sees when each device fetches and reads, as long as
  it relays messages.
- **The rest of the network path.** The server and TURN see client IPs. Only Tor or a VPN on the
  client hides them.
