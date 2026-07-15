# Shroud

End-to-end encrypted messenger: **Rust + PostgreSQL** server and a **native iOS** app (Swift/SwiftUI).

## Repository layout

| Path | Purpose |
| --- | --- |
| `design/iOS-App.pen` | Design source of truth (Pencil) |
| `server/` | Rust workspace — Axum API, migrations, integration tests |
| `ios/` | Native iOS app — SwiftUI, ShroudUI component library |
| `docs/` | Architecture, protocol notes, API contracts |

## Prerequisites

- **Rust** (stable) — [rustup](https://rustup.rs/)
- **Docker** — local PostgreSQL via Compose
- **Xcode 16+** — iOS app (full Xcode, not Command Line Tools only)

## Server (local dev)

```bash
# Start Postgres
docker compose up -d

# Copy env and run migrations + API
cp server/.env.example server/.env
cd server && cargo run -p shroud-server
```

Health check: `GET http://localhost:8080/api/v1/health`

## iOS

Open `ios/Shroud.xcodeproj` in Xcode, select the **Shroud** scheme, and run on a simulator.

## Tests

```bash
# Server (requires Postgres — see server/.env.example)
cd server && cargo test --workspace
cd server && cargo clippy --all-targets -- -D warnings
cd server && cargo fmt --check

# iOS
xcodebuild test -scheme Shroud -destination 'platform=iOS Simulator,name=iPhone 16' -project ios/Shroud.xcodeproj
```

## Security

Message plaintext and private keys **never** leave the device. The server stores and relays ciphertext only. See `.cursor/rules/security-crypto.mdc` and `docs/architecture.md`.
