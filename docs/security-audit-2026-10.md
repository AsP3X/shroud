# Security audit, October 2026

A read-only review of the whole repository at `d4134a4` (dev): server, admin console, deployment,
web, iOS and Android. Each finding below was traced through the code from a real entry point to
its effect. Where a finding could not be run end to end here (a browser, a locked iPhone), it says
so. H1–H5 and M1 are fixed since; each says how under **Fixed**. The rest is open.

Severity is what an attacker gains, weighted by what they need first. "Server" in a client finding
means a malicious or compromised Shroud server, which the E2EE design is meant to withstand.

## Summary

| # | Severity | Area | Finding | Status |
| - | -------- | ---- | ------- | ------ |
| H1 | High | Server, nginx | Per-IP rate limits use a client-chosen IP, or one key shared by everyone | Fixed |
| H2 | High | Server | Per-account login budget is bypassed by whitespace or a legacy digest: unlimited password guessing | Fixed |
| H3 | High | Server | Registering an unmigrated user's name locks them out for good and takes the name | Fixed (lockout) |
| H4 | High | Web | A contact chooses the MIME type of a received photo; `blob:` in `script-src` can turn it into script on Shroud's origin | Fixed |
| H5 | High | Deploy, admin | The console's DB role can create a schema that the superuser API then resolves first | Fixed (path pinned) |
| M1 | Medium | Admin | A half-enrolled operator signs in with a password that is in the source | Fixed |
| M2 | Medium | Admin | Anyone can lock every operator out; parallel sign-ins can exhaust host memory | Open |
| M3 | Medium | Server | Argon2 runs on Tokio worker threads: a login flood stalls the whole API | Open |
| M4 | Medium | Server | No media quota: one account stores ~120 GiB/min permanently; scratch files leak | Open |
| M5 | Medium | Server | WebSocket frames have no size or rate limit; focus toggles amplify into presence fan-out | Open |
| M6 | Medium | Server | A link-relay pipe outlives its lifetime and idle limits | Open |
| M7 | Medium | Web (check iOS/Android) | History accepts messages from people outside the chat (server) | Open |
| M8 | Medium | iOS | A locked phone trusts any identity key the server sends (server) | Open |
| M9 | Medium | Web, iOS, Android | The contact username book is stored unsealed (iOS: also in backups) | Open |
| M10 | Medium | Deploy | The operator console shares an origin and cookie path with the messenger | Open |
| L1–L19 | Low | various | See [Low](#low) | Open |
| — | Deps | Rust, npm | `h2`, `rustls`, `rustls-webpki`, `webpki-roots` advisories; Vite/sharp in the web toolchain | Open |

## High

### H1. Per-IP rate limits use a client-chosen IP, or one key for everyone

`server/crates/shroud-server/src/rate_limit.rs:368-392` (`client_ip`), `web/api-proxy.conf:4`,
`server/crates/shroud-server/src/lib.rs:320,342`.

- With `TRUST_FORWARDED_HEADERS=true` (set in `docker-compose.yml:156,285`) the API takes the
  **left-most** `X-Forwarded-For` entry. nginx forwards `$proxy_add_x_forwarded_for`, which keeps
  whatever the client sent and appends to it. A request with `X-Forwarded-For: 203.0.113.<n>`
  is a new client every time, for every IP budget: login, register, username lookup, PIN guard,
  WebSocket connects, key fetches and the link relay.
- With it `false` (the default outside Compose), `axum::serve` runs without `ConnectInfo`, so every
  client is `"unknown"`. Ten `POST /auth/login` a minute lock everyone out of login and register.
- `docker-compose.local.yml` / `host-ports.yml` publish the API port directly, where the header
  should not be trusted at all.

**Fix:** in nginx, `proxy_set_header X-Forwarded-For $remote_addr;` (after `real_ip` it is the real
client). In the API, serve with `into_make_service_with_connect_info::<SocketAddr>()`, key on the
peer address when not trusted, and when trusted take `X-Real-IP` from our own nginx or the right-most
untrusted hop. Tracked as open item 3.4 in `docs/anonymity-plan.md`; it is live today.

**Fixed:** the API serves with connect info, and the `ClientIp` extractor (`rate_limit.rs`) keys IP
budgets on the peer address. Forwarded headers count only with `TRUST_FORWARDED_HEADERS=true` and
a peer on a loopback or private address (the proxy); from it, `X-Real-IP`, else the right-most
`X-Forwarded-For` hop. `web/api-proxy.conf` sends `X-Forwarded-For $remote_addr`. Tests: unit tests
`client_ip_*`; `forged_forwarded_hops_do_not_pick_the_ip_budget` (fails before the fix). A proxy on
another public host is now treated as one client; deployments like that need a trusted-proxy list.

### H2. The per-account login budget is bypassed: unlimited online password guessing

`server/crates/shroud-server/src/routes/auth.rs:118-121` (register), `:239-242` (login),
`auth/username.rs:64`.

The `auth_user` budget is keyed on the raw `body.username_hash` string, but `parse_username_hash`
trims it first. `"<digest>"`, `" <digest>"`, `"  <digest>"`… are the same account with a fresh
5-per-minute budget each. For accounts still on the SHA-256 digest, a random `username_hash` plus
`legacy_username_hash = SHA256(name)` has no per-account budget at all (the key is the random
value, on purpose per the comment at `:231-232`). With H1, guessing is bounded only by Argon2
throughput, and the password policy is 8+ characters.

The same raw string becomes a Redis key (`rl:auth_user:{raw}`): ~2 MB of leading spaces makes a
2 MB key with a 60 s TTL, and Redis in Compose has no `maxmemory`.

**Fix:** key the budget on the decoded 32 bytes (hex). After the lookup, also count against the
matched user id, including a legacy match. Cap the field length before parsing; set Redis
`maxmemory` with `volatile-ttl`.

**Fixed:** register and login key `auth_user` on the decoded digest (`limit_account_attempts`), so
the key has a fixed length; login also counts the legacy digest against its own budget. Tests:
`login_budget_counts_one_account_however_its_digest_is_written` and
`login_budget_counts_the_legacy_digest` (both fail before the fix). Redis `maxmemory` is not set
(open, with the other denial-of-service items).

### H3. Registering an unmigrated user's name locks them out for good

`server/crates/shroud-server/src/routes/auth.rs:244-260` (login), register's uniqueness check
(`users_username_hash_uidx`), migration 029.

Login looks up the Argon2 digest and falls back to `legacy_username_hash` only when nothing
matches. Register checks uniqueness only among Argon2 digests; unmigrated rows still hold SHA-256.
Anyone who knows a victim's name (contacts see it) computes `argon2id(salt, name)` from
`GET /auth/username-kdf` and registers it. From then on the victim's login finds the attacker's row,
the password fails, and the legacy row is never tried. The account is unusable and the name taken.

**Fix:** when the password fails on the primary row and a legacy digest was sent, try the legacy row
too. Have register also send the SHA-256 digest and refuse it while an unmigrated row holds it.

**Fixed (the lockout):** login tries the new digest's row, then the legacy row when the password
does not open the first. The move to the new digest is skipped while another row holds it (checked
in the `UPDATE`, since a unique violation would abort the transaction), so the owner keeps signing
in through the legacy digest. Test:
`registering_the_new_digest_of_an_unmigrated_name_does_not_lock_its_owner_out` (fails before the
fix). **Open:** the squatter keeps the new digest. Usernames are not looked up by hash (contacts
are found by share code), so it gains no one's messages, but the owner's account never migrates.
Register cannot refuse it server-side: the server cannot tell that two digests name the same user.

### H4. A contact chooses the MIME type of a received photo

`web/src/messaging.ts:389,436`, `web/src/media/images.ts:119`, `web/src/components/ImageViewer.tsx:436`,
`web/security-headers.conf:3`.

`payload.mime` from the sealed payload goes unchecked into `blobOf(bytes, message.mime)`. A contact
sends a "photo" with `mime: "text/html"` and an HTML/JS polyglot body. The image viewer does not
block the browser's context menu; "Open image in new tab" loads `blob:https://<shroud>/…` as an HTML
document on Shroud's origin. That document inherits the page CSP, whose `script-src` allows
`blob:`, so `<script src="#">` loads the blob itself as a classic script (`text/html` is not on the
list browsers refuse to run). It can then read localStorage and IndexedDB and draw a fake PIN
screen on the real origin.

Not reproduced in a browser in this review; the code path and the CSP are confirmed. The fix is
cheap either way.

**Fix:** allow only `image/(jpeg|png|gif|webp|heic|heif|avif)` and fall back to `image/jpeg`, here
and for link-preview pictures. Drop `blob:` from `script-src` unless something needs it
(`worker-src` already covers blob workers). Block the context menu in the viewer as well.

**Fixed:** received photos and link pictures keep only a raster image type (`receivedPictureMime`,
else `image/jpeg`); voice notes and videos only `audio/…`/`video/…` in printable ASCII
(`receivedPlayableMime`), in `web/src/crypto/mediaPayload.ts`; cases in `file.selftest.ts`. Pages
no longer allow `blob:` scripts. The Whisper worker still needs them (transformers.js imports the
ONNX runtime's factory from a `blob:` URL), and a worker applies the CSP of its own script, so
`nginx-http-map.conf` keeps `blob:` for `/assets/*.js|mjs` only. Checked in Chromium behind this
nginx config: with the old CSP the polyglot ran script in its tab; with the new one it does not,
and a worker under `/assets/` still imports a `blob:` module. The context menu is not blocked.

### H5. The console's DB role can create a schema that the superuser API resolves first

`server/docker/init-admin-role.sh:24`, `scripts/compose-env.sh:671`, `docker-compose.yml:36,153`.

Both scripts `GRANT CONNECT, CREATE ON DATABASE … TO shroud_admin`. The API connects as
`POSTGRES_USER`, which the official image makes a superuser. The API never sets `search_path`, and
its queries name tables without a schema. Whoever holds the console's DB login (an RCE in the
console, a leaked `ADMIN_DATABASE_URL`) runs `CREATE SCHEMA shroud`. Through `"$user"` in the
default search path, that schema now comes before `public` for the API. A `shroud.sessions` table
with a trigger then runs as superuser on the API's next insert (CVE-2018-1058's pattern). This
defeats the column grants that keep message text and password and username hashes from the
console (README).

**Fix:** create the `admin` schema owned by `shroud_admin` and grant only `CONNECT` on the
database. Run the API as a non-superuser owner role. Pin `ALTER ROLE <api role> SET search_path =
public`.

**Fixed (the path):** the API connects with `search_path=public` (`lib.rs`), and the three role
scripts (`init-admin-role.sh`, `compose-env.sh`, `deploy.ps1`) run `ALTER ROLE CURRENT_USER SET
search_path = public`, which also covers `grants.sql` and manual `psql` as that role. Reproduced on
Postgres 16: a `shroud.probe` table made by `shroud_admin` shadowed `public.probe` for a new
superuser session before, and not after. `shroud_admin` keeps `CREATE` on the database: Postgres
checks it before `IF NOT EXISTS`, so the console's migration 001 (`CREATE SCHEMA IF NOT EXISTS
admin`, checksummed) would fail without it. Trusted extensions need `CREATE` on the target schema,
so they stay in schemas it owns. **Open:** the API still runs as a superuser.

## Medium

### M1. A half-enrolled operator signs in with a password that is in the source

`admin/api/src/auth.rs:405-419,841-847`, `admin/api/src/password.rs:47`, `setup_get` at `auth.rs:602`.

`check_password` substitutes `dummy_hash()` when `password_hash` is NULL, and that is a real hash of
`"not-a-real-operator-password"`; its result counts as `password_ok`. Opening a setup link stores the
TOTP secret before any password exists. An invitee who scans the QR code and stops, or a link that
expires, leaves an operator with a secret and no password. Whoever holds that secret (the inviter,
whoever saw the link, the person who scanned) signs in with the public password and a code.

**Fix:** `password_ok = hash.is_some() && verify(..)` (still run the dummy verify for timing). Store
the secret on setup completion, or clear it when the link expires.

**Fixed:** `check_password` still runs the dummy hash for timing, but never answers yes without a
stored hash. `tests/session.rs` signs in with the dummy password and a valid code after the setup
link is opened: 204 before the fix, 401 after. The secret is still stored when the link is opened.

### M2. Anyone can lock operators out, and sign-in can exhaust memory

`admin/api/src/limit.rs:23-44`, `admin/api/src/auth.rs:402-418`.

- The limiter is keyed by operator name only (default `operator`): ten bad attempts in 15 minutes
  lock out the real operator, recovery path included.
- At `MAX_KEYS` (4096) every new name is refused, and entries are pruned only when the same name is
  tried again: 4096 random names lock out every operator until a restart.
- Argon2 (19 MiB) runs before the limit check with no concurrency cap. ~500 parallel requests fill
  Tokio's blocking pool, ~10 GB of RAM; the admin container has no memory limit.

**Fix:** key on IP + name (pass a trusted `X-Real-IP` from nginx), evict expired entries globally,
put a semaphore around Argon2, `limit_req` on `/api/admin/session` in nginx, and a memory limit.

### M3. Argon2 runs on Tokio worker threads

`server/crates/shroud-server/src/auth/password.rs:74,85`, called from `routes/auth.rs:123,251,256,262,665,935`.

Argon2 (19 MiB, t=2) is called directly in async handlers. A miss costs two runs
(`delay_unknown_user`, `:397-400`). With one worker per core, a modest flood of failed logins (cheap
with H1/H2) blocks every worker: WebSockets, sends and health checks stall.

**Fix:** run every Argon2 call in `spawn_blocking`, behind a semaphore that answers 503 when full.
Compute the dummy hash once (`OnceLock`) and only verify against it; that also removes the timing
difference that shows whether an account exists (a miss is ~2× a real wrong password today).

### M4. No media quota; scratch files leak

`server/crates/shroud-server/src/routes/media.rs:33,74,147`, `media_store/mod.rs:246-255,496`,
`media_store/local.rs:68`.

Registration is open. `POST /media/uploads` with 2 GiB (60/min), `PUT /media/{id}/content`, then
`POST /messages` to yourself (Saved Messages skips the contact gate). Linked media has no retention,
so that is ~120 GiB/min per account, permanent. Parallel PUTs to one id each spool their own scratch
file, and `remove_stale_scratch` runs only at startup.

**Fix:** a per-user byte quota (sum of live `size_bytes`) checked in `create_upload`; a per-user PUT
concurrency cap; a request timeout on the content route; a periodic scratch sweep.

### M5. WebSocket frames have no size or rate limit

`server/crates/shroud-server/src/routes/ws.rs:275,375,414`, `realtime/mod.rs:80,872`,
`routes/link_relay.rs:123,155-159`.

No `max_message_size` is set on `/ws` or `/link-relay`, so axum's 64 MiB default applies, before
authentication too. On `/ws`, a loop of `focus` true/false flips `counts_online` each time; each
flip updates `devices.last_seen_at`, writes Redis, runs 2–3 SELECTs and publishes a
`presence.update` to every contact. Their outbound queues (256) fill, and `try_send` silently drops
their real `message.new` and `call.ring` events. `typing` does the same to one contact.

**Fix:** `max_message_size`/`max_frame_size` of a few KiB on `/ws` and 64 KiB on the relay; a
per-connection token bucket; at most one presence announcement per user every few seconds.

### M6. A link-relay pipe outlives its lifetime and idle limits

`server/crates/shroud-server/src/routes/link_relay.rs:253-285`.

`timeout_at` wraps only the `select!`. `to_site.write_all(&data)` and `sink.send(..)` run outside
any deadline. A host that accepts on 443 and never reads (zero window) blocks `write_all` forever
after one 64 KiB frame; the browser side can then disconnect and the task, the socket and the
account's pipe slot stay. A client that reads slowly does the same through `sink.send` (nginx
allows 3600 s). With H1 and free accounts this leaks descriptors and memory without bound.

**Fix:** put both writes under `timeout_at(idle_deadline, …)` and end the pipe on timeout.

### M7. History accepts messages from people outside the chat (malicious server)

`web/src/messaging.ts:328,553-556,811`; the reaction path does it right (`web/src/reactions.ts:214-222`).

`decodeIncoming` opens each listed message with the key of `dto.sender_user_id`, and nothing checks
that this sender is me or the chat's peer. The server creates account X, has X seal a correctly
tagged message to the victim, and lists it in the victim's chat with Bob; the client trusts X's key
on first use, the tag verifies, and it shows as Bob's. Related: the `self` box does not name the
recipient, so the server can file my message to Alice under Bob on my other devices.

**Fix:** treat any message not from me whose sender is not `peerUserId` as failed. Later, seal the
recipient's id into the plaintext and check it on open. **The iOS and Android decrypt paths were not
checked for this specific gap and should be.**

**Fixed (web):** `decodeIncoming` fails a message whose sender is neither me nor the chat's peer
before it reads the plaintext cache or asks for a key (`sentInChat` in `web/src/messaging.ts`), so
the stranger's key is never fetched or pinned. The row shows as one that does not open ("[Unable to
decrypt]", also for a tombstone) and is not acked. The latest page, older pages and Notes all decode
there; realtime `message.new` files someone else's message under its sender's chat, so it is never
shown as the peer's. The chat list's `hydratePreviews` skips the cache for such a row, and for
shared transcripts from outside the chat. Search and notifications read the decoded rows. Cases in
`messaging.sender.selftest.ts` (a cached and an uncached stranger's message, a stranger's tombstone,
Notes, the chat list) fail on the code before. Sealing the recipient's id into the plaintext is
still open, as are iOS and Android.

**Fixed (Android):** Android had the same gap. A history page (`HistoryPager.fetchHistoryPage`) and
a push's preview (`MessagingController.notificationText`) opened each listed message with the
pinned (else fetched and pinned) key of its `sender_user_id`, then filed it under the page's chat;
`MediaHydrator.payloadData` re-opened a listed media message the same way. `MessageDecoder.decode`
now takes the chat's peer (our own id for Notes). A message that is neither ours nor from that peer
becomes the existing `[Unable to decrypt]` text bubble with no media: it is not opened, no held
copy or cached plaintext is used, and its sender's key is never fetched. The media recovery returns
nothing for a listed message whose sender is not the chat's peer and the bubble's sender. A live
`message.new` was already safe: a peer's message is filed under its own sender, never under another
chat. Tests: `MessageDecoderTest` (`aMessageFromOutsideTheChatIsNeverOpened` and three more),
`MediaHydratorTest.aRecoveredMessageFromAnotherSenderIsNeverOpened`. **Not compiled or run** (no
Android SDK reachable). **Open:** the `self` box still does not name the recipient.

### M8. A locked iPhone trusts any identity key the server sends (malicious server)

`ios/shroud/Services/API/PeerIdentityStore.swift:21-34,86-97`,
`ios/shroud/Services/Messaging/MessagingController.swift` (`resolvePeerIdentityPublicKey`,
`verifyPeerIdentity`).

`readKeychain` returns `nil` for every status that is not `errSecSuccess`, including
`errSecInteractionNotAllowed`, which the `WhenUnlockedThisDeviceOnly` pin returns while the phone is
locked. `resolvePeerIdentityPublicKey` reads that as "no pin yet", trusts and saves what
`GET /keys/identity` returns; `verifyPeerIdentity` sees no cached key and raises no
`identityChanges` warning. `writeKeychain` deletes the old pin before adding the new one. Whenever
the app runs while locked (a call, background audio, an Auto-Lock delay), the server can substitute
a key, have a message accepted as the contact's, and derive a call secret it knows into
`CallSecretStore` (`AfterFirstUnlock`). Android handles this (`PinRead.Unavailable`,
`CryptoError.Locked`). Not run on a device in this review.

**Fix:** make `readKeychain` three-way (found / `errSecItemNotFound` / unavailable) and throw a
"locked" error instead of trusting on first use; use `SecItemUpdate` rather than delete-then-add;
skip `refreshCallSecrets` while `!isProtectedDataAvailable`.

**Fixed (iOS):** `PeerIdentityStore.pin(for:)` answers `.pinned`, `.notPinned` (only on
`errSecItemNotFound`) or `.unavailable` (every other status), as Android's `PinRead` does; the
legacy `UserDefaults` copy is still migrated, and now deleted only once the Keychain holds it.
`resolvePeerIdentityPublicKey` throws `PeerIdentityError.unavailable` on an unreadable pin, before
and after the first-use fetch, so nothing is trusted or saved then. `verifyPeerIdentity` decides
nothing on an unreadable pin and checks again on the next use. Writes update the item in place
(`SecItemUpdate`, `SecItemAdd` only on `errSecItemNotFound`). `refreshCallSecrets` returns while
`isProtectedDataAvailable` is false, and `callSecret(for:)` reports an unreadable pin as
`CallSecretError.chatsLocked`; reactions treat it as transient, so catch-up comes back for them.
`PeerIdentityStoreTests` covers the status mapping, the update and the migration. Not compiled,
run or tried on a locked device in this review (no Xcode here).

### M9. The contact username book is stored unsealed

`web/src/contactNames.ts:15-37` (localStorage), `ios/shroud/Services/Contacts/ContactNames.swift:74-83`
(UserDefaults, so in iCloud and Finder backups), `android/.../core/contacts/ContactNames.kt:22-72`
(plain `shroud.preferences`, excluded from backup).

The map of user id → username is the one thing the server deliberately does not have. Anyone with a
stolen laptop profile or an iCloud backup gets the whole contact list without a PIN or the phrase.
`docs/web-client.md` promises nothing account-related is readable without the PIN.

**Fix:** web: `vaultSet`/`vaultGet` and migrate in `sealLegacyStorage`. iOS: seal under the history
key in an Application Support file marked `isExcludedFromBackup`. Android: `SealedFile`.

**Fixed (web):** `web/src/contactNames.ts` reads and writes both maps, the name book and the record
of names published to each contact, through `vaultGet`/`vaultSet` under their old storage names, so
the wipe and the orphan sweep still find them by prefix. Locked, they read as empty and nothing is
written; the chat shell only runs with the vault open. `sealLegacyStorage` seals plaintext maps of
the unlocking account in place on the next unlock. The public API is unchanged. Cases in
`crypto/vault.selftest.ts` (a pre-vault book and record are sealed on the first unlock and still
read; locked, nothing reads or is written) fail on the code before; `deviceWipe.selftest.ts` checks
that a sealed book without a session is swept.

**Fixed (Android):** `ContactNameBook` keeps the names and the published-name fingerprints in
`contact-names.sealed` in no-backup storage, sealed by its own AFU Keystore key
`shroud.contact-names.v1`, as `NotificationNameCache` is. Its first read (preloaded at process
start) moves an older build's `contact.names.*` keys out of `shroud.preferences` and deletes them
once the record holding them is written. A record that cannot be read now (phone locked before the
first unlock, a Keystore error) is never written over: names learnt meanwhile stay in memory and
are merged once it reads. Log Out and removal delete the file and the key (the wipe's sweep of
sealed records and aliases), and `ContactsController.stop(wipe = true)` also drops the copy in
memory. Tests: `ContactNameBookTest`, `ContactsControllerTest.onlyAWipeForgetsTheNameBook`.
**Not compiled or run:** no Android SDK or Google Maven could be reached from the machine this was
written on.

**Fixed (iOS):** `ContactNames` keeps both maps (names and published fingerprints) in
`Application Support/shroud/contacts/names.sealed`, sealed under the history key
(`LocalHistoryCrypto` context `contactNames`) and marked `isExcludedFromBackup`, the way
`TranscriptionLanguageMemory` keeps the voice language file. `SealedLocalState` opens it when the
chats unlock and forgets it when they lock; locked, every name reads as unknown and writes drop.
Unlocking folds the `shroud.contact-names.*` UserDefaults keys into the file and deletes them
once it is written. Log Out's settings step deletes the file by name (the container sweep would
too). While the chats are locked a ringing call can no longer read a name from the book, so the
placeholder no longer replaces the name the VoIP push brought (`CallController.applyCallerName`).
`ContactNamesTests` and `DeviceDataWipeTests` cover the seal, the lock, the migration and the
wipe. Not compiled or run in this review (no Xcode here). Web and Android are still open.

### M10. The operator console shares the messenger's origin

`web/nginx.conf.template:54-68`, admin cookie `Path=/` (`admin/api/src/auth.rs:1122-1129`).

Any XSS in the messenger (H4 is one candidate) running in an operator's browser can call
`/api/admin/*` with the operator's cookie and read the JS-readable CSRF cookie; inside the 5-minute
re-auth window it can sign users out and delete accounts. In reverse, a console XSS reaches the
operator's E2EE vault in IndexedDB.

**Fix:** serve the console on its own origin (`admin.<host>`). At minimum, `Path=/api/admin` on its
cookies.

## Low

| # | Area | Finding | Fix |
| - | ---- | ------- | --- |
| L1 | Admin | TOTP accepts ±1 step and never records the last one: a code replays for 60–90 s, and the sign-in code also passes the "fresh code" step-up (`admin/api/src/totp.rs:28-47`) | Store `last_totp_step` per operator; reject a step at or below it |
| L2 | Admin | Console CSP has no `frame-ancestors`; `admin-proxy.conf:12`'s own `add_header` drops the site's `X-Frame-Options` and HSTS (`admin/api/src/lib.rs:50-51`) | Add `frame-ancestors 'none'`; include `security-headers.conf` |
| L3 | Admin | The audit log is owned by `shroud_admin`, so a compromised console can edit it; "cannot be edited" is true of the UI only | A separate owner role; grant `INSERT, SELECT` only |
| L4 | Server | Sessions never expire (no `expires_at`, no idle check in `auth/session.rs:204-217`); any session can remove the account's other devices without a password | Idle timeout on `last_used_at` (e.g. 90 days); re-auth for device removal |
| L5 | Server | The operator listener binds `config.host` (0.0.0.0) and accepts a token of any length, with no rate limit (`lib.rs:308`, `config.rs:255-283`). Not published in Compose; a risk on bare metal | Minimum 32 characters; bind 127.0.0.1 by default |
| L6 | Server | Block racing a contact accept can leave both a block and a contact row; presence and typing then still flow, since they check only `are_contacts` (`routes/blocks.rs:63-98`, `contacts.rs:423`) | Lock the pair's request rows in the block; add `is_blocked_either_way` to presence/typing gates |
| L7 | Server | `POST /messages/{id}/read` skips the contact/block gate; `mark_read_bulk` checks contacts but not blocks (`routes/messages.rs:592,651`) | Same gate as `send_read_receipts` |
| L8 | Server | A block does not end live calls; `accept_call`/`signal_call` don't re-check blocks (`routes/calls.rs:417`) | End the pair's calls in `create_block`; re-check on accept and signal |
| L9 | Server, coturn | `GET /calls/ice-servers` has no budget; each call mints a 12 h TURN login not tied to a call or user; coturn has no `--user-quota`/`--total-quota`/`--max-bps`. `docs/calls.md:591` describes the old username format | Budget the endpoint; short TTL per live call; coturn quotas; fix the doc |
| L10 | Server | A PUT started before the media is linked can rename over the blob after linking; a PUT racing deletion publishes a blob no row names (`routes/media.rs:159`) | Re-check `message_id IS NULL` and the row under `FOR UPDATE` just before publishing; delete if gone |
| L11 | Server | A known APNs token or UnifiedPush endpoint can be moved silently to another account's device (`routes/push.rs:92-100,240-245`) | Only release a token within the same user, or prove possession |
| L12 | Server | `is_globally_routable_v6` misses `::/96`, `64:ff9b:1::/48`, `fec0::/10` (`src/link_relay.rs:143-165`); the clients miss `64:ff9b::/96`, `2002::/16`, `fec0::/10`. Not reachable in the default Docker network | Add the ranges |
| L13 | Server | With `UNIFIEDPUSH_PUBLIC_HOSTS=true`, an attacker endpoint's 401/403 logs a VAPID error with 200 chars of its body; the per-audience VAPID cache is never evicted (`web_push.rs:348,557-561,748-759`) | Treat 401/403 as config errors only for listed hosts; escape the body; bound the cache |
| L14 | Web | Link-preview card shows the sender-supplied `siteName` instead of the real host, and `lp.u` need not be a link in the text (`components/LinkPreviewCard.tsx:64`) | Always show `displayHost`; require `lp.u` to be one of the text's links |
| L15 | iOS | The encryption phrase is copied to the general pasteboard without `.localOnly`, so Universal Clipboard syncs it (`EncryptionPhrasePasteboard.swift:17-41`) | `.localOnly`, or a write-it-down flow |
| L16 | iOS | The Whisper model is fetched from the repo's default branch without a revision or hash (`WhisperKitEngine.swift:27-33`); Android pins both | Pin a revision and check file hashes |
| L17 | iOS | The link-preview fetcher resolves twice (rebinding window) and honours the system proxy (`LinkPreviewFetcher.swift:88-103,251-275`); HTTPS:443 only, so a connect probe at most | `connectionProxyDictionary = [:]`; connect to the checked address |
| L18 | Deploy, CI | API container has no `read_only`/`cap_drop` (admin and nebular do); `POSTGRES_PASSWORD`/`REDIS_PASSWORD` fall back to `shroud` without `.env`; CI pins third-party actions by tag, has no `permissions:` block, and does not test `admin/` or `web/` | Harden like the admin service; fail without `.env`; pin by SHA; `permissions: contents: read` |
| L19 | Deploy | `SELECT set_config('shroud.admin_password', :'pwd', false)` prints the console's database password to stdout in `init-admin-role.sh` (Postgres init logs), `compose-env.sh` and `deploy.ps1` | Send that row nowhere (`\g /dev/null`), or pass the password without a query that returns it |

## Design notes (no change asked for here)

- **Forward secrecy.** Every v3 message also carries a `peer` identity box, and a failed ratchet
  decrypt falls back to it (`web/src/crypto/messageCrypto.ts:216-222`). The long-term identity key,
  which comes from the phrase, opens every stored message. The docs should say the ratchet gives no
  forward secrecy against that key while the fallback exists.
- **Replay.** The server can replay an old envelope under a new id; the identity box still opens.
  Documented in `docs/architecture.md`.
- **Call DTLS fingerprint check fails open** when the fingerprint is not yet available
  (`CallController.swift:1188-1210`, `CallController.kt:1021-1035`). WebRTC enforces the sealed
  fingerprint itself, so this is defence in depth.
- **Legacy iOS builds without `payload_key`** send conversation and message ids to APNs in clear
  (`push/payload.rs:183-188`); retire them with `IOS_MIN_VERSION`.
- **Plain HTTP to an unqualified host name** is allowed on both clients; a DNS search suffix can
  turn it into a public host. Consider IP literals, `.local` and `localhost` only.
- **No `Cross-Origin-Opener-Policy`** header on the web client.

## Dependencies

`cargo audit` (RustSec DB, 2026-10-26):

| Lockfile | Applies | Fix |
| -------- | ------- | --- |
| `server/Cargo.lock` | `h2` 0.4.15 (RUSTSEC-2026-0258), `rustls` 0.23.42 (-0285, -0346, -0348, -0349), `rustls-webpki` 0.103.13 (-0382), `webpki-roots` 1.0.8 (-0381) | `cargo update -p h2 -p rustls -p rustls-webpki -p webpki-roots` |
| `web/tls/Cargo.lock` | `rustls` 0.23.45 (-0346, -0347, -0348, -0349), `rustls-webpki` 0.103.15 (-0382), `webpki-roots` 1.0.9 (-0381) | Same, then `npm run build:tls`. This is the link-preview TLS client, so the webpki fixes matter |
| `admin/api/Cargo.lock` | `tokio` 1.53.2 (RUSTSEC-2026-0386, `AsyncFd` I/O safety) | `cargo update -p tokio` |
| both server lockfiles | `rsa` (Marvin, no fix) and `quinn-proto` | In the lockfile only; not in the compiled tree (`cargo tree -i` is empty). No action |

`npm audit`: `web/` has `vite` 7.3.6 (dev-server advisories), `source-map-js` 1.2.1 (build-time
DoS) and `sharp` 0.35.4 (via `@huggingface/transformers`, Node-only). None ships in the bundle;
`npm audit fix` clears them. `admin/ui/` is clean.

## Checked and found sound

So the next review does not repeat it:

- **Server auth.** Session tokens are 32 bytes from `OsRng`, stored as SHA-256, one per device.
  Argon2id with OWASP parameters; generic `INVALID_CREDENTIALS`. Logout, password change, device
  removal and deletion revoke sessions and close sockets. Account deletion runs in one transaction.
  The operator token is compared in constant time.
- **Authorization.** No IDOR found in messages, conversations, reactions, read anchors, delivered
  acks, calls, contact requests, devices, mutes or media. Media object keys are server-generated,
  `validate_key` blocks traversal, downloads are participant-gated with `nosniff`/`no-store`. Key
  uploads are bound to the caller's device; bundles are contact-gated; prekeys are consumed with
  `FOR UPDATE SKIP LOCKED`.
- **WebSocket.** The token goes in the first frame, never the URL; sessions are re-checked every
  30 s; call signals go to the exact device, including across Redis.
- **Outbound.** Web Push/UnifiedPush endpoints are allowlisted on the parsed URL, https only, no
  port or userinfo, no IP literals; public-host mode checks addresses in the client's own resolver
  with redirects off. The link relay resolves once, pins public addresses, port 443 only, caps
  bytes, never logs the host. No `danger_accept_invalid_certs` anywhere; timeouts on every client.
- **Push payloads.** No sender name or text; web payloads RFC 8291-encrypted; APNs `thread-id`,
  `collapse-id` and web `Topic` are HMAC-keyed per device.
- **Admin.** Argon2id; `__Host-` cookie with `HttpOnly`, `Secure`, `SameSite=Strict`; CSRF token
  checked against the DB; every write needs the write role, a fresh re-auth and logs to the audit
  table; setup links are single use under `FOR UPDATE`; recovery codes 80 bits, hashed, burned
  atomically; the image is distroless, non-root, read-only, capabilities dropped; column grants
  match the README (H5 is the way around them).
- **Web.** No `innerHTML`/`dangerouslySetInnerHTML`; links limited to http/https/mailto; pdf.js with
  no eval path, scripting and XFA off; strict CSP otherwise; vault key wrapped by PBKDF2 (600k) with a
  server-held pepper; non-extractable keys; sender tags checked in constant time on every box; the
  ratchet decrypts on a clone and caps skipped keys at 64; the service worker caches nothing.
- **Android.** Backups off; no user CAs; no custom TrustManager; minimal, checked exported
  components; every PendingIntent immutable; FileProviders not exported; no WebView; Keystore-sealed
  secrets; no secrets in logs.
- **iOS.** ATS local networking only; no TLS overrides; no WKWebView; correct Keychain classes for
  token, identity and ratchet; sealed caches excluded from backup; the notification extension never
  receives message text.
