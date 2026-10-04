import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The deploy's build id (web/Dockerfile → VITE_WEB_BUILD). It is also written into index.html, so
// a tab offers a reload only once the page a reload would load carries it (src/appVersion.ts).
const webBuild = (process.env.VITE_WEB_BUILD ?? "").trim();

export default defineConfig({
  plugins: [
    react(),
    {
      name: "shroud-build-meta",
      transformIndexHtml: () =>
        webBuild ? [{ tag: "meta", attrs: { name: "shroud-build", content: webBuild }, injectTo: "head" }] : [],
    },
  ],
  assetsInclude: ["**/*.wasm"],
  optimizeDeps: {
    exclude: [
      "@huggingface/transformers",
      "onnxruntime-web",
      "libheif-js",
      "mediabunny",
      "@mediabunny/aac-encoder",
    ],
  },
  worker: {
    format: "es",
  },
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "http://127.0.0.1:8080",
        changeOrigin: true,
        ws: true,
      },
    },
  },
});
