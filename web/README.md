# Shroud web client

Vite + React SPA. Tokens and layout follow `design/webclient.pen`.

```bash
npm install
npm run dev          # http://localhost:5173 — proxies /api to :8080
npm run build
npm run build:tls    # link-preview TLS module (optional locally, see below)
```

`npm run build:tls` compiles `tls/` (rustls → WebAssembly) into `src/linkPreview/tls/`, which the
composer loads to build link previews through the API's link relay. It needs a Rust toolchain
with the `wasm32-unknown-unknown` target and `llvm-tools`, clang, and
`cargo install wasm-bindgen-cli --version 0.2.128`. The generated module is not committed; the
Docker image builds it in its first stage, and without it the web client simply shows no previews
for links you send.

Production is the `web` service in Compose (`./deploy.sh`). nginx serves the SPA and reverse-proxies `/api/v1` (including WebSocket) to `api:8080`, so the browser is same-origin.
