import { readdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { cpus } from "node:os";
import { extname, join, relative, resolve } from "node:path";
import { promisify } from "node:util";
import { brotliCompress, constants, gzip } from "node:zlib";
import type { Plugin } from "vite";

/*
 * Compressed copies of the build's immutable files, written next to them once per build: `x.js.gz`
 * (gzip level 9) and `x.js.br` (Brotli quality 11). nginx serves them as they are (`gzip_static`,
 * and `brotli_static` from the module web/Dockerfile builds), so a browser gets the smallest copy
 * it accepts and nginx never compresses these files per request. At the levels nginx would use on
 * the fly they come out far larger: the ONNX runtime's WebAssembly is 7.9 MB at nginx's default
 * gzip level, 6.7 MB here as .gz and 4.0 MB as .br.
 *
 * Only `assets/` (content-hashed names) and `pdfjs/{version}/` are compressed: a file there never
 * changes under its name, so a copy can't go stale. Root files (config.js, sw.js, licenses.json, …)
 * stay with nginx's on-the-fly gzip, which also keeps a file an operator swaps into the image from
 * being shadowed by an old copy.
 */

const DIRS = ["assets", "pdfjs"];

/** Text formats Brotli is tuned for in text mode. */
const TEXT = new Set([".js", ".mjs", ".css", ".json", ".svg", ".html", ".txt"]);
/** Formats that compress; images, video, woff2 and the like are already compressed. */
const COMPRESSIBLE = new Set([...TEXT, ".wasm", ".onnx", ".bcmap", ".pfb", ".ttf", ".otf", ".icc"]);

/** Smaller files gain little and cost a request header either way (nginx's own gzip_min_length). */
const MIN_BYTES = 1024;
/** A copy is kept only when it saves at least this share of the file. */
const MIN_SAVING = 0.1;
/**
 * Files above this are compressed one at a time: Brotli 11 holds about eight times the file in
 * memory (230 MB for the 28 MB ONNX runtime WebAssembly), and the image is built on the deploy
 * server, which may be a small VPS.
 */
const LARGE_BYTES = 4 * 1024 * 1024;

const gzipAsync = promisify(gzip);
const brotliAsync = promisify(brotliCompress);

function walk(dir: string, out: string[]): void {
  let entries: string[];
  try {
    entries = readdirSync(dir);
  } catch {
    return;
  }
  for (const entry of entries) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) walk(path, out);
    else out.push(path);
  }
}

async function compress(path: string): Promise<{ raw: number; gz: number; br: number }> {
  const source = readFileSync(path);
  const keep = (copy: Buffer) => copy.length <= source.length * (1 - MIN_SAVING);
  const [gz, br] = await Promise.all([
    gzipAsync(source, { level: 9 }),
    brotliAsync(source, {
      params: {
        // The default 4 MB window: the largest one (16 MB) saves 3% on the WebAssembly files but
        // needs 70% more memory.
        [constants.BROTLI_PARAM_QUALITY]: constants.BROTLI_MAX_QUALITY,
        [constants.BROTLI_PARAM_MODE]: TEXT.has(extname(path)) ? constants.BROTLI_MODE_TEXT : constants.BROTLI_MODE_GENERIC,
        [constants.BROTLI_PARAM_SIZE_HINT]: source.length,
      },
    }),
  ]);
  if (keep(gz)) writeFileSync(`${path}.gz`, gz);
  if (keep(br)) writeFileSync(`${path}.br`, br);
  return { raw: source.length, gz: keep(gz) ? gz.length : source.length, br: keep(br) ? br.length : source.length };
}

export function precompress(): Plugin {
  let outDir = "";
  return {
    name: "shroud-precompress",
    apply: "build",
    configResolved(config) {
      outDir = resolve(config.root, config.build.outDir);
    },
    // After the bundle and the public folder are written; worker chunks are part of the page's
    // bundle by then.
    async closeBundle() {
      const paths: string[] = [];
      for (const dir of DIRS) walk(join(outDir, dir), paths);
      const files: { path: string; size: number }[] = [];
      for (const path of paths) {
        const size = statSync(path).size;
        if (COMPRESSIBLE.has(extname(path)) && size >= MIN_BYTES) files.push({ path, size });
      }
      // Largest first, so the long jobs don't start last and leave one lane finishing alone. The
      // large files get a lane of their own (one at a time); zlib runs on libuv's thread pool, so
      // a few small lanes keep its other threads busy meanwhile.
      files.sort((a, b) => b.size - a.size);
      const large = files.filter(({ size }) => size > LARGE_BYTES).map(({ path }) => path);
      const small = files.filter(({ size }) => size <= LARGE_BYTES).map(({ path }) => path);

      const totals = { files: files.length, raw: 0, gz: 0, br: 0 };
      const lane = async (queue: string[]) => {
        for (let path = queue.shift(); path; path = queue.shift()) {
          try {
            const sizes = await compress(path);
            totals.raw += sizes.raw;
            totals.gz += sizes.gz;
            totals.br += sizes.br;
          } catch (error) {
            // Without a copy nginx compresses the file on the fly; the build still works.
            this.warn(`precompress: ${relative(outDir, path)}: ${String(error)}`);
          }
        }
      };
      const smallLanes = Math.max(1, Math.min(3, cpus().length - 1));
      await Promise.all([lane(large), ...Array.from({ length: smallLanes }, () => lane(small))]);

      const mb = (bytes: number) => (bytes / 1_048_576).toFixed(1);
      this.info(`precompressed ${totals.files} files: ${mb(totals.raw)} MB → ${mb(totals.gz)} MB gzip, ${mb(totals.br)} MB brotli`);
    },
  };
}
