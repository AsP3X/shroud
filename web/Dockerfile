# syntax=docker/dockerfile:1

# Link-preview TLS client: rustls compiled to WebAssembly (web/tls). clang builds ring's C code
# for wasm32, llvm-ar archives it, and wasm-bindgen (pinned to the crate's version) writes the
# JavaScript glue the web client loads on first use.
FROM rust:1-slim AS link-tls
RUN apt-get update \
    && apt-get install -y --no-install-recommends clang llvm \
    && rm -rf /var/lib/apt/lists/*
RUN rustup target add wasm32-unknown-unknown \
    && cargo install wasm-bindgen-cli --version 0.2.128 --locked
WORKDIR /build
COPY tls ./tls
COPY scripts/build-link-tls.sh ./scripts/build-link-tls.sh
RUN AR_wasm32_unknown_unknown=llvm-ar sh scripts/build-link-tls.sh

FROM node:22-alpine AS builder
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
COPY --from=link-tls /build/src/linkPreview/tls ./src/linkPreview/tls
# The build id ./deploy.sh also hands the API; a tab built from another one is offered a reload.
ARG WEB_BUILD=""
ENV VITE_WEB_BUILD=$WEB_BUILD
RUN npm run build

FROM nginx:alpine
RUN apk add --no-cache wget
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY nginx-http-map.conf /etc/nginx/conf.d/00-http-map.conf
COPY security-headers.conf /etc/nginx/security-headers.conf
COPY api-proxy.conf /etc/nginx/api-proxy.conf
# nginx image envsubst only replaces defined environment variables.
ENV API_UPSTREAM=http://api:8080
ENV MAX_UPLOAD_BYTES=2147483648
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1
