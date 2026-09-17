# Shroud web client

Vite + React SPA. Tokens and layout follow `design/webclient.pen`.

```bash
npm install
npm run dev          # http://localhost:5173 — proxies /api to :8080
npm run build
```

Production is the `web` service in Compose (`./deploy.sh`). nginx serves the SPA and reverse-proxies `/api/v1` (including WebSocket) to `api:8080`, so the browser is same-origin.
