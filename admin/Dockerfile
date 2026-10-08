# Admin console image.
# Stage 1 builds admin/ui. Stage 2 builds the shroud-admin binary. The final
# image is distroless and runs as nonroot: no shell, no package manager.
# Compose sets read_only: true. /healthz does not need a file, and the UI is
# read from /ui/dist.

FROM node:22-bookworm AS ui

WORKDIR /src/admin/ui
COPY admin/ui/package.json admin/ui/package-lock.json ./
RUN npm ci
COPY admin/ui/ ./
RUN npm run build

FROM rust:1-bookworm AS rust

WORKDIR /src
COPY server/Cargo.toml server/Cargo.lock server/rust-toolchain.toml ./
COPY server/crates/shroud-server/Cargo.toml crates/shroud-server/Cargo.toml
# The workspace member crates/shroud-admin is a symlink to admin/api, which
# Docker will not follow out of the server build context. Copy the crate in
# as a real directory. Only its manifest is needed to cache dependencies.
COPY admin/api/Cargo.toml crates/shroud-admin/Cargo.toml
RUN mkdir -p crates/shroud-server/src crates/shroud-admin/src \
    && printf '%s\n' 'fn main() {}' > crates/shroud-server/src/main.rs \
    && printf '%s\n' 'pub fn placeholder() {}' > crates/shroud-server/src/lib.rs \
    && printf '%s\n' 'fn main() {}' > crates/shroud-admin/src/main.rs \
    && printf '%s\n' 'pub fn placeholder() {}' > crates/shroud-admin/src/lib.rs \
    && cargo build --release -p shroud-admin \
    && rm -rf crates/shroud-admin/src
COPY admin/api/src crates/shroud-admin/src
RUN touch crates/shroud-admin/src/lib.rs crates/shroud-admin/src/main.rs \
    && cargo build --release -p shroud-admin

FROM gcr.io/distroless/cc-debian12:nonroot

COPY --from=rust /src/target/release/shroud-admin /shroud-admin
COPY --from=ui /src/admin/ui/dist /ui/dist

ENV HOST=0.0.0.0
ENV PORT=8082
ENV ADMIN_DIST=/ui/dist

EXPOSE 8082

USER nonroot

ENTRYPOINT ["/shroud-admin"]
