import { CircleAlert, CircleCheck, RefreshCw } from "lucide-react";
import { useCallback, useEffect, useRef, useState, type CSSProperties, type ReactNode } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { ApiError, api } from "../api/client";
import type { CheckLine } from "../api/types";
import { count } from "../format";
import { Button, Card, CardHead, Chip, StatusPill, type Tone } from "./ui";

// A check run, shared by Privacy checks and the Overview's Services card. The server answers every
// check in one response: a scan sweeps the list while it works, then the answers land one row at a
// time, so a row that moved is easy to spot.
const MIN_SWEEP_MS = 650;
const STEP_MS = 80;
const SETTLE_MS = 420;

const clockFormat = new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit" });

export type RunPhase = "idle" | "sweeping" | "revealing" | "failed";
export type Step = "queued" | "active" | "done";

type Run<T> =
  | { phase: "idle" }
  | { phase: "sweeping" }
  | { phase: "revealing"; results: T; revealed: number }
  | { phase: "failed"; error: Error };

export interface CheckRun<T> {
  /** What the page shows: the new answer while it plays back, the last settled one otherwise. */
  data: T;
  /** The last answer that finished playing; what cards outside the list show. */
  settled: T;
  phase: RunPhase;
  running: boolean;
  error: Error | null;
  /** Rows of `data` that have landed. */
  revealed: number;
  total: number;
  checkedAt: Date;
  /** Bumped by every run; key the list on it so each run starts from fresh rows. */
  runs: number;
  /** Rows whose state moved in the last run, or null before the first run. */
  changed: number | null;
  /** False while `data` is still the placeholder of a run-on-mount page (nothing answered yet). */
  answered: boolean;
  start: () => void;
  stepOf: (index: number) => Step;
  /** The state a row had before this run, when it differs from `state`. */
  wasOf: (key: string, state: string) => string | undefined;
}

/** Runs `path` again on `start()` and plays the answer back row by row. `rows` lists each row's key
 *  and state label in display order; it decides how many steps there are and what moved. With
 *  `runOnMount`, `initial` is only the rows to wait on: the first run starts at once and compares
 *  nothing. */
export function useCheckRun<T>(
  path: string,
  initial: T,
  rows: (data: T) => { key: string; state: string }[],
  options: { runOnMount?: boolean } = {},
): CheckRun<T> {
  const navigate = useNavigate();
  const location = useLocation();
  const [current, setCurrent] = useState(initial);
  const [run, setRun] = useState<Run<T>>({ phase: "idle" });
  const [runs, setRuns] = useState(0);
  const [checkedAt, setCheckedAt] = useState(() => new Date());
  const [before, setBefore] = useState<Map<string, string> | null>(null);
  const [answered, setAnswered] = useState(!options.runOnMount);
  const latest = useRef(0);
  const rowsOf = useRef(rows);
  rowsOf.current = rows;

  useEffect(
    () => () => {
      latest.current += 1;
    },
    [],
  );

  const running = run.phase === "sweeping" || run.phase === "revealing";

  const startRun = useCallback(
    (compare: boolean) => {
      if (running) return;
      const mine = ++latest.current;
      const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
      const started = performance.now();
      setBefore(compare ? new Map(rowsOf.current(current).map((row) => [row.key, row.state])) : null);
      setRuns((value) => value + 1);
      setRun({ phase: "sweeping" });
      api<T>(path)
        .then(async (results) => {
          const left = reduced ? 0 : MIN_SWEEP_MS - (performance.now() - started);
          if (left > 0) await new Promise((resolve) => setTimeout(resolve, left));
          if (latest.current !== mine) return;
          setRun({ phase: "revealing", results, revealed: reduced ? rowsOf.current(results).length : 0 });
        })
        .catch((failure: unknown) => {
          if (latest.current !== mine) return;
          if (failure instanceof ApiError && failure.status === 401) {
            navigate("/sign-in", { replace: true, state: { from: location.pathname } });
            return;
          }
          setRun({ phase: "failed", error: failure instanceof Error ? failure : new Error(String(failure)) });
        });
    },
    [running, current, path, navigate, location.pathname],
  );
  const start = useCallback(() => startRun(true), [startRun]);

  // Runs once on mount when asked; StrictMode's second mount supersedes the first run.
  const runOnMount = useRef(options.runOnMount);
  useEffect(() => {
    if (runOnMount.current) startRun(false);
  }, []);

  // One answer per step, then a short settle so the last pill lands before the summary.
  useEffect(() => {
    if (run.phase !== "revealing") return;
    const done = run.revealed >= rowsOf.current(run.results).length;
    const timer = setTimeout(
      () => {
        if (done) {
          setCurrent(run.results);
          setAnswered(true);
          setCheckedAt(new Date());
          setRun({ phase: "idle" });
        } else {
          setRun({ ...run, revealed: run.revealed + 1 });
        }
      },
      done ? SETTLE_MS : run.revealed === 0 ? 0 : STEP_MS,
    );
    return () => clearTimeout(timer);
  }, [run]);

  const data = run.phase === "revealing" ? run.results : current;
  const shown = rows(data);
  const total = shown.length;
  const revealed = run.phase === "revealing" ? run.revealed : run.phase === "sweeping" ? 0 : total;
  const changed = before ? rows(current).filter((row) => before.has(row.key) && before.get(row.key) !== row.state).length : null;

  const stepOf = (index: number): Step => {
    if (index < revealed) return "done";
    if (run.phase === "revealing" && index === revealed) return "active";
    return running ? "queued" : "done";
  };

  const wasOf = (key: string, state: string) => {
    const was = before?.get(key);
    return was !== undefined && was !== state ? was : undefined;
  };

  return {
    data,
    settled: current,
    phase: run.phase,
    running,
    error: run.phase === "failed" ? run.error : null,
    revealed,
    total,
    checkedAt,
    runs,
    changed,
    answered,
    start,
    stepOf,
    wasOf,
  };
}

/** Class names for one row: its step, and the landing wash in its tone once a run has played it. */
export function runRowClass(run: CheckRun<unknown>, step: Step, tone: Tone): string {
  const landed = step === "done" && run.runs > 0 && run.phase !== "failed";
  return ["run-row", `run-row--${step}`, landed ? `run-row--landed run-row--${tone}` : ""].filter(Boolean).join(" ");
}

export function runListClass(run: CheckRun<unknown>, base: string): string {
  return `${base} run-list run-list--${run.phase}`;
}

/** The row's pill: an empty one while queued, Checking while it is next, the answer once landed. */
export function RunPill({ step, tone, children }: { step: Step; tone: Tone; children: ReactNode }) {
  return (
    <span className="run-slot">
      {step === "done" ? (
        <StatusPill tone={tone}>{children}</StatusPill>
      ) : step === "active" ? (
        <span className="pill pill--checking">
          <span className="run-spinner" aria-hidden="true" />
          Checking
        </span>
      ) : (
        <span className="pill pill--queued">Waiting</span>
      )}
    </span>
  );
}

export function ChangedChip({ was }: { was: string | undefined }) {
  return was ? <Chip tone="accent">Changed · was {was}</Chip> : null;
}

/** A number that rises into place each time it changes. */
export function Tick({ value }: { value: ReactNode }) {
  return (
    <span className="tick" key={String(value)}>
      {value}
    </span>
  );
}

/** The card head's side of a run: the status line, and on phones (where the page header's button
 *  is hidden) its own re-run button. `quiet` hides the idle line until the first run. */
/** `busy` is the line while the server works ("Running 20 checks"). */
export function RunHead({ run, busy, label, quiet }: { run: CheckRun<unknown>; busy: string; label: string; quiet?: boolean }) {
  return (
    <>
      <RunStatus run={run} busy={busy} quiet={quiet} />
      <button type="button" className="icon-button run-again" onClick={run.start} disabled={run.running} aria-label={label}>
        <RefreshCw aria-hidden="true" />
      </button>
    </>
  );
}

function RunStatus({ run, busy, quiet }: { run: CheckRun<unknown>; busy: string; quiet?: boolean }) {
  let body: ReactNode = null;
  let tone: "busy" | "ok" | "accent" | "danger" = "busy";
  let announcement = "";
  if (run.phase === "sweeping") {
    announcement = busy;
    body = (
      <>
        <span className="run-spinner" aria-hidden="true" />
        {busy}
      </>
    );
  } else if (run.phase === "revealing") {
    body = (
      <>
        <span className="run-spinner" aria-hidden="true" />
        <span className="run-status__count">
          {count(Math.min(run.revealed, run.total))} of {count(run.total)}
        </span>
      </>
    );
  } else if (run.phase === "failed") {
    tone = "danger";
    announcement = "The checks couldn't run";
    body = (
      <>
        <CircleAlert aria-hidden="true" />
        Couldn't check
      </>
    );
  } else if (!(quiet && run.changed === null)) {
    tone = run.changed ? "accent" : "ok";
    const moved = run.changed === null ? "" : run.changed === 0 ? " · nothing changed" : ` · ${count(run.changed)} changed`;
    if (run.changed !== null) announcement = `Checks done${run.changed ? `, ${run.changed} changed` : ", nothing changed"}`;
    body = (
      <>
        <CircleCheck aria-hidden="true" />
        Checked {clockFormat.format(run.checkedAt)}
        {moved}
      </>
    );
  }
  return (
    <>
      {body ? (
        <div className={`run-status run-status--${tone}`} key={run.phase} aria-hidden="true">
          {body}
        </div>
      ) : null}
      <div className="visually-hidden" role="status" aria-live="polite">
        {announcement}
      </div>
    </>
  );
}

/** The bar under the card head: indeterminate while the server works, one step per answer after. */
export function RunProgress({ run }: { run: CheckRun<unknown> }) {
  const progress = run.phase === "revealing" ? run.revealed / Math.max(1, run.total) : run.running ? 0 : 1;
  return (
    <div
      className={`run-progress run-progress--${run.phase}`}
      style={{ "--progress": progress } as CSSProperties}
      role="progressbar"
      aria-label="Checks done"
      aria-valuemin={0}
      aria-valuemax={run.total}
      aria-valuenow={run.revealed}
    >
      <span />
    </div>
  );
}

export function RunFailed({ run }: { run: CheckRun<unknown> }) {
  const error = run.error;
  if (!error) return null;
  let message = error.message;
  if (error instanceof ApiError && error.code === "UPSTREAM") {
    message = error.upstream === "postgres" ? "The console can't reach its database." : "The console can't reach the API.";
  } else if (!(error instanceof ApiError)) {
    message = "Check your connection, then try again.";
  }
  return (
    <div className="run-failed" role="alert">
      <CircleAlert aria-hidden="true" />
      <div className="run-failed__text">
        <div className="run-failed__title">The checks couldn't run.{run.answered ? " These are the last results." : ""}</div>
        <div className="run-failed__body">{message}</div>
      </div>
      <Button icon={RefreshCw} onClick={run.start}>
        Try again
      </Button>
    </div>
  );
}

/** How a check line reads: the API's `ok`, `failed` and `off`. */
export const CHECK_STATE: Record<CheckLine["state"], { label: string; tone: Tone }> = {
  ok: { label: "Works", tone: "ok" },
  failed: { label: "Fails", tone: "danger" },
  off: { label: "Off", tone: "neutral" },
};

/** What a run compares: each line by its item. */
export const checkKeys = (lines: CheckLine[]) => lines.map((line) => ({ key: line.item, state: CHECK_STATE[line.state].label }));

/** Lines a run-on-mount card shows before the first answer. */
export const waitingFor = (items: string[]): CheckLine[] => items.map((item) => ({ item, state: "off", detail: "Not checked yet" }));

/** A card of check lines that plays each run back: Push delivery's and Calls' checks. */
export function CheckCard({ run, title, sub, mono }: { run: CheckRun<CheckLine[]>; title: string; sub: string; mono?: boolean }) {
  return (
    <Card>
      <CardHead title={title} sub={sub}>
        <RunHead run={run} busy={`Running ${count(run.total)} checks`} label="Check again" />
      </CardHead>
      <RunProgress run={run} />
      <RunFailed run={run} />
      <div className={runListClass(run, "checks")} key={run.runs}>
        {run.data.map((line, index) => {
          const state = CHECK_STATE[line.state];
          // Until something answered, the rows are only what the card waits on.
          const step = run.answered || run.running ? run.stepOf(index) : "queued";
          // A row that hasn't landed keeps what it said before, so the answer can't show early.
          const detail = step === "done" ? line.detail : (run.settled.find((before) => before.item === line.item)?.detail ?? "Not checked yet");
          return (
            <div className={`check ${runRowClass(run, step, state.tone)}`} key={line.item}>
              <RunPill step={step} tone={state.tone}>
                {state.label}
              </RunPill>
              <div className="check__text run-text">
                <div className="check__title">
                  <span className={mono ? "mono" : undefined}>{line.item}</span>
                  {step === "done" ? <ChangedChip was={run.wasOf(line.item, state.label)} /> : null}
                </div>
                <div className="check__detail">{detail}</div>
              </div>
            </div>
          );
        })}
      </div>
    </Card>
  );
}
