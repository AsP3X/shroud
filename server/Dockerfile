# Multi-stage build for shroud-server (Axum API).
# Agent: Builds release binary; runtime is distroless/debian slim; no secrets in image.

FROM rust:1-bookworm AS builder

WORKDIR /app

# Cache dependency builds when only sources change.
COPY Cargo.toml Cargo.lock rust-toolchain.toml ./
COPY crates/shroud-server/Cargo.toml crates/shroud-server/Cargo.toml

# Dummy main so `cargo build` can resolve deps before full sources copy.
RUN mkdir -p crates/shroud-server/src \
    && echo 'fn main() {}' > crates/shroud-server/src/main.rs \
    && echo 'pub fn placeholder() {}' > crates/shroud-server/src/lib.rs \
    && cargo build --release -p shroud-server \
    && rm -rf crates/shroud-server/src

COPY crates/shroud-server crates/shroud-server
COPY migrations migrations

# Force rebuild of the package (deps stay cached).
RUN touch crates/shroud-server/src/main.rs crates/shroud-server/src/lib.rs \
    && cargo build --release -p shroud-server

FROM debian:bookworm-slim AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates libssl3 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /app/target/release/shroud-server /usr/local/bin/shroud-server

ENV HOST=0.0.0.0
ENV PORT=8080
ENV RUST_LOG=shroud_server=info,tower_http=info,sqlx=warn

EXPOSE 8080

USER nobody:nogroup

CMD ["shroud-server"]
