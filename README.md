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

The operator console is a page of the Shroud site (`WEB_PUBLIC_URL` + `/admin`, for example `https://shroud-app.com/admin`). Turn it on without re-running the setup wizard:

```bash
./deploy.sh --admin              # turn it on and deploy it
./deploy.sh --admin bootstrap    # one-time operator setup link
./deploy.sh --admin off          # turn it off; the key stays in .env
```

See [docs/admin.md](docs/admin.md). Windows uses `.\deploy.ps1 -Admin` with the same words (`off`, `bootstrap`).

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

# Either one with data in folders instead of named volumes (see Data storage). Make the three
# folders first, or Docker creates them as root, and give nebular/ to Nebular's user, which
# its entrypoint doesn't do (./deploy.sh does both):
mkdir -p data/database data/nebular data/media
sudo chown 10001:10001 data/nebular
SHROUD_DATA_DIR="$PWD/data" docker compose -f docker-compose.yml -f docker-compose.local.yml -f docker-compose.data-dir.yml up -d --build

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
./deploy.sh --down              # keep the data
./deploy.sh --down --volumes    # wipe Postgres / Nebular / media data (needed if POSTGRES_PASSWORD changed)
```

Postgres only hashes `POSTGRES_PASSWORD` the first time it sets up its data. Changing the password in `.env` later will make the API fail with `password authentication failed`. Either restore the original password or wipe the data as above.

Rebuild after server or web changes: `./deploy.sh --rebuild`.

### Data storage

Postgres, Nebular (media blobs) and the API's media folder keep their data in one of two places. Redis keeps nothing on disk.

| Mode | Where | Turn on |
| --- | --- | --- |
| Named volumes (default) | Docker volumes `<project>_shroud_pg_data`, `_shroud_nebular_data`, `_shroud_media_data` (`<project>` is the checkout's folder name, e.g. `shroud`) | `./deploy.sh --named-volumes` |
| Data folder | `database/`, `nebular/` and `media/` inside one folder, `./data` by default | `./deploy.sh --data-dir [path]` |

`--data-dir` takes a path relative to the repository or an absolute one, saves it in `.env` as `SHROUD_DATA_DIR`, and deploys; later plain `./deploy.sh` runs keep it. `--named-volumes` removes the setting. Windows: `.\deploy.ps1 -DataDir [path]` and `-NamedVolumes`. The wizard asks too. `--status` shows which storage is in use. The files in the folders belong to the containers' users (Postgres uid 70, Nebular 10001, media `nobody`), so `./deploy.sh --down --volumes` empties those three folders through a container and leaves everything else in the folder alone.

**Back up** with the stack down (`./deploy.sh --down`): in folder mode, copy the data folder as root (or with `sudo`) so owners and modes stay; with named volumes, copy each volume out through a container:

```bash
docker run --rm -v shroud_shroud_pg_data:/from:ro -v "$PWD/backup/database:/to" postgres:16-alpine cp -a /from/. /to/
```

**Switching** never moves, deletes or overwrites data on its own. When the side you leave holds a database and the other side is empty, the switch stops and prints the copy commands for your setup. Copy with the stack down, then switch:

```bash
./deploy.sh --down
mkdir -p data/database data/nebular data/media
docker run --rm -v shroud_shroud_pg_data:/from:ro -v "$PWD/data/database:/to" postgres:16-alpine cp -a /from/. /to/
docker run --rm -v shroud_shroud_nebular_data:/from:ro -v "$PWD/data/nebular:/to" postgres:16-alpine cp -a /from/. /to/
docker run --rm -v shroud_shroud_media_data:/from:ro -v "$PWD/data/media:/to" postgres:16-alpine cp -a /from/. /to/
./deploy.sh --data-dir
```

Going back works the same way the other round: create each volume with Compose's labels (`docker volume create --label com.docker.compose.project=shroud --label com.docker.compose.volume=shroud_pg_data shroud_shroud_pg_data`), copy `data/database` into it, and so on, then `./deploy.sh --named-volumes`. Or add `--migrate` to the switch: after you confirm, it stops the stack and runs those copies itself. Either way the old volumes or folders stay where they are; remove them once the switch works (`docker volume rm …`, or `docker run --rm -v "$PWD/data:/data" postgres:16-alpine rm -rf /data/database /data/nebular /data/media`).

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
