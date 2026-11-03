#!/usr/bin/env node
/*
 * Runs every `*.selftest.ts` (and `crypto/selftest.ts`) under Node.
 *
 * Each selftest is a plain script that throws on failure, so the runner bundles it with esbuild,
 * runs it in its own Node process from `web/` (some read `public/` files) with a fresh
 * `localStorage`, and fails when any of them exits non-zero.
 *
 *   npm test                 # all selftests
 *   npm test -- ratchet      # only those whose path contains "ratchet"
 */
import { build } from "esbuild";
import { spawn } from "node:child_process";
import { mkdtemp, readdir, rm } from "node:fs/promises";
import { availableParallelism, tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const webRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const filters = process.argv.slice(2);

async function findSelftests(dir) {
  const found = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) found.push(...(await findSelftests(full)));
    else if (entry.name.endsWith(".selftest.ts") || entry.name === "selftest.ts") found.push(full);
  }
  return found;
}

function run(bundle, storageFile) {
  return new Promise((resolve) => {
    const child = spawn(
      process.execPath,
      ["--no-warnings", "--experimental-webstorage", `--localstorage-file=${storageFile}`, bundle],
      { cwd: webRoot, stdio: ["ignore", "pipe", "pipe"] },
    );
    let output = "";
    child.stdout.on("data", (chunk) => (output += chunk));
    child.stderr.on("data", (chunk) => (output += chunk));
    const timer = setTimeout(() => child.kill("SIGKILL"), 120_000);
    child.on("close", (code, signal) => {
      clearTimeout(timer);
      resolve({ ok: code === 0, output: signal ? `${output}\nkilled by ${signal}` : output });
    });
  });
}

const all = (await findSelftests(path.join(webRoot, "src"))).sort();
const selected = all.filter(
  (file) => filters.length === 0 || filters.some((f) => path.relative(webRoot, file).includes(f)),
);
if (selected.length === 0) {
  console.error(`no selftest matches ${filters.join(", ")}`);
  process.exit(1);
}

const outDir = await mkdtemp(path.join(tmpdir(), "shroud-selftest-"));
let failed = 0;
try {
  const queue = selected.map((file, i) => ({ file, i }));
  const worker = async () => {
    for (let job = queue.shift(); job; job = queue.shift()) {
      const name = path.relative(webRoot, job.file);
      const bundle = path.join(outDir, `${job.i}.mjs`);
      let result;
      try {
        await build({
          entryPoints: [job.file],
          outfile: bundle,
          bundle: true,
          platform: "node",
          format: "esm",
          logLevel: "silent",
          external: ["onnxruntime-node"],
        });
        result = await run(bundle, path.join(outDir, `${job.i}.localstorage`));
      } catch (error) {
        result = { ok: false, output: String(error?.message ?? error) };
      }
      if (result.ok) {
        console.log(`ok    ${name}`);
      } else {
        failed++;
        console.log(`FAIL  ${name}\n${result.output.trimEnd().replace(/^/gm, "      ")}`);
      }
    }
  };
  await Promise.all(Array.from({ length: Math.min(availableParallelism(), 8) }, worker));
} finally {
  await rm(outDir, { recursive: true, force: true });
}

console.log(`\n${selected.length - failed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
