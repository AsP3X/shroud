# Multi-stage build for shroud-server (Axum API).
# Agent: Builds release binary; runtime is distroless/debian slim; no secrets in image.

FROM rust:1-bookworm AS builder

WORKDIR /app

# Cache dependency builds when only sources change.
COPY Cargo.toml Cargo.lock rust-toolchain.toml ./
COPY crates/shroud-server/Cargo.toml crates/shroud-server/Cargo.toml
# crates/shroud-admin is a symlink to admin/api, outside this build context.
# Cargo still loads every workspace member. This image only compiles
# shroud-server, so the member manifest (same dependencies as admin/api) and
# a dummy source are enough.
COPY docker/shroud-admin-member.toml crates/shroud-admin/Cargo.toml

# Dummy mains so `cargo build` can resolve deps before full sources copy.
RUN mkdir -p crates/shroud-server/src crates/shroud-admin/src \
    && echo 'fn main() {}' > crates/shroud-server/src/main.rs \
    && echo 'pub fn placeholder() {}' > crates/shroud-server/src/lib.rs \
    && echo 'fn main() {}' > crates/shroud-admin/src/main.rs \
    && echo 'pub fn placeholder() {}' > crates/shroud-admin/src/lib.rs \
    && cargo build --release -p shroud-server \
    && rm -rf crates/shroud-server/src

COPY crates/shroud-server crates/shroud-server
COPY migrations migrations

# Force rebuild of the package (deps stay cached).
RUN touch crates/shroud-server/src/main.rs crates/shroud-server/src/lib.rs \
    && cargo build --release -p shroud-server

FROM debian:bookworm-slim AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates libssl3 gosu curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /data/shroud-media \
    && chown -R nobody:nogroup /data

WORKDIR /app

COPY --from=builder /app/target/release/shroud-server /usr/local/bin/shroud-server
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENV HOST=0.0.0.0
ENV PORT=8080
ENV MEDIA_DATA_DIR=/data/shroud-media
# Overridden by compose to debug for local; keep info as image default for prod-like runs.
ENV RUST_LOG=shroud_server=info,tower_http=info,sqlx=warn

EXPOSE 8080

# Entrypoint starts as root only to chown the media volume, then drops to nobody.
ENTRYPOINT ["/entrypoint.sh"]
