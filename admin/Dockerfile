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

# admin/api is its own workspace. This stage copies that directory only.
WORKDIR /src
COPY admin/api/Cargo.toml admin/api/Cargo.lock admin/api/rust-toolchain.toml ./
RUN mkdir -p src \
    && printf '%s\n' 'fn main() {}' > src/main.rs \
    && printf '%s\n' 'pub fn placeholder() {}' > src/lib.rs \
    && cargo build --release \
    && rm -rf src
COPY admin/api/src ./src
COPY admin/api/migrations ./migrations
RUN touch src/lib.rs src/main.rs \
    && cargo build --release

FROM gcr.io/distroless/cc-debian12:nonroot

COPY --from=rust /src/target/release/shroud-admin /shroud-admin
COPY --from=ui /src/admin/ui/dist /ui/dist

ENV HOST=0.0.0.0
ENV PORT=8082
ENV ADMIN_DIST=/ui/dist

EXPOSE 8082

USER nonroot

ENTRYPOINT ["/shroud-admin"]
