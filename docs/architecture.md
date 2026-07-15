# Shroud architecture

High-level structure for the E2E encrypted messenger. Protocol details will expand here as features ship.

## Components

```text
┌─────────────────┐         ciphertext envelopes          ┌─────────────────┐
│   iOS app       │  ───────────────────────────────────► │  Rust server    │
│  Swift/SwiftUI  │  ◄─────────────────────────────────── │  Axum + Postgres│
│  CryptoKit      │         delivery metadata only          │  (no plaintext) │
└─────────────────┘                                       └─────────────────┘
        │
        ▼
  Keychain / Secure Enclave
  (identity keys, encryption phrase — never on wire)
```

## Repository map

| Layer | Location | Responsibility |
| --- | --- | --- |
| Design | `design/iOS-App.pen` | Screen specs, tokens, components |
| iOS UI | `ios/ShroudUI/` | Reusable SwiftUI components + `Theme` |
| iOS features | `ios/Shroud/Features/` | Screens (MVVM), 1:1 with design |
| iOS services | `ios/Shroud/Services/` | API client, crypto, persistence |
| API | `server/crates/shroud-server/` | HTTP `/api/v1`, auth, relay |
| Schema | `server/migrations/postgres/` | Forward-only sqlx migrations |

## API contract

- Base path: `/api/v1`
- Errors: `{ "error": { "code": string, "message": string } }` via server `AppError`
- Swift `APIError` decodes the same envelope (`ios/Shroud/Services/API/APIError.swift`)

## Security invariants

1. Message plaintext exists **only on devices**.
2. Private keys and the 12-word encryption phrase **never leave the device**.
3. Server stores ciphertext envelopes and minimal delivery metadata.
4. Voice transcription runs **on-device** (Speech framework).

## Local development

1. `docker compose up -d` — Postgres on `localhost:5432`
2. `cp server/.env.example server/.env` && `cd server && cargo run -p shroud-server`
3. Open `ios/Shroud.xcodeproj` — Debug API base URL `http://127.0.0.1:8080/api/v1`

## Next implementation milestones

1. **Onboarding** — registration, encryption phrase display, key generation (design screens first)
2. **Auth** — opaque tokens, argon2id passwords, session middleware
3. **Key bundles** — authenticated fetch/upload for pre-key bundles
4. **Message relay** — store-and-forward ciphertext envelopes
5. **Push** — APNs with opaque payload references only
