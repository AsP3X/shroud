# Shroud architecture

High-level structure for the E2E encrypted messenger.

| Doc | Role |
| --- | --- |
| **[server-plan.md](./server-plan.md)** | Server decisions, milestones, locked Auth API |
| [thought-collection.md](../thought-collection.md) | Calls, WebRTC, Compose scaling notes |

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

**coturn** (Compose profile) supplies TURN when P2P fails. Call media does not flow through the Rust API. App media ciphertext is stored on the API volume and, when `NEBULAR_URL` is set, mirrored to Nebular with prefer-Nebular reads for multi-replica (`MEDIA_PREFER_NEBULAR`, default true). Clients always use `/media/{id}/content`. Prometheus text metrics: `GET /api/v1/metrics`.

## Repository map

| Layer | Location | Responsibility |
| --- | --- | --- |
| Design | `design/iOS-App.pen`, `design/webclient.pen` | Screen specs, tokens, components |
| iOS UI | `ios/shroud/ShroudUI/` | Reusable SwiftUI components + `Theme` |
| iOS features | `ios/shroud/Features/` | Screens (MVVM), 1:1 with design |
| iOS services | `ios/shroud/Services/` | API client, crypto, persistence |
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
| Annotation | `{"t":"transcript","r":<message id>,"c":<text>}` | `MessageAnnotation` |

`re` carries the quoted message's id (`id`), its author (`u`), its kind (`k`) and a ≤120-character
snippet (`x`) so a quote still reads when the original has aged out of the local window. Anything
that does not parse as one of these shapes is treated as plain text, which is what keeps old and
new builds interoperable in both directions.

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

## Security invariants

1. Message plaintext exists **only on devices**, and **only in memory** while messaging is unlocked.
2. Private keys and the 12-word encryption phrase **never leave the device**.
3. Server stores ciphertext envelopes, encrypted media references, and minimal delivery metadata.
4. **Local at-rest:** chats, notes, media, and decrypt caches on disk are AES-256-GCM sealed with the BIP39-derived `historyKey` (HKDF `shroud-history-aes`). Files are excluded from backups. Without the history key, sealed blobs are unreadable.
5. **History key vault:** raw `historyKey` is **never** stored in the identity Keychain. It is AES-GCM wrapped under a device wrap key gated by **userPresence** (Face ID / Touch ID / passcode) via `HistoryKeyVault`. Phrase unlock re-derives and re-vaults the key. Backgrounding clears history key + decrypted threads from RAM.
6. Voice transcription is **on-device** for v1 (no server transcript APIs yet).
7. Contact requests and blocks are enforced on the server before full messaging.
8. Push payloads are **opaque references only** (no content or keys).
9. Sessions are **device-bound opaque tokens** with no time-based logout (revoke on logout / device remove / password change of other devices). Logout also forgets the device's push token, so a logged-out phone stops receiving the account's pushes.
10. Presence is visible only to **accepted contacts**.
11. Identity Keychain items use `WhenUnlockedThisDeviceOnly` (no backup restore; unavailable while device locked).

## Local development

Compose stack: **Postgres + Redis + API** (Nebular optional later).

```bash
docker compose up -d --build   # from repo root
curl http://127.0.0.1:8080/api/v1/health/live   # process up
curl http://127.0.0.1:8080/api/v1/health/ready  # Postgres (+ Redis if configured)
curl http://127.0.0.1:8080/api/v1/health        # same as ready (compat)
```

Optional native API: `docker compose up -d postgres redis` then `cd server && cargo run -p shroud-server`.

Open `ios/shroud.xcodeproj` — Debug API base URL `http://127.0.0.1:8080/api/v1`.

## Implementation milestones

Detail: [server-plan.md](./server-plan.md#implementation-milestones).

### Server

1. **Auth** — **done** (register/login, multi-device, opaque tokens, argon2id)  
2. **Key bundles** — **done** (per-device identity/SPK/OTPK, PUT/GET single + multi-device list, status, atomic consume)  
3. **Contacts** — **done** (UUID requests, mutual auto-accept, directed contacts, blocks)  
4. **Messages** — **done** (HTTP send/history; lazy conversations; delivery acks)  
4b. **WebSocket** — **done** (in-process fan-out; `message.new` + `message.delivered`)  
5. **Media** — **done** (API-proxied upload/download → message link; optional Nebular mirror; 25 MiB)  
6. **Presence / receipts** — **done** (typing / recording WS; online/last-seen contacts-only; read receipts)  
7. **Deletes** — **done** (for me / for everyone; hard account cascade)  
8. **Push** — **done** (token register; offline gate; live HTTP/2 APNs with .p8 JWT when configured)  
9. **Calls** — **done** (1:1 signaling ring/accept/reject/hangup/signal; ICE servers; coturn compose profile)

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
| Logout wipe | **done** — iOS and web clear every store the app uses (files, Keychain, defaults, HTTP cache, snapshots, notifications) behind a step-by-step overlay, then verify; identity keys go too, so signing in again takes the phrase. The device-id anchor stays so the next login reuses the device (5-device cap). Interrupted wipes finish on next launch. Server logout also drops the device's push token. |
| History pagination | **done** — a chat opens on its newest 40 messages; older `before_created_at`/`before_id` pages load in the background (up to ~300) and then as the reader scrolls up, within the 90-day window. Clients render only the newest rows and add older ones near the top |
| Notes multi-device | **done** — Saved Messages via `peer_user_id = self`; excluded from chats list |
| Notes to me | **done** — local-only self chat (text / photo / voice / todos); no server replies |
| Photo media messages | **done** — E2E AES-GCM blobs + caption compose |
| Voice messages | **done** — record/upload/play; on-device Whisper on iOS and web (pluggable engines) |
| Replies | **done** — swipe left (or the context menu) to quote; the quote is sealed **inside** the plaintext, never server metadata |
| Links & link previews | **done** — links are tappable (in-app browser), Telegram-style preview block; the sender builds the preview (the iPhone directly, the browser through the link relay) and seals it, recipients never contact the site; toggle in Privacy & Security |
| Calls UI / WebRTC | **done** — signaling + WKWebView WebRTC + CallKit; voice & video |
| APNs / VoIP push register | **done** — data token + PushKit VoIP token → `PUT /push/token` |
| Sealed messaging v2 | **done** — dual-seal (peer + self) so sender devices can decrypt history |
| Sealed messaging (live) | **v3 Double Ratchet** (default) + self dual-seal; first message from non-initiator uses **v2** |
| Dual-initiator prevention | **done** — only lower `user_id` starts a new DR session; higher UUID sends v2 until session exists |
| Legacy v1/v2 open | **done** — still openable; `useRatchet: false` forces v2 |
