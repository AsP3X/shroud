import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { fixtureServer } from "./dev-server";

// The contract's example responses (docs/admin-plan.md §3.8), served as the backend in
// `npm run dev:fixtures`. Plain `npm run dev` proxies to a running shroud-admin instead.
const fixturesDir = fileURLToPath(new URL("../api/fixtures/", import.meta.url));

export default defineConfig(({ mode }) => ({
  plugins: [react(), ...(mode === "fixtures" ? [fixtureServer(fixturesDir)] : [])],
  server: {
    port: 5174,
    strictPort: true,
    proxy:
      mode === "fixtures"
        ? undefined
        : {
            "/api/admin": {
              target: process.env.ADMIN_API_URL ?? "http://127.0.0.1:8082",
              changeOrigin: true,
            },
          },
  },
  build: {
    outDir: "dist",
    sourcemap: false,
  },
}));
