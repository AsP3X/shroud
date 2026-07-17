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

### Networks

| Network | Type | Purpose |
| --- | --- | --- |
| `shroud-internal` | Compose bridge | Postgres, Redis, and private service-to-service traffic |
| `proxy-network` | **External** | Shared with **Nginx Proxy Manager** — only services that should be public |

```bash
# Once per machine (skip if NPM already created it)
docker network create proxy-network
```

| Service | Networks | NPM target (example) |
| --- | --- | --- |
| `postgres` | `shroud-internal` only | — |
| `redis` | `shroud-internal` only | — |
| `api` (`shroud-api`) | internal + **proxy** | `http://shroud-api:8080` |
| `nebular` (`shroud-nebular`) | internal + **proxy** | `http://shroud-nebular:9000` (presigned media) |

### Start

From the **repository root**:

```bash
# Build and start Postgres + Redis + Nebular + API
# Nebular is built from ../ownly/nebular-os (override with NEBULAR_CONTEXT=...)
docker compose up -d --build

# Local host ports (iOS Simulator / curl without NPM):
docker compose -f docker-compose.yml -f docker-compose.host-ports.yml up -d --build

# Follow API + Nebular logs (Ownly-style RUST_LOG=debug by default)
docker compose logs -f api nebular

# Health check (host ports profile, or via your NPM hostname)
curl http://127.0.0.1:8080/api/v1/health/live
# {"status":"ok"}
curl http://127.0.0.1:8080/api/v1/health/ready
# {"status":"ok","database":"ok","redis":"ok"|"skipped"}
```

| Service | Default host port | Notes |
| --- | --- | --- |
| `api` | none (use NPM or `host-ports` file → `8080`) | Axum `/api/v1`; migrations on startup; `x-request-id` |
| `nebular` | none (or `9000` with host-ports) | Object storage; needs Ownly checkout or `NEBULAR_CONTEXT` |
| `postgres` | none (or `5432` with host-ports) | User/db/password: `shroud` |
| `redis` | none (or `6379` with host-ports) | Multi-replica WS fan-out |

Logging: compose sets `RUST_LOG=debug` for `api` and `nebular`. Override with `RUST_LOG=info docker compose up`.

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

Useful for fast iteration without rebuilding the image. Keep Compose infra running with host ports:

```bash
docker compose -f docker-compose.yml -f docker-compose.host-ports.yml up -d postgres redis nebular

cp server/.env.example server/.env
# Point at host-mapped ports (defaults already do):
# DATABASE_URL=postgres://shroud:shroud@127.0.0.1:5432/shroud
# REDIS_URL=redis://127.0.0.1:6379
# NEBULAR_URL=http://127.0.0.1:9000

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
