<p align="center"><img src="design/icon/shroud-favicon.svg" width="96" alt="Shroud"></p>

# Shroud

End-to-end encrypted messenger: **Rust + PostgreSQL** server, a **native iOS** app, a **web client**, and an **Android** app (early: sign-up and log-in).

## Repository layout

| Path | Purpose |
| --- | --- |
| `design/iOS-App.pen` | iOS design source (Pencil) |
| `design/webclient.pen` | Web client frames (desktop three-pane + mobile) |
| `server/` | Rust workspace — Axum API, migrations, integration tests |
| `web/` | Vite + React web client (same-origin `/api/v1` via nginx) |
| `ios/` | Native iOS app — SwiftUI, ShroudUI component library |
| `android/` | Native Android app — Kotlin, Jetpack Compose ([android/README.md](android/README.md)) |
| `design/Android-App.pen` | Android design source (Pencil) |
| `docs/` | Architecture, [server plan](docs/server-plan.md), [web client](docs/web-client.md) |

## Prerequisites

- **Docker** + Docker Compose v2 — API, web, Postgres, Redis, Nebular
- **Xcode 16+** — iOS app (full Xcode, not Command Line Tools only)
- **Rust** (optional) — native `cargo run` / tests without rebuilding the API image

## Deploy (recommended)

Same shape as Ownly / pzserver: a wizard writes `.env`, then Compose builds the stack.

```bash
./deploy.sh            # first run: wizard; later runs: rebuild/start
./deploy.sh --status   # web URL + API URL + container table
./deploy.sh --logs api
.\deploy.ps1           # Windows
```

| `PROXY_MODE` | How you reach it |
| --- | --- |
| `local` (default) | Web `http://localhost:8081`, API `http://localhost:8080/api/v1` |
| `npm` | Joins the external `proxy-network`. NPM hosts: `http://shroud-web:80`, `http://shroud-api:8080` |

The wizard asks for the **public web URL** (and API URL for iOS). The browser always talks same-origin (`/api/v1` proxied by web nginx). `WEB_PUBLIC_URL` is also sent to the API as CORS for split-origin setups.

Media blobs (always ciphertext) live in [Nebular OS](https://github.com/AsP3X/nebular-os), run from its published 0.2.0 image pinned by digest. The API is its only client. It signs every request with an access key Nebular limits to the `shroud-media` bucket, and clients only ever reach `/api/v1/media/{id}/content`. The wizard generates that key and Nebular's secrets. `./deploy.sh` adds any that an older `.env` lacks, and on first start the API moves blobs from the old local media volume into Nebular.

### Manual Compose

```bash
# Local ports (iOS Simulator / curl, no NPM):
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d --build

# Behind Nginx Proxy Manager (create the network once if NPM has not):
docker network create proxy-network
docker compose -f docker-compose.yml -f docker-compose.npm.yml up -d --build

curl http://127.0.0.1:8080/api/v1/health/live
# {"status":"ok"}
```

| Service | Local port | Notes |
| --- | --- | --- |
| `web` | `8081` | SPA + reverse-proxy `/api/v1` (incl. WebSocket) |
| `api` | `8080` | Axum `/api/v1`; iOS talks here; migrations on startup |
| `nebular` | `127.0.0.1:9000` | Media store (this machine only); clients use API `/media/{id}/content` |
| `postgres` | `127.0.0.1:5432` | Database (this machine only); user/db from `.env` |
| `redis` | `127.0.0.1:6379` | Multi-replica WS fan-out (this machine only; no password) |

Logging: set `RUST_LOG` in `.env` (wizard default `info`).

Stop:

```bash
./deploy.sh --down              # keep data volumes
./deploy.sh --down --volumes    # wipe Postgres / Redis / media (needed if POSTGRES_PASSWORD changed)
```

Postgres only hashes `POSTGRES_PASSWORD` the first time its volume is created. Changing the password in `.env` later will make the API fail with `password authentication failed`. Either restore the original password or wipe volumes as above.

Rebuild after server or web changes: `./deploy.sh --rebuild`.

## Android APK

Same shape as `./deploy.sh`. The first run asks what to build and writes `android/.apk.env`. Later runs reuse those options.

```bash
./apk.sh            # build (wizard on the first run)
./apk.sh --edit     # change an option
./apk.sh --init     # ask again, then build
./apk.sh --status   # show the saved options
./apk.sh --clean    # this build only: clean first
```

The default is a signed phone release. It opens on the official server, and the APK is copied to `~/Desktop/Shroud`. The release key is created once at `~/.shroud/shroud-release.p12` (RSA 4096, valid 30 years) and reused, so the next file installs over the last one. Back it up with its password: without both, no release installs over the last one. The version code goes up by one after each build that works. `android/.apk.env` holds the options and the keystore password. It is not committed. Change it by hand, or use `--edit`.

## Server (native Cargo — optional)

Useful for fast iteration without rebuilding the image. Keep Compose infra running with host ports:

```bash
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d postgres redis nebular

cp server/.env.example server/.env
# Point at host-mapped ports (defaults already do):
# DATABASE_URL=postgres://shroud:shroud@127.0.0.1:5432/shroud
# REDIS_URL=redis://:<REDIS_PASSWORD>@127.0.0.1:6379
# NEBULAR_URL=http://127.0.0.1:9000
# Nebular's access key: copy NEBULAR_ACCESS_KEY_ID / NEBULAR_SECRET_ACCESS_KEY from the
# repository's .env into server/.env. Without NEBULAR_URL, media goes to MEDIA_DATA_DIR.

cd server && cargo run -p shroud-server
```

Server tests need Postgres (`DATABASE_URL`, and they pass as skipped without it). Media tests use a temp directory. Set `SHROUD_TEST_NEBULAR_URL`, `SHROUD_TEST_NEBULAR_ACCESS_KEY_ID` and `SHROUD_TEST_NEBULAR_SECRET_ACCESS_KEY` to run them, and `tests/media_store_nebular.rs`, against a Nebular set up like `docker-compose.yml`:

```bash
cd server && DATABASE_URL=postgres://… cargo test -p shroud-server
```

## iOS

Open `ios/shroud.xcodeproj` in Xcode, select the **shroud** scheme, and run on a simulator.

Debug API base URL: `http://127.0.0.1:8080/api/v1` (requires the Compose `api` service or a local `cargo run`).

## Tests

```bash
# Server (requires Postgres — start compose infra or full stack)
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d postgres
cd server && cargo test --workspace
cd server && cargo clippy --all-targets -- -D warnings
cd server && cargo fmt --check

# iOS
xcodebuild test -scheme shroud -destination 'platform=iOS Simulator,name=iPhone 17' -project ios/shroud.xcodeproj
```

## Security

Message plaintext and private keys **never** leave the device. The server stores and relays ciphertext only. See `.cursor/rules/security-crypto.mdc`, [`docs/architecture.md`](docs/architecture.md), and [`docs/server-plan.md`](docs/server-plan.md).
