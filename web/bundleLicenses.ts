import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import type { Plugin } from "vite";

/*
 * licenses.json for Settings → About Shroud → Open-Source Licenses: every third-party package
 * whose code ends up in this build, read afresh on each one. The npm packages are the ones the
 * page's chunks and its workers' chunks were made from (workers are separate builds, so the
 * plugin runs in those too and the page's build writes the file). The link-preview TLS module's
 * Rust crates come from scripts/build-link-tls.sh, which lists them next to the module; they are
 * added only when the module is in the build.
 */

export type LicensedPackage = {
  name: string;
  version: string;
  /** SPDX expression from the package's own manifest. */
  license: string;
  source: "npm" | "crates.io" | "model";
  /** Index into `texts`, or `null` when the package ships no license file. */
  text: number | null;
};

/** Texts are shared: many packages carry the same license word for word. */
export type LicensesFile = { packages: LicensedPackage[]; texts: string[] };

const LICENSE_FILE = /^(licen[sc]e|copying|notice)([-_.].*)?$/i;
const TLS_DIR = "src/linkPreview/tls";
/**
 * Machine-learning models bundled as files, each in a folder with its LICENSE: the face detector
 * for Center Stage (src/calls/faceWorker.ts).
 */
const MODELS: { dir: string; file: string; name: string; version: string; license: string }[] = [
  {
    dir: "src/calls/models",
    file: "face_detection_yunet_2023mar.onnx",
    name: "YuNet face detector (libfacedetection)",
    version: "2023mar",
    license: "MIT",
  },
];

/*
 * MIT packages published without a license file: the text of their repository's LICENSE. A
 * package that lands here only when the file is missing from what npm installed.
 */
const MIT_HOLDERS: Record<string, string> = {
  // github.com/microsoft/onnxruntime/blob/main/LICENSE
  "onnxruntime-common": "Microsoft Corporation",
  "onnxruntime-web": "Microsoft Corporation",
};

function mitText(holder: string): string {
  return `MIT License

Copyright (c) ${holder}

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.`;
}

/** `/…/node_modules/@scope/name/dist/x.js?url` → `/…/node_modules/@scope/name` (the last one). */
export function packageRoot(id: string): string | null {
  const path = id.replace(/^\0/, "").split("?")[0];
  const at = path.lastIndexOf("/node_modules/");
  if (at < 0) return null;
  const start = at + "/node_modules/".length;
  const parts = path.slice(start).split("/");
  const depth = parts[0].startsWith("@") ? 2 : 1;
  if (parts.length <= depth) return null;
  return path.slice(0, start) + parts.slice(0, depth).join("/");
}

/** The package's license files, one after another, each under its name when there are several. */
function licenseText(dir: string): string | null {
  let files: string[];
  try {
    files = readdirSync(dir).filter((file) => LICENSE_FILE.test(file) && statSync(join(dir, file)).isFile());
  } catch {
    return null;
  }
  files.sort();
  const texts = files.map((file) => readFileSync(join(dir, file), "utf8").replace(/\r\n/g, "\n").trim());
  if (texts.length === 0) return null;
  if (texts.length === 1) return texts[0];
  return files.map((file, i) => `${file}\n\n${texts[i]}`).join("\n\n\n");
}

/** `license` is an SPDX string; very old manifests use `licenses: [{ type }]`. */
function manifestLicense(manifest: { license?: unknown; licenses?: unknown }): string {
  if (typeof manifest.license === "string" && manifest.license.trim()) return manifest.license.trim();
  if (manifest.license && typeof manifest.license === "object" && "type" in manifest.license) {
    return String((manifest.license as { type: unknown }).type);
  }
  if (Array.isArray(manifest.licenses)) {
    const types = manifest.licenses.map((entry) => (entry as { type?: unknown })?.type).filter(Boolean);
    if (types.length) return types.join(" OR ");
  }
  return "UNKNOWN";
}

export function bundleLicenses(root: string): { page: Plugin; worker: () => Plugin } {
  const moduleIds = new Set<string>();

  function collect(bundle: Record<string, { type: string; modules?: Record<string, unknown> }>) {
    for (const output of Object.values(bundle)) {
      // Every module placed in a chunk, CSS ones included (their code is gone, their fonts stay).
      if (output.type === "chunk" && output.modules) {
        for (const id of Object.keys(output.modules)) moduleIds.add(id);
      }
    }
  }

  const worker = (): Plugin => ({
    name: "shroud-bundle-licenses-worker",
    apply: "build",
    generateBundle(_options, bundle) {
      collect(bundle);
    },
  });

  const page: Plugin = {
    name: "shroud-bundle-licenses",
    apply: "build",
    generateBundle(_options, bundle) {
      collect(bundle);
      const texts: string[] = [];
      const textIndex = new Map<string, number>();
      const addText = (text: string | null): number | null => {
        if (text == null) return null;
        let index = textIndex.get(text);
        if (index === undefined) {
          index = texts.push(text) - 1;
          textIndex.set(text, index);
        }
        return index;
      };

      const packages = new Map<string, LicensedPackage>();
      const roots = new Set<string>();
      let tlsBundled = false;
      const modelsBundled = new Set<string>();
      for (const id of moduleIds) {
        for (const model of MODELS) if (id.split("?")[0].endsWith(`/${model.dir}/${model.file}`)) modelsBundled.add(model.file);
        const dir = packageRoot(id);
        if (dir) roots.add(dir);
        else if (id.split("?")[0].includes(`/${TLS_DIR}/`)) tlsBundled = true;
      }
      for (const dir of roots) {
        const manifest = JSON.parse(readFileSync(join(dir, "package.json"), "utf8"));
        const name = String(manifest.name);
        const version = String(manifest.version);
        const license = manifestLicense(manifest);
        const holder = license === "MIT" ? MIT_HOLDERS[name] : undefined;
        const text = licenseText(dir) ?? (holder ? mitText(holder) : null);
        if (license === "UNKNOWN") this.warn(`${name}@${version} names no license`);
        if (text == null) this.warn(`${name}@${version} ships no license file`);
        packages.set(`npm:${name}@${version}`, { name, version, license, source: "npm", text: addText(text) });
      }

      const tlsLicenses = join(root, TLS_DIR, "licenses");
      if (tlsBundled) {
        const index = join(tlsLicenses, "index.tsv");
        if (!existsSync(index)) {
          this.warn("the link-preview TLS module has no crate list: run `npm run build:tls` again");
        } else {
          for (const line of readFileSync(index, "utf8").split("\n")) {
            const [name, version, license] = line.split("\t");
            if (!name || !version) continue;
            const text = licenseText(join(tlsLicenses, `${name}-${version}`));
            if (text == null) this.warn(`crate ${name} ${version} has no license file`);
            packages.set(`crate:${name}@${version}`, {
              name,
              version,
              license: license || "UNKNOWN",
              source: "crates.io",
              text: addText(text),
            });
          }
        }
      }

      for (const model of MODELS) {
        if (!modelsBundled.has(model.file)) continue;
        const text = licenseText(join(root, model.dir));
        if (text == null) this.warn(`model ${model.name} has no license file`);
        packages.set(`model:${model.name}@${model.version}`, {
          name: model.name,
          version: model.version,
          license: model.license,
          source: "model",
          text: addText(text),
        });
      }

      const file: LicensesFile = {
        packages: [...packages.values()].sort(
          (a, b) =>
            a.source.localeCompare(b.source) ||
            a.name.localeCompare(b.name, "en", { sensitivity: "base" }) ||
            a.version.localeCompare(b.version),
        ),
        texts,
      };
      this.emitFile({ type: "asset", fileName: "licenses.json", source: JSON.stringify(file) });
      moduleIds.clear();
    },
  };

  return { page, worker };
}
