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
         │                                                             │
         ▼                                                             │
  Keychain / Secure Enclave                              ┌─────────────┼─────────────┐
  (identity keys, encryption phrase                        ▼             ▼             ▼
   — never on wire)                                   Postgres        Redis      Nebular OS
                                                      (durable)    (fan-out,    (ciphertext
                                                                    limits,       blobs)
                                                                    presence)
```

Later: **coturn** for WebRTC TURN (calls). Call media does not flow through the Rust API. App media blobs live in Nebular OS (ciphertext).

## Repository map

| Layer | Location | Responsibility |
| --- | --- | --- |
| Design | `design/iOS-App.pen` | Screen specs, tokens, components |
| iOS UI | `ios/shroud/ShroudUI/` | Reusable SwiftUI components + `Theme` |
| iOS features | `ios/shroud/Features/` | Screens (MVVM), 1:1 with design |
| iOS services | `ios/shroud/Services/` | API client, crypto, persistence |
| API | `server/crates/shroud-server/` | HTTP `/api/v1`, WebSocket, auth, relay |
| Schema | `server/migrations/postgres/` | Forward-only sqlx migrations |
| Docs | `docs/` | Architecture, server plan, protocol notes |

## API contract

- Base path: `/api/v1`
- Errors: `{ "error": { "code": string, "message": string } }` (`AppError` / Swift `APIError`)
- Auth routes and bodies: [server-plan.md — Milestone 1](./server-plan.md#milestone-1--auth-locked)

## Security invariants

1. Message plaintext exists **only on devices**.
2. Private keys and the 12-word encryption phrase **never leave the device**.
3. Server stores ciphertext envelopes, encrypted media references, and minimal delivery metadata.
4. Voice transcription is **on-device** for v1 (no server transcript APIs yet).
5. Contact requests and blocks are enforced on the server before full messaging.
6. Push payloads are **opaque references only** (no content or keys).
7. Sessions are **device-bound opaque tokens** with no time-based logout (revoke on logout / device remove / password change of other devices).
8. Presence is visible only to **accepted contacts**.

## Local development

Compose stack: **Postgres + Redis + API** (Nebular optional later).

```bash
docker compose up -d --build   # from repo root
curl http://127.0.0.1:8080/api/v1/health
```

Optional native API: `docker compose up -d postgres redis` then `cd server && cargo run -p shroud-server`.

Open `ios/shroud.xcodeproj` — Debug API base URL `http://127.0.0.1:8080/api/v1`.

## Implementation milestones

Detail: [server-plan.md](./server-plan.md#implementation-milestones).

### Server

1. **Auth** — **done** (register/login, multi-device, opaque tokens, argon2id)  
2. **Key bundles** — **done** (per-device identity/SPK/OTPK, PUT/GET/status, atomic consume)  
3. **Contacts** — **done** (UUID requests, mutual auto-accept, directed contacts, blocks)  
4. **Messages** — **done** (HTTP send/history; lazy conversations; delivery acks)  
4b. **WebSocket** — **done** (in-process fan-out; `message.new` + `message.delivered`)  
5. **Media** — **done** (presign upload → message link; stub without Nebular; 25 MiB)  
6. **Presence / receipts** — **done** (typing WS; online/last-seen contacts-only; read receipts)  
7. **Deletes** — for me / for everyone; hard account cascade  
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
| Calls UI / WebRTC | **mock** — server signaling ready |
| Full Signal Double Ratchet | **not yet** — sealed ECDH envelopes ready for upgrade |
