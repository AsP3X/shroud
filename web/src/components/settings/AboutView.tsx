import { useEffect, useId, useState, useSyncExternalStore, type ReactNode } from "react";
import {
  Check,
  ChevronDown,
  CodeXml,
  Download,
  ExternalLink,
  Lock,
  RefreshCw,
  ScrollText,
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
import { SettingsGroup, SettingsNote, SettingsRow } from "./SettingsRow";
import type { SettingsRoute } from "./routes";

const SOURCE_URL = "https://github.com/AsP3X/shroud";

/** The host the API answers on: this page's own when it is reverse proxied (Settings → Server). */
function serverHost(): string {
  const base = apiBase();
  if (base.startsWith("/")) return window.location.host;
  try {
    return new URL(base).host;
  } catch {
    return base;
  }
}

export function useUpdateSnapshot() {
  return useSyncExternalStore(subscribeUpdate, updateSnapshot);
}

const STATUS: Record<UpdateStatus, { text: string; tile: string; icon: ReactNode }> = {
  idle: { text: "Updates are checked automatically", tile: "set-tile-muted", icon: <RefreshCw size={15} /> },
  checking: { text: "Checking for updates…", tile: "set-tile-muted", icon: <span className="dev-spinner" /> },
  current: { text: "Shroud is up to date", tile: "about-tile-ok", icon: <Check size={15} /> },
  available: { text: "A new version is available", tile: "about-tile-accent", icon: <Download size={15} /> },
  failed: { text: "Couldn’t check for updates", tile: "about-tile-failed", icon: <TriangleAlert size={15} /> },
};

export function AboutView({ onNavigate }: { onNavigate: (route: SettingsRoute) => void }) {
  const { status, serverVersion } = useUpdateSnapshot();
  const build = webBuild();
  const shown = STATUS[status];

  return (
    <>
      <header className="set-hero about-hero">
        <BrandMark size={80} />
        <h1>Shroud</h1>
        <p>
          Version {webVersion()} ({build ? `build ${build.slice(0, 12)}` : "development"})
        </p>
      </header>

      <SettingsGroup title="Updates">
        <div className="set-row about-status">
          <span className={`set-tile ${shown.tile}`} aria-hidden="true">
            {shown.icon}
          </span>
          {/* Read out as it changes: a check started here ends here. */}
          <span className="set-row-copy" role="status">
            <strong>{shown.text}</strong>
          </span>
          {status === "available" ? (
            <button type="button" className="about-reload" onClick={() => window.location.reload()}>
              Reload
            </button>
          ) : null}
        </div>
        <SettingsRow
          title="Check for Updates"
          Icon={RefreshCw}
          tint="var(--accent)"
          // A build without an id (`npm run dev`) has nothing to compare.
          disabled={!build}
          onClick={() => checkForUpdate("manual")}
        />
      </SettingsGroup>

      <SettingsGroup title="Server">
        <div className="set-row">
          <span className="set-row-copy">
            <strong>Address</strong>
          </span>
          <span className="set-row-value about-value">{serverHost()}</span>
        </div>
        <div className="set-row">
          <span className="set-row-copy">
            <strong>Server version</strong>
          </span>
          <span className="set-row-value about-value">{serverVersion ?? "—"}</span>
        </div>
      </SettingsGroup>

      <SettingsGroup title="Privacy">
        <div className="set-row about-privacy">
          <span className="set-tile" style={{ background: "#2fa85b" }} aria-hidden="true">
            <Lock size={15} />
          </span>
          <p>
            Messages, media and calls are end-to-end encrypted. They’re sealed on your devices, so
            the server passes them on without being able to read them.
          </p>
        </div>
      </SettingsGroup>

      <SettingsGroup title="More">
        <a className="set-row set-row-button" href={SOURCE_URL} target="_blank" rel="noopener noreferrer">
          <span className="set-tile" style={{ background: "var(--text-tertiary)" }} aria-hidden="true">
            <CodeXml size={15} />
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
          tint="#f76b1c"
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
