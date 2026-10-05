import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { bundleLicenses } from "./bundleLicenses";
import { pdfjsAssets } from "./pdfjsAssets";

const root = fileURLToPath(new URL(".", import.meta.url));

// The deploy's build id (web/Dockerfile → VITE_WEB_BUILD). It is also written into index.html, so
// a tab offers a reload only once the page a reload would load carries it (src/appVersion.ts).
const webBuild = (process.env.VITE_WEB_BUILD ?? "").trim();
// The release Settings → About Shroud shows: package.json's version.
const webVersion = String(JSON.parse(readFileSync(new URL("./package.json", import.meta.url), "utf8")).version);

const licenses = bundleLicenses(root);

export default defineConfig({
  plugins: [
    react(),
    {
      name: "shroud-build-meta",
      transformIndexHtml: () =>
        webBuild ? [{ tag: "meta", attrs: { name: "shroud-build", content: webBuild }, injectTo: "head" }] : [],
    },
    licenses.page,
    // The PDF viewer's CMaps, fonts and decoders, on this origin (src/media/pdfjs.ts).
    pdfjsAssets(root),
  ],
  define: {
    "import.meta.env.VITE_WEB_VERSION": JSON.stringify(webVersion),
  },
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
    plugins: () => [licenses.worker()],
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
