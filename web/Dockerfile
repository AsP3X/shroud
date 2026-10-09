# syntax=docker/dockerfile:1

# nginx image for both the Brotli module build and the final stage, so the module is compiled for
# exactly the nginx it is loaded into.
ARG NGINX_IMAGE=nginx:alpine

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

# brotli_static: nginx serves the .br copies precompress.ts writes next to the build's assets. The
# official image has no Brotli module, so it is compiled here against the image's own nginx source
# (--with-compat makes it loadable by the official binary). Only the static module: it serves the
# copies as they are and links no Brotli library; nothing is compressed per request.
FROM ${NGINX_IMAGE} AS brotli
# github.com/google/ngx_brotli, pinned by commit.
ARG NGX_BROTLI_COMMIT=a71f9312c2deb28875acc7bacfdd5695a111aa53
RUN apk add --no-cache gcc git libc-dev linux-headers make pcre2-dev zlib-dev
WORKDIR /build
RUN wget -qO- "https://nginx.org/download/nginx-${NGINX_VERSION}.tar.gz" | tar xz \
    && git init -q ngx_brotli \
    && git -C ngx_brotli fetch -q --depth 1 https://github.com/google/ngx_brotli "$NGX_BROTLI_COMMIT" \
    && git -C ngx_brotli checkout -q FETCH_HEAD \
    && cd "nginx-${NGINX_VERSION}" \
    && ./configure --with-compat --add-dynamic-module=../ngx_brotli/static \
    && make modules \
    && cp objs/ngx_http_brotli_static_module.so /build/

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

FROM ${NGINX_IMAGE}
RUN apk add --no-cache wget
COPY --from=brotli /build/ngx_http_brotli_static_module.so /usr/lib/nginx/modules/
# nginx -t fails the build if the module doesn't match this nginx.
RUN sed -i '1i load_module modules/ngx_http_brotli_static_module.so;' /etc/nginx/nginx.conf \
    && nginx -t
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY nginx-http-map.conf /etc/nginx/conf.d/00-http-map.conf
COPY security-headers.conf /etc/nginx/security-headers.conf
COPY api-proxy.conf /etc/nginx/api-proxy.conf
COPY admin-proxy.conf /etc/nginx/admin-proxy.conf
# nginx image envsubst only replaces defined environment variables.
ENV API_UPSTREAM=http://api:8080
ENV ADMIN_UPSTREAM=http://admin:8082
ENV MAX_UPLOAD_BYTES=2147483648
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1
