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

Production is the `web` service in Compose (`./deploy.sh`). nginx serves the SPA and reverse-proxies `/api/v1` (including WebSocket) to `api:8080`, so the browser is same-origin. It also proxies `/admin` and `/api/admin` to the operator console. Those paths answer only while the console is on (`./deploy.sh --admin`).

`npm run build` also writes `.gz` (gzip 9) and `.br` (Brotli 11) copies of the compressible files
in `dist/assets/` and `dist/pdfjs/` (`precompress.ts`; skipped below 1 KB or when a copy saves
less than 10%), which nginx serves as they are with `gzip_static` and `brotli_static`. Brotli on
the two 27 MB ONNX runtime `.wasm` files takes most of the time: they are compressed one at a time
(about 230 MB of memory each), and the whole build takes about 75 s on an Apple-silicon Mac instead
of 5 s, longer on a small server. The official nginx image has no Brotli module, so `Dockerfile`
compiles `ngx_brotli`'s static module (pinned by commit) against the image's own nginx and checks
it loads with `nginx -t`.
