import { createReadStream, existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { extname, join, normalize, sep } from "node:path";
import type { Plugin } from "vite";

/*
 * pdf.js's data files, served from Shroud's own origin (docs/file-sharing.md §10.2: the CSP allows
 * nothing else): the CMaps, the standard fonts, the CMYK ICC profile and the WebAssembly image and
 * colour decoders (with their no-wasm JS fallbacks). `src/media/pdfjs.ts` points pdf.js at
 * `/pdfjs/{version}/`, so a new release never mixes with an old one in a cache.
 *
 * Left out: QuickJS (`quickjs-eval.*`), the sandbox pdf.js would run a document's JavaScript in.
 * Shroud never turns scripting on.
 */

const DIRS = ["cmaps", "standard_fonts", "iccs", "wasm"];
const SKIPPED = /^quickjs-eval\./;

const TYPES: Record<string, string> = {
  ".wasm": "application/wasm",
  ".js": "text/javascript",
  ".ttf": "font/ttf",
};

function walk(dir: string, prefix: string, out: string[]): void {
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) walk(path, `${prefix}${entry}/`, out);
    else if (!SKIPPED.test(entry)) out.push(`${prefix}${entry}`);
  }
}

export function pdfjsAssets(root: string): Plugin {
  const pkgDir = join(root, "node_modules", "pdfjs-dist");
  const version = String(JSON.parse(readFileSync(join(pkgDir, "package.json"), "utf8")).version);
  const base = `pdfjs/${version}/`;

  function files(): string[] {
    const out: string[] = [];
    for (const dir of DIRS) {
      const path = join(pkgDir, dir);
      if (existsSync(path)) walk(path, `${dir}/`, out);
    }
    return out;
  }

  return {
    name: "shroud-pdfjs-assets",
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const url = (req.url ?? "").split("?")[0];
        const at = url.indexOf(`/${base}`);
        if (at !== 0) return next();
        const rel = normalize(decodeURIComponent(url.slice(base.length + 1)));
        const [dir] = rel.split(sep);
        const path = join(pkgDir, rel);
        if (!DIRS.includes(dir) || rel.includes("..") || SKIPPED.test(rel) || !existsSync(path) || statSync(path).isDirectory()) {
          res.statusCode = 404;
          res.end();
          return;
        }
        res.setHeader("Content-Type", TYPES[extname(path)] ?? "application/octet-stream");
        createReadStream(path).pipe(res);
      });
    },
    generateBundle() {
      for (const file of files()) {
        this.emitFile({ type: "asset", fileName: `${base}${file}`, source: readFileSync(join(pkgDir, file)) });
      }
    },
  };
}
