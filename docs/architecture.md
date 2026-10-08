# Shroud architecture

High-level structure for the E2E encrypted messenger.

| Doc | Role |
| --- | --- |
| **[server-plan.md](./server-plan.md)** | Server decisions, milestones, locked Auth API |
| [thought-collection.md](../thought-collection.md) | Calls, WebRTC, Compose scaling notes |
| [file-sharing.md](./file-sharing.md) | Files: the `t:"file"` payload, SHRF1 blobs, supported types, name rules, warnings, UI |
| **[android-plan.md](./android-plan.md)** | Android client: open decisions and workstreams (design in `design/Android-App.pen`; foundation, sign-up and log-in in `android/`) |

## Components

```text
┌─────────────────┐     ciphertext envelopes + media refs     ┌─────────────────┐
│   iOS app       │  ───────────────────────────────────────► │  Rust server    │
│  Swift/SwiftUI  │  ◄─────────────────────────────────────── │  Axum           │
│  CryptoKit      │     delivery metadata only (no plaintext) │                 │
└────────┬────────┘                                           └────────┬────────┘
         │                       ┌─────────────────┐                   │
         │                       │  Web client     │  same-origin      │
         │                       │  Vite/React     │  /api/v1 via nginx│
         │                       │  WebCrypto      │ ──────────────────┤
         ▼                       └────────┬────────┘                   │
  Keychain / Secure Enclave               ▼              ┌─────────────┼─────────────┐
  (identity keys, encryption phrase   IndexedDB sealed   ▼             ▼             ▼
   — never on wire)                   + PIN / idle lock Postgres     Redis      Nebular OS
                                                      (durable)    (fan-out,    (ciphertext
                                                                    limits,       blobs)
                                                                    presence)
```

**coturn** (Compose profile) supplies TURN when P2P fails. Call media does not flow through the Rust API. App media ciphertext lives in **Nebular OS** when `NEBULAR_URL` is set (Compose sets it), so every API replica sees the same blobs; otherwise in files under `MEDIA_DATA_DIR`. Clients always use `/media/{id}/content`, where the API checks access and streams the bytes: clients never reach Nebular, and Nebular never sees a client address or token. The API talks to Nebular with a SigV4 access key. Nebular gives that key the `editor` role on the `shroud-media` bucket only, verifies each request's body hash before storing it, and refuses requests more than 15 minutes old. Deleted media is removed at once (`NOS_SOFT_DELETE_TTL_SECS=0`); the orphan GC retries a delete the store missed. New object keys are `media/{xx}/{media id}` and never name the uploader. Blobs earlier releases kept on the local volume are moved into Nebular on start (see `media_store`). Prometheus text metrics: `GET /api/v1/metrics`.

## Repository map

| Layer | Location | Responsibility |
| --- | --- | --- |
| Design | `design/iOS-App.pen`, `design/webclient.pen` | Screen specs, tokens, components |
| iOS UI | `ios/shroud/ShroudUI/` | Reusable SwiftUI components + `Theme` |
| iOS features | `ios/shroud/Features/` | Screens (MVVM), 1:1 with design |
| iOS services | `ios/shroud/Services/` | API client, crypto, persistence |
| Android | `android/` | Kotlin + Compose app; `core/` (API, crypto, storage), `ui/` (theme, components, screens) |
| iOS extension | `ios/ShroudNotificationService/`, `ios/ShroudShared/` | Notification service extension (names the sender of a push); code it shares with the app |
| Web | `web/` | Vite + React SPA; nginx same-origin `/api/v1` |
| API | `server/crates/shroud-server/` | HTTP `/api/v1`, WebSocket, auth, relay |
| Schema | `server/migrations/postgres/` | Forward-only sqlx migrations |
| Docs | `docs/` | Architecture, server plan, protocol notes |

## API contract

- Base path: `/api/v1`
- Errors: `{ "error": { "code": string, "message": string } }` (`AppError` / Swift `APIError`)
- Auth routes and bodies: [server-plan.md — Milestone 1](./server-plan.md#milestone-1--auth-locked)

## Sealed plaintext shapes

The server only ever sees `content_type` (`text` / `media` / `annotation`) and an opaque
envelope. What the two clients agree on *inside* that envelope:

| Shape | Sealed plaintext | Written by |
| --- | --- | --- |
| Plain text | raw UTF-8, exactly as typed | every build since v1 |
| Reply | `{"t":"text","c":<body>,"re":{"id","u","k","x"}}` | `MessageReplyReference` / `web/src/reply.ts` |
| Text with link preview | `{"t":"text","c":<body>,"lp":{…}}` (plus `re` when it is also a reply) | iOS `MessageTextPayload` (read by `web/src/reply.ts`) |
| Link with a large preview image | `content_type = media`: `MediaMessagePayload` with `t:"link"`, `c` = the whole message text, `lp`, and the image as the encrypted blob | iOS `deliverLinkWithImage` (read by `web/src/crypto/mediaPayload.ts`) |
| Media | `MediaMessagePayload` JSON (`t`, `mime`, `k`, …), with the same `re` object when it is a reply | `MediaModels.swift` / `web/src/crypto/mediaPayload.ts` |
| File | `MediaMessagePayload` with `t:"file"`, the cleaned name `n`, size `s` and the key of an SHRF1 blob; read before any MIME sniffing. Audio files add `d`, `ti`, `ar` and cover art (§11) | [file-sharing.md](./file-sharing.md) |
| Annotation | `{"t":"transcript","r":<message id>,"c":<text>}` | `MessageAnnotation` |
| Reaction (not a message: `PUT /messages/{id}/reaction`) | `{"t":"reaction","r":<message id>,"e":[<emoji>, …]}` (the person's whole set, oldest first), always a tagged v2 envelope | `MessageReaction.swift` / `web/src/reactions.ts` |

`re` carries the quoted message's id (`id`), its author (`u`), its kind (`k`) and a ≤120-character
snippet (`x`) so a quote still reads when the original has aged out of the local window. Anything
that does not parse as one of these shapes is treated as plain text, which is what keeps old and
new builds interoperable in both directions.

**Reactions** are not messages. Each user has at most one sealed record per message on the server
(`message_reactions`, migration 020) — their whole set of emoji — so the server learns who reacted
to which message and when, never the emoji. They are sealed as a v2 envelope (identity
boxes only, with the sender tag), not through the Double Ratchet: a reaction is overwritten in
place, so ratchet steps would be lost, and every device must be able to open it at any time. That
costs forward secrecy for the emoji only. A reader accepts only a *tagged* v2 box, from one of the
chat's two people: every build that writes reactions tags, so an untagged one could only be the
server's invention. `r` binds the record to its message — a reader drops a reaction whose `r` is
not the message it is attached to, which stops the server moving a genuine box onto another message.

`e` holds the person's set, oldest first. How many one person may leave is a server setting
(`REACTIONS_MAX_PER_USER`, default 5, handed out by `GET /config`); clients enforce it when adding
(a pick past it drops the oldest), since the server can't count sealed emoji. A reader keeps each
single emoji of at most 32 bytes once, up to 20, and ignores anything else. Each person's emoji
share one chip under the bubble. Clients keep the highest `seq` per (message, user) and catch up per
conversation with `GET /conversations/{peer}/reactions?after_seq=` (removals come back with a null
ciphertext). Because a record is the whole set, a write names the `seq` it was built on: when
another device of the same person wrote in between, the server answers `409` with the record as it
is now, and the client re-applies what it changed (the emoji it added and the ones it took back)
onto that set and tries again — neither device's pick is lost.

What reactions do not protect against: a removal is not sealed (it is the absence of a
ciphertext), so the server can hide a reaction, or put back an older genuine one for the same
message and user. It cannot invent one, change the emoji, or move one to another message. A
modified client can leave more emoji than the limit, up to the 4 KiB record cap; readers show at
most 20. Records are not padded (messages aren't either), so a record's size hints at how many
emoji it holds. The unseen-reaction badge is server metadata of the same kind as read receipts; to
keep a taken-back emoji from lighting it, a write also tells the server whether it added one.

`lp` is a link preview (`LinkPreview.swift` / `web/src/links.ts`): `u` the page URL (http/https
only), `n` site name, `ti` title, `d` description, `th` a ≤6 KB square JPEG for the small layout,
`w`/`h` the image size, `vd` a video page (play badge), `ab` drawn above the text instead of under
it. Only the **sender's** phone contacts the website (HTTPS only; no local names, IP literals,
or names that resolve to a private address; ephemeral session, head only) and seals the result;
recipients never load anything from the link.
A picture too big for the envelope goes out as the blob of a `t:"link"` media message — the server
cannot tell it from a photo, and a build without link support shows it as a photo with the text as
its caption. Links themselves are found on each device by the same rules (`LinkDetector.swift` /
`links.ts`, shared test vectors).

**The web client builds its previews through the link relay.** A browser cannot read other
websites (CORS, and the client's own CSP), and fetching a link *for* it would hand the server the
message's plaintext. So the browser speaks TLS itself — rustls compiled to WebAssembly
(`web/tls/`, loaded on first use) — and `GET /api/v1/link-relay` (a WebSocket, `link_relay.rs`)
only moves the encrypted bytes to port 443 of one public host. Like Signal's link-preview proxy,
the relay learns which host was contacted and nothing else: not the path, the headers, or the
page, none of which it can decrypt; it logs no host. The website in turn sees the server's
address, not the user's. The relay refuses IP literals, local names, and names that resolve to a
private address (the same rules the iOS fetcher applies), connects only to the addresses it
checked, caps bytes in both directions, and holds at most six pipes per account. The preview the
browser builds is sealed into the message exactly as the iPhone's is — recipients still never
contact the website.

### Sealed device names

`DeviceNameSeal.swift` / `web/src/crypto/deviceName.ts` (golden vector shared by
`DeviceNameSealTests` and `deviceName.selftest.ts`). The server keeps a device's name only as
`devices.sealed_name`, which it cannot open:

- key: HKDF-SHA256 of the phrase's `historyKey`, salt `shroud-v1`, info `shroud-device-name-v1`,
  32 bytes. Every device of the account derives it; nobody else can.
- associated data: `shroud-device-name-v1:` + the lowercase device id, so a stored name cannot be
  moved onto another device.
- plaintext: one kind byte (1 iPhone app, 2 iPad app, 3 web browser, 4 Android app, 0 other;
  `| 0x80` when a person typed the name), the UTF-8 name (at most 96 bytes, one line, no control or bidi
  characters), `0x80`, zeros to 128 bytes. Every name
  seals to the same 156 bytes (`nonce ‖ ciphertext ‖ tag`), so its length does not show.

The iPhone app asks nothing: it uses the phone's own name (the model, such as "iPhone 16 Pro",
while iOS hands out only the generic "iPhone") and seals it after each unlock when it has changed,
unless someone renamed it, which it then keeps. A browser asks in a dialog when the app opens after
a login or sign-up, and whenever it has no name its account can read ("Not now" keeps a guess such
as "Safari on iPhone"). The Android app asks nothing either: it seals the phone's own name
(`Settings.Global.DEVICE_NAME`, otherwise the manufacturer and model) with kind 4 after an unlock
when the sealed name has changed. A name someone typed is kept, except that a custom name an older
client saved with kind 0 is sealed again with kind 4. Settings → Devices renames any device
on iOS and web. Login and register carry no name; a plaintext `device_name` from an older build is ignored.

## Message envelopes and sender authentication

`MessageCrypto.swift` / `web/src/crypto/messageCrypto.ts` + `sealedBox.ts`. The envelope is JSON
inside the server's opaque `ciphertext`:

| `v` | Contents | Sent by |
| --- | --- | --- |
| 1 | `ek`, `ct`: one box to the peer | nothing any more; refused (it never carried a sender tag) |
| 2 | `peer` box + `self` box | the higher `user_id` until a ratchet session exists |
| 3 | Double Ratchet `dh`/`n`/`pn`/`ct` + `peer` box + `self` box | everything else |

**Identity box.** `ek` = ephemeral X25519 key, key = HKDF(ECDH(ephemeral, recipient identity),
salt `shroud-v1`, info `shroud-msg-v1 ‖ ek ‖ sender IK ‖ recipient IK`), AES-GCM. That key uses no
secret of the sender's, so **anyone holding the two public identity keys — the server included —
can build a box that opens "from" the peer**. Builds before the sender tag had exactly that hole on
every v1/v2 message and on every v3 peer-box fallback (a junk ratchet body forces the fallback), and
on `self` boxes ("sent by me" on our other devices).

**Sender tag (`t`).** Every box now carries
`t = HMAC-SHA256(K, "shroud-box-tag-v1" ‖ ek ‖ ct)` with
`K = HKDF(ECDH(sender IK, recipient IK), salt "shroud-box-auth-v1", info "shroud-box-auth-v1" ‖ sender IK ‖ recipient IK)`.
Only the two identity private keys can compute the static ECDH, the ordered keys in the info stop
a box being reflected back to its sender, and `ek ‖ ct` fix the plaintext. It gives the sender
authentication of libsodium `crypto_box` (deniable in the same way: the recipient could tag too).
A box with a `t` that does not verify is refused; it is never read as untagged. The Double Ratchet
body needs no tag: its root is HKDF of the same identity ECDH.

**Why a tag and not a new `v`.** Builds before the tag throw on an unknown `v`, and nothing tells a
sender what its peer runs (the server is not involved). The tag is one extra JSON field those builds
ignore, so every build keeps reading every message. Separate `crypto_box`-style boxes next to the
old ones would have carried the plaintext four or five times and pushed media envelopes past the
server's 64 KiB cap. Folding the static ECDH into the box key as a `v:4` is possible later; it would
add nothing the tag does not already give.

**Untagged boxes are refused** (`openIdentityBox`): every v1 envelope, and every v2/v3 `peer` or
`self` box without a `t`, fails with `unauthenticatedSender` and shows as "Unable to decrypt". Only
the ratchet body of a v3 message opens without a tag. There is no transition policy left: the
per-sender watermarks (`shroud.boxauth.*` / `SenderTagStore`) and `legacyBoxCutoff` that let
untagged boxes through while older builds were around are gone; sign-out still deletes watermark
entries an earlier build left behind. The builds that cannot tag are stopped by the server's
minimum version instead (`IOS_MIN_VERSION`, see [server-plan.md](./server-plan.md#the-minimum-on-every-request)).

The cost: messages from before the tag (2026-09-23) read only from a device's plaintext cache. A
device that never opened them (a fresh sign-in, a new browser) shows them as "Unable to decrypt",
unless it reads them by ratchet.

What the tag does not cover:

- **Replays.** A genuine envelope re-sent under a new message id still verifies; tags bind the
  box, not the message id.
- **Key substitution.** The tag proves the key the directory returned. Safety numbers are what
  catch the server handing out the wrong identity key.

## Notifications

A device in the foreground notifies its user itself: it can read the message, so the open app
shows the sender and, if the user wants, the text (an in-app banner on iOS; a sound in a focused
web tab). The server pushes to every signed-in device that is not in front
(`push/mod.rs`) — including one whose socket is still open — and only ids and a kind. It has no
content to send.

- **Who gets a push.** Each of the recipient's signed-in devices with an APNs token or a Web
  Push subscription that is not in the foreground. A live socket counts as in front until the
  client sends `{type:"focus", focused:false}` (the iPhone does this when it backgrounds, the
  browser when the tab is hidden or unfocused). The server also pings every 30 s and closes a
  socket 75 s after the last frame it heard, and the iPhone closes its socket when it goes to
  the background (unless a call needs it). A device signed out by a password change gets none
  until it signs in again. Per-device settings (`device_notification_settings`: on/off, sender name, reactions,
  contact requests, sound, badge, whether muted chats count) decide the rest. A muted chat
  (`chat_mutes`, per account, for a while or until unmuted) pushes nothing — only a silent badge
  to an iPhone that counts muted chats; contact requests and calls ignore mutes. Saved
  Messages, annotations and taking an emoji back never push.
- **What a push says.** APNs: a fixed line per kind ("New message") as the body, the conversation
  as `thread-id`, `mutable-content`, and a `shroud` object with the kind and ids. The sender's
  name is `e`, AES-256-GCM sealed under a 32-byte key the iPhone generated and registered with its
  token (AAD `shroud-push-v1|kind|thread|peer`), so Apple sees ids and no names and cannot
  move a name onto another push. The notification
  service extension opens it with the key from the app group's Keychain
  (`AfterFirstUnlockThisDeviceOnly`) and makes it the title; without the key (before the first
  unlock) the push shows no name, never a readable one. The key keeps names from Apple, not from
  the server, which chose them. Web Push is RFC 8291 (`aes128gcm`) with VAPID (RFC 8292); the push
  service cannot read the payload, so the name travels inside it and the service worker
  (`web/public/sw.js`) writes the text.
- **Calls** ring iPhones through PushKit (`call` / `video_call`), even when the app is not in
  front, and a `call_ended` VoIP push stops CallKit when the ring ends. Older iPhones and
  browsers get an alert only while they are not in front. The server keeps the ring while the
  call rings, and hands it to a device that connects meanwhile, so opening the app from the
  notification lands on the ringing call.
- **Unread counts are server metadata** (`conversation_reads`, one read marker per user and chat,
  moved by reading and by replying). `GET /conversations` returns `unread_count` (capped at 999)
  and `mute`; pushes carry the total as the icon badge; reading on one device clears the others
  (`conversation.read`, plus badge-only pushes to iPhones without a socket). Like read receipts,
  the markers tell the server when a chat was read, never what it says.
- **Web Push endpoints** must be https on a known push service (`WEB_PUSH_ALLOWED_HOSTS` adds
  more), so a subscription cannot aim the server's requests at an arbitrary address. The
  Android app's endpoints (`client: "android"`, from the UnifiedPush distributor) pass their own
  policy instead: https on port 443 on a built-in UnifiedPush server (`ntfy.sh`,
  `up.conversations.im`, Mozilla's autopush), the embedded FCM distributor's
  `fcm.googleapis.com/fcm/send/` endpoint, or one the operator lists (`UNIFIEDPUSH_ALLOWED_HOSTS`);
  with `UNIFIEDPUSH_PUBLIC_HOSTS=true` any public name whose every address is globally routable,
  the push connecting only to the addresses checked. Other Google hosts are never accepted.
- **Android** links no Play Services SDK. The payload is RFC 8291 ciphertext
  (`PUT /push/web/subscription`, `client: "android"`); the phone decrypts it. On a phone with
  Play Services the embedded FCM distributor is the default: Google sees that a push was
  delivered and its size, not the message, and there is no permanent notification. A distributor
  the user installs (ntfy, or one they run) replaces it, including on that phone. With neither,
  an opt-in background connection (default off) is a `specialUse` foreground service that keeps
  the WebSocket open with `background: true` and shows a permanent notification, so contacts do
  not see the phone as online. With none of the three, nothing arrives until the app is open.
  If Play Services is absent and exactly one distributor is installed, the app registers it; if
  several are and none is the embedded distributor, it waits until one is chosen.
- **Sounds** are generated (`scripts/gen_notification_sounds.py`) and shared by both apps.

## Security invariants

1. Message plaintext exists **only on devices**, and **only in memory** while messaging is unlocked.
2. Private keys and the 12-word encryption phrase **never leave the device**.
3. Server stores ciphertext envelopes, encrypted media references, and minimal delivery metadata.
4. **Local at-rest:** chats, notes, media, and decrypt caches on disk are AES-256-GCM sealed with the BIP39-derived `historyKey` (HKDF `shroud-history-aes`). Files are excluded from backups. Without the history key, sealed blobs are unreadable.
5. **History key vault:** raw `historyKey` is **never** stored in the identity Keychain. It is AES-GCM wrapped under a device wrap key gated by **userPresence** (Face ID / Touch ID / passcode) via `HistoryKeyVault`. Phrase unlock re-derives and re-vaults the key. Backgrounding clears history key + decrypted threads from RAM.
6. Voice transcription is **on-device** for v1 (no server transcript APIs yet). Android runs whisper.cpp in the app. Per-chat language stats are sealed with the history key (`shroud-local-language-stats-v1` at `language-stats.sealed`) and stay on the device; a locked history key still transcribes, and skips the write. Whisper detects the language once and the note is decoded in it; a note is never decoded again in a language Whisper did not hear (forced, Whisper translates). Its probabilities are weighed: the device languages and region plus English count fully, another language needs five times the probability, and the chat's history makes its language up to three times likelier. Only a note whose audio settled the language (Whisper above 50 %) teaches that history. Audio reaches Whisper through a band-limited resampler (windowed sinc on web and Android, `AVAudioConverter` on iOS), never plain interpolation.
7. Contact requests and blocks are enforced on the server before full messaging.
8. Push payloads carry **ids and a kind only** — no content or keys. A sender's name goes only to a device that asks for it, sealed so the relay (Apple, or the browser's push service) cannot read it.
9. Sessions are **device-bound opaque tokens** with no time-based logout (revoke on logout / device remove / password change of other devices). Logout also forgets the device's push tokens, Web Push subscription and notification settings, so a logged-out device stops receiving the account's pushes.
10. Presence is visible only to **accepted contacts**.
11. Identity Keychain items use `WhenUnlockedThisDeviceOnly` (no backup restore; unavailable while device locked).
12. A message reads as coming from a contact only if its ratchet body decrypts or its identity box carries a verified **sender tag**; untagged boxes are refused.
13. **Nothing is stored in the clear unless the server needs to read it.** Device names, for one, are sealed to the account ([Sealed device names](#sealed-device-names)).

## Local development

Compose stack: **Postgres + Redis + Nebular OS + API + web**.

```bash
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d --build   # from repo root
curl http://127.0.0.1:8080/api/v1/health/live   # process up
curl http://127.0.0.1:8080/api/v1/health/ready  # Postgres (+ Redis if configured)
curl http://127.0.0.1:8080/api/v1/health        # same as ready (compat)
```

Optional native API: `docker compose -f docker-compose.yml -f docker-compose.local.yml up -d postgres redis nebular` then `cd server && cargo run -p shroud-server`.

Open `ios/shroud.xcodeproj` — Debug API base URL `http://127.0.0.1:8080/api/v1`.

## Implementation milestones

Detail: [server-plan.md](./server-plan.md#implementation-milestones).

### Server

1. **Auth** — **done** (register/login, multi-device, opaque tokens, argon2id)  
2. **Key bundles** — **done** (per-device identity/SPK/OTPK, PUT/GET single + multi-device list, status, atomic consume)  
3. **Contacts** — **done** (UUID requests, mutual auto-accept, directed contacts, blocks)  
4. **Messages** — **done** (HTTP send/history; lazy conversations; delivery acks)  
4b. **WebSocket** — **done** (in-process fan-out; `message.new` + `message.delivered`)  
5. **Media** — **done** (API-proxied upload/download → message link; blobs in Nebular OS or a local directory; 2 GiB)  
6. **Presence / receipts** — **done** (typing / recording WS; online/last-seen contacts-only; read receipts)  
7. **Deletes** — **done** (for me / for everyone; account deletion deletes each chat for both and keeps a scrubbed placeholder user row)  
8. **Push** — **done** (token register; offline gate; live HTTP/2 APNs with .p8 JWT when configured)  
9. **Calls** — **done** (1:1 signaling ring/accept/reject/hangup/signal; ICE servers; coturn compose profile)  
11. **Notifications** — **done** (APNs alerts with a sealed sender name, Web Push with VAPID; per-device settings; chat mutes; server unread counts and badges; socket liveness by ping)

### iOS client

| Area | Status |
| --- | --- |
| Onboarding UI + server settings | **done** |
| Auth session (Keychain + `/auth/me` validate) | **done** |
| Encryption phrase (BIP39 generate/validate) | **done** |
| Identity keys + `PUT /keys/bundle` | **done** (CryptoKit X25519/Ed25519 + AES-GCM seal) |
| Session ≠ messaging unlock | **done** (phrase or Keychain identity restore) |
| Live contacts + chats | **done** (requests/list, conversations, sealed send/recv, WS) |
| Offline local cache | **done** — 90 days of peer chats + media on device; hydrate offline, merge online |
| Local at-rest encryption | **done** — AES-256-GCM under phrase-derived `historyKey`; no plaintext on disk |
| Local store layout | **done** — sealed `roster` + per-peer `threads/{id}.sealed` (not one monolithic blob) |
| MessagingLocalRepository | **done** — offline/disk/decrypt-cache separated from MessagingController |
| OutboundPending + ChatListFormatting | **done** — pure helpers for offline queue + list previews |
| MessageDecoder + NotesLocal | **done** — decrypt pipeline and Notes CRUD pulled out of controller |
| History key vault | **done** — biometry/passcode wrap; no plain historyKey in identity Keychain; RAM wipe on background |
| Logout wipe | **done** — iOS and web clear every store the app uses (files, Keychain, defaults, HTTP cache, snapshots, notifications) behind a step-by-step overlay, then verify; identity keys go too, so signing in again takes the phrase. The device-id anchor goes too; at the 5-device cap a login reuses the account's longest-idle device with no live session. Interrupted wipes finish on next launch. Server logout also drops the device's push token. |
| History pagination | **done** — a chat opens on its newest 40 messages; older `before_created_at`/`before_id` pages load in the background (up to ~300) and then as the reader scrolls up, within the 90-day window. Clients render only the newest rows and add older ones near the top |
| Notes multi-device | **done** — Saved Messages via `peer_user_id = self`; excluded from chats list |
| Notes to me | **done** — local-only self chat (text / photo / voice / todos); no server replies |
| Photo media messages | **done** — E2E AES-GCM blobs + caption compose |
| Files | **done** — text, PDF, Word/Excel/PowerPoint, image and video files as they are, APKs; streamed SHRF1 blobs up to 2 GiB; warnings for APKs and macro-capable Office files ([file-sharing.md](./file-sharing.md)) |
| Voice messages | **done** — record/upload/play; on-device Whisper on iOS and web (pluggable engines) |
| Replies | **done** — swipe right on iOS and Android, left on the web (or the context menu) to quote; the quote is sealed **inside** the plaintext, never server metadata |
| Links & link previews | **done** — links are tappable (in-app browser), Telegram-style preview block; the sender builds the preview (the iPhone directly, the browser through the link relay) and seals it, recipients never contact the site; toggle in Privacy & Security |
| Calls UI / WebRTC | **done** — signaling + native WebRTC + CallKit; voice & video |
| Notifications | **done** — alert pushes named by the notification service extension; in-app banner, sound and haptic while in front; Settings → Notifications and Sounds (per-device toggles, sound picker, badge, muted chats, test notification); mute from the chat list or contact info; icon badge from server unread counts; a tap opens the chat. PushKit rings calls when the app is backgrounded or locked, and a `call_ended` VoIP push stops the ring |
| Sealed messaging v2 | **done** — dual-seal (peer + self) so sender devices can decrypt history |
| Sealed messaging (live) | **v3 Double Ratchet** (default) + self dual-seal; first message from non-initiator uses **v2** |
| Dual-initiator prevention | **done** — only lower `user_id` starts a new DR session; higher UUID sends v2 until session exists |
| Legacy v1/v2 open | v2 opens with a sender tag; v1 and untagged v2 are refused; `useRatchet: false` forces v2 |
| Sender tags | **done** — every identity box carries `t` (static identity ECDH → HMAC); untagged boxes refused outright; builds that can't tag blocked by the server's minimum version |
