import { useEffect, useId, useState, useSyncExternalStore, type ReactNode } from "react";
import {
  Cable,
  Check,
  ChevronDown,
  CodeXml,
  Download,
  ExternalLink,
  RefreshCw,
  ScrollText,
  Server,
  Tag,
  TriangleAlert,
} from "lucide-react";
import {
  checkForUpdate,
  subscribeUpdate,
  updateSnapshot,
  webVersion,
  type UpdateStatus,
} from "../../appVersion";
import { apiBase, webBuild } from "../../config";
import { BrandMark } from "../BrandMark";
import { SettingsGroup, SettingsHero, SettingsNote, SettingsRow } from "./SettingsRow";
import type { SettingsRoute } from "./routes";

const SOURCE_URL = "https://github.com/AsP3X/shroud";

/** The host the API answers on: this page's own when it is reverse proxied (Settings → About). */
export function serverHost(): string {
  const base = apiBase();
  if (base.startsWith("/")) return window.location.host;
  try {
    return new URL(base).host;
  } catch {
    return base;
  }
}

/** The full API endpoint, with this page's origin filled in when it is reverse proxied. */
function serverEndpoint(): string {
  const base = apiBase();
  return base.startsWith("/") ? `${window.location.origin}${base}` : base;
}

export function useUpdateSnapshot() {
  return useSyncExternalStore(subscribeUpdate, updateSnapshot);
}

const STATUS: Record<UpdateStatus, { text: string; tone: string; icon: ReactNode }> = {
  idle: { text: "Updates are checked automatically", tone: "", icon: <RefreshCw size={14} /> },
  checking: { text: "Checking for updates…", tone: "", icon: <span className="dev-spinner" /> },
  current: { text: "Up to date", tone: "ok", icon: <Check size={14} /> },
  available: { text: "A new version is ready", tone: "accent", icon: <Download size={14} /> },
  failed: { text: "Couldn’t check for updates", tone: "failed", icon: <TriangleAlert size={14} /> },
};

export function AboutView({ onNavigate }: { onNavigate: (route: SettingsRoute) => void }) {
  const { status, serverVersion } = useUpdateSnapshot();
  const build = webBuild();
  const shown = STATUS[status];

  return (
    <>
      <SettingsHero
        figure={
          <span className="set-hero-mark" aria-hidden="true">
            <BrandMark size={40} />
          </span>
        }
        title={`Shroud ${webVersion()}`}
        lines={[
          build ? `Build ${build.slice(0, 12)}` : "Development build",
          /* Read out as it changes: a check started here ends here. */
          <span key="status" className={`set-status ${shown.tone}`} role="status">
            {shown.icon}
            {shown.text}
          </span>,
        ]}
        actions={
          status === "available" ? (
            <button type="button" className="set-action primary" onClick={() => window.location.reload()}>
              <RefreshCw size={16} aria-hidden="true" />
              Reload to update
            </button>
          ) : (
            <button
              type="button"
              className="set-action"
              // A build without an id (`npm run dev`) has nothing to compare.
              disabled={!build || status === "checking"}
              onClick={() => checkForUpdate("manual")}
            >
              <RefreshCw size={16} aria-hidden="true" />
              Check for updates
            </button>
          )
        }
      />

      <SettingsGroup title="Server">
        <div className="set-row">
          <span className="set-ico" aria-hidden="true">
            <Server size={20} />
          </span>
          <span className="set-row-copy">
            <strong>{serverHost()}</strong>
            <code>{serverEndpoint()}</code>
          </span>
        </div>
        <SettingsRow title="Server version" Icon={Tag} value={serverVersion ?? "—"} />
        <SettingsRow
          title="Connection"
          Icon={Cable}
          value={apiBase().startsWith("/") ? "Same origin, reverse proxied" : "Configured origin"}
        />
      </SettingsGroup>
      <SettingsNote>
        The server only ever sees ciphertext. This client talks to whichever server served the
        page; to use another, deploy it against that server’s API.
      </SettingsNote>

      <SettingsGroup title="More">
        <a className="set-row set-row-button" href={SOURCE_URL} target="_blank" rel="noopener noreferrer">
          <span className="set-ico" aria-hidden="true">
            <CodeXml size={20} />
          </span>
          <span className="set-row-copy">
            <strong>Source Code</strong>
          </span>
          <span className="sr-only">(opens in a new tab)</span>
          <ExternalLink size={16} className="set-chevron" aria-hidden="true" />
        </a>
        <SettingsRow
          title="Open-Source Licenses"
          Icon={ScrollText}
          onClick={() => onNavigate("licenses")}
        />
      </SettingsGroup>
    </>
  );
}

/** One entry of `licenses.json` (vite.config.ts → bundleLicenses.ts writes it on every build). */
type LicensedPackage = {
  name: string;
  version: string;
  license: string;
  source: "npm" | "crates.io" | "model";
  text: number | null;
};
type LicensesFile = { packages: LicensedPackage[]; texts: string[] };

type LicensesState =
  | { kind: "loading" }
  | { kind: "missing" }
  | { kind: "failed" }
  | { kind: "ready"; file: LicensesFile };

function isLicensesFile(value: unknown): value is LicensesFile {
  if (!value || typeof value !== "object") return false;
  const { packages, texts } = value as Partial<LicensesFile>;
  return Array.isArray(packages) && Array.isArray(texts);
}

const SOURCES: { source: LicensedPackage["source"]; title: string }[] = [
  { source: "npm", title: "npm packages" },
  { source: "crates.io", title: "Rust crates" },
  { source: "model", title: "Models" },
];

export function LicensesView() {
  const [state, setState] = useState<LicensesState>(() =>
    import.meta.env.DEV ? { kind: "missing" } : { kind: "loading" },
  );
  const [open, setOpen] = useState<string | null>(null);
  const idBase = useId();

  useEffect(() => {
    // `npm run dev` has no file: the list is made while building.
    if (import.meta.env.DEV) return;
    let cancelled = false;
    // Revalidated: nginx serves it without a cache header, and the next deploy changes it.
    fetch(`${import.meta.env.BASE_URL}licenses.json`, { cache: "no-cache" })
      .then(async (response) => {
        const file: unknown = response.ok ? await response.json().catch(() => null) : null;
        if (!cancelled) setState(isLicensesFile(file) ? { kind: "ready", file } : { kind: "missing" });
      })
      .catch(() => {
        if (!cancelled) setState({ kind: "failed" });
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (state.kind !== "ready") {
    return (
      <div className="set-card">
        {state.kind === "loading" ? (
          <div className="dev-state">
            <span className="dev-spinner" aria-hidden="true" />
            <p role="status">Loading licenses…</p>
          </div>
        ) : (
          <p className="set-placeholder" role="status">
            {state.kind === "missing" ? "Licenses are listed in production builds" : "Couldn’t load the licenses"}
          </p>
        )}
      </div>
    );
  }

  const { packages, texts } = state.file;
  return (
    <>
      {SOURCES.map(({ source, title }) => {
        const listed = packages.filter((pkg) => pkg.source === source);
        if (listed.length === 0) return null;
        return (
          <SettingsGroup key={source} title={title}>
            {listed.map((pkg) => {
              const key = `${pkg.source}:${pkg.name}@${pkg.version}`;
              const expanded = open === key;
              const panel = `${idBase}-${packages.indexOf(pkg)}`;
              const text = pkg.text == null ? null : texts[pkg.text];
              return (
                <div key={key} className="lic-item">
                  <button
                    type="button"
                    className="set-row set-row-button"
                    aria-expanded={expanded}
                    aria-controls={panel}
                    onClick={() => setOpen(expanded ? null : key)}
                  >
                    <span className="set-row-copy">
                      <strong>{pkg.name}</strong>
                      <span>
                        {pkg.version} · {pkg.license}
                      </span>
                    </span>
                    <ChevronDown size={16} className="set-chevron lic-chevron" aria-hidden="true" />
                  </button>
                  {expanded ? (
                    <pre id={panel} className="lic-text" tabIndex={0} aria-label={`${pkg.name} license`}>
                      {text ?? `This package ships no license file. Its manifest names ${pkg.license}.`}
                    </pre>
                  ) : null}
                </div>
              );
            })}
          </SettingsGroup>
        );
      })}
      <SettingsNote>
        Shroud is built with these open-source components. Select one to read its license.
      </SettingsNote>
    </>
  );
}
