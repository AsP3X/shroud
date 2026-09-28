import { useEffect, useRef, useState, type CSSProperties } from "react";
import { Check, Laptop, LoaderCircle, ShieldCheck, TriangleAlert } from "lucide-react";
import type { Session } from "../api/client";
import {
  beginWipe,
  runWipeStep,
  WIPE_STEPS,
  type Leftover,
  type StepResult,
  type WipeInventory,
  type WipeStepId,
} from "../deviceWipe";

/**
 * Why the browser is being cleared: the user logged out, the server ended the session, or this
 * browser was removed from the account's Devices list on another device.
 */
export type WipeReason = "logout" | "ended" | "removed";

const LEAD: Record<WipeReason, string> = {
  logout: "",
  ended: "Your session ended. ",
  removed: "This browser was removed from your account. ",
};

type RowState = "pending" | "active" | "done" | "failed";

const LABELS: Record<WipeStepId, string> = {
  session: "Signing out",
  messages: "Messages & previews",
  media: "Photos, videos & voice",
  keys: "Encryption keys",
  settings: "Settings & PIN",
  verify: "Checking nothing is left",
};

/*
 * The work is milliseconds; a row that ticks faster than it can be read shows nothing. Each
 * step starts when its row lights up and finishes no sooner than this, so the list reads as
 * the wipe happening rather than a flash. Reduced motion keeps the order and drops the wait.
 */
const STEP_MS = 420;
const VERIFY_MS = 560;
const DONE_HOLD_MS = 1400;
const REDUCED_STEP_MS = 120;
const REDUCED_DONE_HOLD_MS = 900;

/** Data leaving the device: dots that drift out of the emblem and fade. */
const PARTICLES = [
  { dx: 46, dy: -30, size: 6, delay: 0 },
  { dx: 54, dy: 10, size: 4, delay: 0.5 },
  { dx: -48, dy: -22, size: 5, delay: 0.25 },
  { dx: -52, dy: 18, size: 4, delay: 0.9 },
  { dx: 30, dy: 44, size: 5, delay: 1.2 },
  { dx: -14, dy: -52, size: 3, delay: 0.7 },
];

function detailFor(step: WipeStepId, result: StepResult): string {
  switch (step) {
    case "session":
      return result.offline ? "Ended here · server offline" : "Session ended";
    case "messages":
    case "keys":
      return result.removed > 0 ? `${result.removed} removed` : "None stored";
    case "media":
      if (result.removed === 0) return "None stored";
      return result.removed === 1 ? "1 file" : `${result.removed} files`;
    case "settings":
      return "Cleared";
    case "verify":
      return "Nothing left";
  }
}

function labelsOf(found: Leftover[]): string {
  return [...new Set(found.map((leftover) => leftover.label))].join(", ");
}

function wait(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, Math.max(0, ms)));
}

function prefersReducedMotion(): boolean {
  return window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;
}

/** The page reloads onto the welcome screen, which also drops everything held in memory. */
function leaveForWelcome(): void {
  window.location.replace("/");
}

/**
 * Clears this browser of the account, step by step, and says so. Every row is real work (see
 * `deviceWipe.ts`) and the last one proves the result; only then does the dialog call the
 * browser clear. It cannot be dismissed — there is no half-cleared state worth going back to.
 */
export function DeviceWipeDialog({
  session,
  reason,
  continued = false,
  onFinished = leaveForWelcome,
}: {
  session: Session;
  reason: WipeReason;
  /** Follows the logout confirmation: the scrim is already up, so it must not fade in again. */
  continued?: boolean;
  onFinished?: () => void;
}) {
  const [active, setActive] = useState<WipeStepId | null>(null);
  const [results, setResults] = useState<Partial<Record<WipeStepId, StepResult>>>({});
  const [leftovers, setLeftovers] = useState<Leftover[] | null>(null);
  /** Rows whose data was still here, running again under "Try again". */
  const [retrying, setRetrying] = useState<WipeStepId[]>([]);
  const [done, setDone] = useState(false);
  const [announcement, setAnnouncement] = useState("");
  const card = useRef<HTMLDivElement>(null);
  const retryButton = useRef<HTMLButtonElement>(null);
  const inventory = useRef<WipeInventory | null>(null);
  const finish = useRef(onFinished);
  finish.current = onFinished;
  // A forced sign-out still has the token in memory. Logout revokes it and drops the push
  // token; a 401 from that call means the server had already ended it. A locked page has no
  // token to send (it is sealed): there is nothing left to end, and the step says so.
  const token = session.token;

  async function runStep(step: WipeStepId, reduce: boolean): Promise<StepResult> {
    setActive(step);
    const began = performance.now();
    const result = await runWipeStep(step, { token, inventory: inventory.current! });
    const floor = reduce ? REDUCED_STEP_MS : step === "verify" ? VERIFY_MS : STEP_MS;
    await wait(floor - (performance.now() - began));
    setResults((prev) => ({ ...prev, [step]: result }));
    return result;
  }

  async function complete(reduce: boolean) {
    setActive(null);
    setDone(true);
    setAnnouncement("This browser is clear. Nothing from your account is left on it.");
    await wait(reduce ? REDUCED_DONE_HOLD_MS : DONE_HOLD_MS);
    finish.current();
  }

  function fail(found: Leftover[]) {
    setActive(null);
    setRetrying([]);
    setLeftovers(found);
    setAnnouncement(`Some data could not be removed: ${labelsOf(found)}.`);
  }

  // StrictMode mounts, unmounts, then mounts again, and a ref does not survive that. The
  // timeout is cleared on the throwaway mount, so the wipe starts once, on the instance that
  // stays. A second start would count an already-empty store.
  useEffect(() => {
    const id = window.setTimeout(() => {
      void (async () => {
        try {
          const reduce = prefersReducedMotion();
          inventory.current = await beginWipe();
          for (const step of WIPE_STEPS) {
            const result = await runStep(step, reduce);
            if (result.leftovers?.length) return fail(result.leftovers);
            setAnnouncement(`${LABELS[step]}: ${detailFor(step, result)}.`);
          }
          await complete(reduce);
        } catch {
          fail([{ step: "settings", label: "browser data" }]);
        }
      })();
    }, 0);
    return () => window.clearTimeout(id);
  }, []);

  async function retry() {
    setRetrying((leftovers ?? []).map((leftover) => leftover.step));
    setLeftovers(null);
    const reduce = prefersReducedMotion();
    try {
      const result = await runStep("verify", reduce);
      if (result.leftovers?.length) return fail(result.leftovers);
      setRetrying([]);
      await complete(reduce);
    } catch {
      fail([{ step: "settings", label: "browser data" }]);
    }
  }

  /* Nothing behind the dialog is reachable while it runs: Escape is swallowed before any
     screen-level handler sees it, and Tab stays on the dialog's own buttons (if any). */
  useEffect(() => {
    card.current?.focus({ preventScroll: true });
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        event.stopImmediatePropagation();
        return;
      }
      if (event.key !== "Tab") return;
      const buttons = [...(card.current?.querySelectorAll<HTMLButtonElement>("button") ?? [])];
      const index = buttons.indexOf(document.activeElement as HTMLButtonElement);
      event.preventDefault();
      if (buttons.length === 0) return;
      const step = event.shiftKey ? -1 : 1;
      buttons[(index + step + buttons.length) % buttons.length]?.focus();
    };
    window.addEventListener("keydown", onKeyDown, true);
    return () => window.removeEventListener("keydown", onKeyDown, true);
  }, []);

  useEffect(() => {
    if (leftovers) retryButton.current?.focus();
  }, [leftovers]);

  const failed = leftovers !== null;
  const state = failed ? "failed" : done ? "done" : "running";
  const handle = `@${session.user.username}`;

  function rowState(step: WipeStepId): RowState {
    if (leftovers && (step === "verify" || leftovers.some((leftover) => leftover.step === step))) {
      return "failed";
    }
    if (active === step || retrying.includes(step)) return "active";
    return results[step] ? "done" : "pending";
  }

  const completed = WIPE_STEPS.filter((step) => rowState(step) === "done");
  const progress = done ? 1 : completed.length / WIPE_STEPS.length;

  const title = failed
    ? "Couldn’t clear everything"
    : done
      ? "This browser is clear"
      : "Clearing this browser";
  const subtitle = failed
    ? `Still here: ${labelsOf(leftovers)}. Try again — if it keeps failing, clear this site’s data in your browser settings.`
    : done
      ? `Nothing from ${handle} is left on this device.`
      : `${LEAD[reason]}Removing everything Shroud stored for ${handle}.`;

  return (
    <div className={continued ? "modal-scrim wipe-scrim continued" : "modal-scrim wipe-scrim"}>
      <div
        ref={card}
        className="modal wipe-card"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="wipe-title"
        aria-describedby="wipe-subtitle"
        aria-busy={state === "running"}
        tabIndex={-1}
      >
        <WipeEmblem state={state} progress={progress} />
        <div className="wipe-heading">
          <h2 id="wipe-title">{title}</h2>
          <p id="wipe-subtitle">{subtitle}</p>
        </div>
        <ol className="wipe-steps">
          {WIPE_STEPS.map((step) => {
            const row = rowState(step);
            const result = results[step];
            const detail =
              row === "done" && result
                ? detailFor(step, result)
                : row === "failed" && step !== "verify"
                  ? "Still here"
                  : null;
            return (
              <li key={step} className="wipe-step" data-state={row}>
                <StepStatus state={row} />
                <span className="wipe-step-label">{LABELS[step]}</span>
                {detail ? <span className="wipe-step-detail">{detail}</span> : null}
              </li>
            );
          })}
        </ol>
        {failed ? (
          <div className="modal-actions wipe-actions">
            <button type="button" className="btn btn-secondary" onClick={() => finish.current()}>
              Continue
            </button>
            <button ref={retryButton} type="button" className="btn btn-primary" onClick={() => void retry()}>
              Try again
            </button>
          </div>
        ) : (
          <p className="wipe-foot">
            {done ? (
              <LoaderCircle className="wipe-foot-spinner" size={14} aria-hidden="true" />
            ) : (
              <ShieldCheck size={14} aria-hidden="true" />
            )}
            {done
              ? "Taking you to the welcome screen…"
              : "Your account and chats on other devices stay as they are."}
          </p>
        )}
        <span className="sr-only" role="status" aria-live="polite">
          {announcement}
        </span>
      </div>
    </div>
  );
}

function WipeEmblem({ state, progress }: { state: "running" | "done" | "failed"; progress: number }) {
  const radius = 45;
  const circumference = 2 * Math.PI * radius;
  return (
    <div className="wipe-emblem" data-state={state} aria-hidden="true">
      <svg className="wipe-ring" viewBox="0 0 96 96">
        <circle className="wipe-ring-track" cx="48" cy="48" r={radius} />
        <circle
          className="wipe-ring-fill"
          cx="48"
          cy="48"
          r={radius}
          strokeDasharray={circumference}
          strokeDashoffset={circumference * (1 - progress)}
          data-empty={progress === 0}
        />
      </svg>
      {state === "running"
        ? PARTICLES.map((p, i) => (
            <i
              key={i}
              className="wipe-particle"
              style={
                {
                  "--dx": `${p.dx}px`,
                  "--dy": `${p.dy}px`,
                  "--size": `${p.size}px`,
                  "--delay": `${p.delay}s`,
                } as CSSProperties
              }
            />
          ))
        : null}
      <span className="wipe-core">
        {state === "done" ? (
          <Check key="done" size={30} strokeWidth={2.4} />
        ) : state === "failed" ? (
          <TriangleAlert key="failed" size={26} />
        ) : (
          <Laptop key="device" size={28} />
        )}
      </span>
    </div>
  );
}

function StepStatus({ state }: { state: RowState }) {
  return (
    <span className="wipe-status" data-state={state} aria-hidden="true">
      <svg viewBox="0 0 22 22">
        {state === "pending" ? <circle className="wipe-status-ring" cx="11" cy="11" r="10.25" /> : null}
        {state === "active" ? (
          <>
            <circle className="wipe-status-track" cx="11" cy="11" r="9" />
            <circle className="wipe-status-arc" cx="11" cy="11" r="9" />
          </>
        ) : null}
        {state === "done" ? <path className="wipe-status-tick" d="M6.6 11.3l3 3 5.9-6.2" /> : null}
        {state === "failed" ? <path className="wipe-status-bang" d="M11 6.6v5.2M11 15.1v.2" /> : null}
      </svg>
    </span>
  );
}
