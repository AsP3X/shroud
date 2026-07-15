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

Target Compose stack (as features land): **Postgres + Redis + Nebular OS + API**.

1. `docker compose up -d` — infrastructure (Postgres today; Redis/Nebular as milestones need them)
2. `cp server/.env.example server/.env` && `cd server && cargo run -p shroud-server`
3. Open `ios/shroud.xcodeproj` — Debug API base URL `http://127.0.0.1:8080/api/v1`

## Implementation milestones

Detail: [server-plan.md](./server-plan.md#implementation-milestones).

1. **Auth** — **done** (register/login, multi-device, opaque tokens, argon2id)  
2. **Key bundles** — plan locked: per-device identity/SPK/OTPK, PUT/GET/status, atomic consume (see server-plan)  
3. **Contacts** — UUID share links, contact requests, block  
4. **Messages** — 1:1 conversations, ciphertext store, WebSocket/Redis fan-out  
5. **Media** — Nebular (`shroud-media` / `{user_id}/{object_id}`), 25 MiB, 15m presign  
6. **Presence / receipts** — typing, online/last-seen (contacts only), optional read receipts  
7. **Deletes** — for me / for everyone; hard account cascade  
8. **Push** — APNs data notifications (opaque ids)  
9. **Calls** — WebRTC signaling + coturn  
