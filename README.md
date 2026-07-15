# Shroud

End-to-end encrypted messenger: **Rust + PostgreSQL** server and a **native iOS** app (Swift/SwiftUI).

## Repository layout

| Path | Purpose |
| --- | --- |
| `design/iOS-App.pen` | Design source of truth (Pencil) |
| `server/` | Rust workspace — Axum API, migrations, integration tests |
| `ios/` | Native iOS app — SwiftUI, ShroudUI component library |
| `docs/` | Architecture + [server plan](docs/server-plan.md) (API decisions, milestones) |

## Prerequisites

- **Docker** + Docker Compose — full API stack (Postgres, Redis, API)
- **Xcode 16+** — iOS app (full Xcode, not Command Line Tools only)
- **Rust** (optional) — native `cargo run` / tests without rebuilding the API image

## Server (Docker Compose — recommended)

From the **repository root**:

```bash
# Build and start Postgres + Redis + API
docker compose up -d --build

# Follow API logs
docker compose logs -f api

# Health check (host)
curl http://127.0.0.1:8080/api/v1/health
# {"status":"ok","database":"ok"}
```

| Service | Host port | Notes |
| --- | --- | --- |
| `api` | `8080` | Axum `/api/v1`; migrations run on startup |
| `postgres` | `5432` | User/db/password: `shroud` |
| `redis` | `6379` | Multi-replica WS fan-out |

Stop:

```bash
docker compose down          # keep data volumes
docker compose down -v       # wipe Postgres/Redis data
```

Rebuild after server code changes:

```bash
docker compose up -d --build api
```

## Server (native Cargo — optional)

Useful for fast iteration without rebuilding the image. Keep Compose infra running:

```bash
docker compose up -d postgres redis

cp server/.env.example server/.env
# Point at host-mapped ports (defaults already do):
# DATABASE_URL=postgres://shroud:shroud@127.0.0.1:5432/shroud
# REDIS_URL=redis://127.0.0.1:6379

cd server && cargo run -p shroud-server
```

## iOS

Open `ios/shroud.xcodeproj` in Xcode, select the **shroud** scheme, and run on a simulator.

Debug API base URL: `http://127.0.0.1:8080/api/v1` (requires the Compose `api` service or a local `cargo run`).

## Tests

```bash
# Server (requires Postgres — start compose infra or full stack)
docker compose up -d postgres
cd server && cargo test --workspace
cd server && cargo clippy --all-targets -- -D warnings
cd server && cargo fmt --check

# iOS
xcodebuild test -scheme shroud -destination 'platform=iOS Simulator,name=iPhone 17' -project ios/shroud.xcodeproj
```

## Security

Message plaintext and private keys **never** leave the device. The server stores and relays ciphertext only. See `.cursor/rules/security-crypto.mdc`, [`docs/architecture.md`](docs/architecture.md), and [`docs/server-plan.md`](docs/server-plan.md).
