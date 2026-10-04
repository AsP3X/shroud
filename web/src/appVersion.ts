import { api, type ClientVersion, type ClientVersionStatus } from "./api/client";

/*
 * "A new version of Shroud is available." A tab keeps running the bundle it loaded, so after a
 * deploy it asks the server whether its build id is still the deployed one. It asks on load
 * (the sign-in and lock screens too), when the tab is shown again, every half hour while it is
 * in front, and when the realtime socket comes back (a deploy that changes the API restarts it).
 * Mid-deploy the old web container may still answer, so the banner waits until the page a reload
 * would load carries the build the server names (its `shroud-build` meta, from vite.config.ts).
 *
 * Settings → About Shroud reads the same store: whether a question is out, whether the last one
 * failed, the server's version, and a "Check for Updates" that skips the wait between questions.
 *
 * Kept in memory only, like everything this page does not need to seal: a dismissal holds for
 * this tab until the server names a different build.
 */

/** A tab shown again asks at most this often. So does the half-hourly timer. */
export const CHECK_GAP_MS = 10 * 60 * 1000;
export const CHECK_INTERVAL_MS = 30 * 60 * 1000;
/** While the served page is still the old build, ask again this often, for up to ten minutes. */
export const CONFIRM_RETRY_MS = 30 * 1000;
const CONFIRM_RETRIES = 20;

export type CheckReason = "startup" | "visible" | "interval" | "reconnect" | "confirm" | "manual";

/** What the server said, kept only when it is an answer this page understands. */
export type UpdateState = { status: ClientVersionStatus; latest: string | null };

/** The latest build the banner was closed for; `null` while it has not been closed. */
export type Dismissal = { latest: string | null } | null;

/** The About page's status row. `idle`: nothing asked yet, or a build that never asks. */
export type UpdateStatus = "idle" | "checking" | "current" | "available" | "failed";

export type UpdateSnapshot = { status: UpdateStatus; serverVersion: string | null };

/** This bundle's build id, stamped by the deploy (`VITE_WEB_BUILD`). Empty in `npm run dev`. */
export function webBuild(): string {
  return (import.meta.env?.VITE_WEB_BUILD ?? "").trim();
}

/** web/package.json's version, stamped by vite.config.ts. Empty outside a Vite build. */
export function webVersion(): string {
  return (import.meta.env?.VITE_WEB_VERSION ?? "").trim();
}

/**
 * Whether to ask now. Load and a reconnected socket always ask. A hidden tab does not: it asks
 * when it is shown, which waits for the gap since the last question so tab switching is cheap.
 */
export function shouldCheck(reason: CheckReason, now: number, lastCheckAt: number | null, hidden: boolean): boolean {
  if (reason === "startup" || reason === "reconnect" || reason === "confirm" || reason === "manual") return true;
  if (hidden) return false;
  return lastCheckAt == null || now - lastCheckAt >= CHECK_GAP_MS;
}

/** The answer as this page keeps it, or `null` for a status it does not know (ignored). */
export function updateFromAnswer(answer: ClientVersion | null | undefined): UpdateState | null {
  if (!answer || typeof answer !== "object") return null;
  const { status, latest_version: latest } = answer;
  if (status !== "current" && status !== "update_available" && status !== "update_required") return null;
  return { status, latest: typeof latest === "string" && latest ? latest : null };
}

/** The server's own version (`server_version`); older servers don't send it. */
export function serverVersionFromAnswer(answer: ClientVersion | null | undefined): string | null {
  const version = answer && typeof answer === "object" ? answer.server_version : null;
  return typeof version === "string" && version.trim() ? version.trim() : null;
}

/**
 * The About page's row. A newer build shows only once it is confirmed served (`state` holds
 * nothing else), and stays shown when a later question fails: a reload still gets it.
 */
export function updateStatus(
  canCheck: boolean,
  checking: boolean,
  failed: boolean,
  state: UpdateState | null,
): UpdateStatus {
  if (!canCheck) return "idle";
  if (checking) return "checking";
  if (state && state.status !== "current") return "available";
  if (failed) return "failed";
  return state ? "current" : "idle";
}

/** The banner shows for a newer build unless it was closed for that same build. */
export function bannerShown(state: UpdateState | null, dismissed: Dismissal): boolean {
  if (!state || state.status === "current") return false;
  return dismissed == null || dismissed.latest !== state.latest;
}

let state: UpdateState | null = null;
let dismissed: Dismissal = null;
let lastCheckAt: number | null = null;
let inFlight = false;
let failed = false;
let serverVersion: string | null = null;
let confirmRetries = 0;
let installed = false;
let snapshot: UpdateSnapshot = { status: "idle", serverVersion: null };
const listeners = new Set<() => void>();

function emit() {
  const status = updateStatus(Boolean(webBuild()), inFlight, failed, state);
  if (status !== snapshot.status || serverVersion !== snapshot.serverVersion) {
    snapshot = { status, serverVersion };
  }
  for (const listener of listeners) listener();
}

export function subscribeUpdate(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function updateBannerShown(): boolean {
  return bannerShown(state, dismissed);
}

/** For `useSyncExternalStore`: the same object until something on the About page changes. */
export function updateSnapshot(): UpdateSnapshot {
  return snapshot;
}

export function dismissUpdate(): void {
  dismissed = { latest: state?.latest ?? null };
  emit();
}

/** The build the page a reload would load was made from; `null` when it can't be read. */
async function servedBuild(): Promise<string | null> {
  const response = await fetch(`${import.meta.env.BASE_URL}index.html`, { cache: "no-store" });
  if (!response.ok) return null;
  const html = await response.text();
  return /<meta name="shroud-build" content="([^"]*)"/.exec(html)?.[1] ?? null;
}

/**
 * Asks the server when `reason` calls for it. A failed question keeps what the last one said.
 * A newer build the page a reload would load doesn't carry yet changes nothing either, manual
 * check included: the row keeps saying what it said, and the retries flip it once it is served.
 */
export function checkForUpdate(reason: CheckReason): void {
  const build = webBuild();
  if (!build || inFlight) return;
  const now = Date.now();
  const hidden = typeof document !== "undefined" && document.hidden;
  if (!shouldCheck(reason, now, lastCheckAt, hidden)) return;
  const previousCheckAt = lastCheckAt;
  // A fresh question gets a fresh run of retries; only the retries themselves use them up.
  if (reason !== "confirm") confirmRetries = 0;
  inFlight = true;
  lastCheckAt = now;
  emit();
  void (async () => {
    try {
      const answer = await api.clientVersion("web", build);
      serverVersion = serverVersionFromAnswer(answer);
      const next = updateFromAnswer(answer);
      // An answer this page doesn't understand is no answer.
      failed = !next;
      if (!next) return;
      // Mid-deploy the old web container still answers: a reload now would load this build again.
      if (next.status !== "current" && next.latest && (await servedBuild().catch(() => null)) !== next.latest) {
        if (confirmRetries < CONFIRM_RETRIES) {
          confirmRetries += 1;
          window.setTimeout(() => checkForUpdate("confirm"), CONFIRM_RETRY_MS);
        }
        return;
      }
      confirmRetries = 0;
      if (next.status === state?.status && next.latest === state.latest) return;
      state = next;
    } catch {
      // Offline, or an older server: a tab shown again soon asks again.
      failed = true;
      lastCheckAt = previousCheckAt;
      if (reason === "confirm" && confirmRetries < CONFIRM_RETRIES) {
        confirmRetries += 1;
        window.setTimeout(() => checkForUpdate("confirm"), CONFIRM_RETRY_MS);
      }
    } finally {
      inFlight = false;
      emit();
    }
  })();
}

/** Once, before the first render. A build without an id (`npm run dev`) never asks. */
export function installUpdateCheck(): void {
  if (installed || !webBuild()) return;
  installed = true;
  checkForUpdate("startup");
  document.addEventListener("visibilitychange", () => checkForUpdate("visible"));
  window.setInterval(() => checkForUpdate("interval"), CHECK_INTERVAL_MS);
}
