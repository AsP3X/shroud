# Admin console image.
# Stage 1 builds admin/ui. Stage 2 builds the shroud-admin binary. The final
# image is distroless and runs as nonroot: no shell, no package manager.
# Compose sets read_only: true. /healthz does not need a file, and the UI is
# read from /ui/dist. Base images are pinned by digest with their tag kept in front; Dependabot
# proposes new ones.

FROM node:22-bookworm@sha256:e13726ca604184d7a91fcacab1fc09eefd642dc28e79ac34e85540dd93b78cfb AS ui

WORKDIR /src/admin/ui
COPY admin/ui/package.json admin/ui/package-lock.json ./
RUN npm ci
COPY admin/ui/ ./
RUN npm run build

FROM rust:1-bookworm@sha256:e6f2cff35d0f309acf8433b4f05878ed77092602c5065cc81801177da0bb3a9c AS rust

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

FROM gcr.io/distroless/cc-debian12:nonroot@sha256:9dac0a79194e45a7da0158a9c6da57b217585af0786db3845d1f0ec1a0dd182f

COPY --from=rust /src/target/release/shroud-admin /shroud-admin
COPY --from=ui /src/admin/ui/dist /ui/dist

ENV HOST=0.0.0.0
ENV PORT=8082
ENV ADMIN_DIST=/ui/dist

EXPOSE 8082

USER nonroot

ENTRYPOINT ["/shroud-admin"]
