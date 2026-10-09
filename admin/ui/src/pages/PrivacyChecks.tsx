import { CircleAlert, CircleCheck, RefreshCw } from "lucide-react";
import { useCallback, useEffect, useRef, useState, type CSSProperties } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { ApiError, api } from "../api/client";
import { usePageData } from "../api/usePageData";
import type { PrivacyCheck } from "../api/types";
import { PageFrame } from "../components/PageFrame";
import { Button, Card, CardHead, Chip, KeyValueRows, PageHeader, StatTile, StatusPill, type Tone } from "../components/ui";
import { count } from "../format";

const STATE: Record<PrivacyCheck["state"], { label: string; tone: Tone; sort: number }> = {
  sealed: { label: "Sealed", tone: "ok", sort: 0 },
  not_stored: { label: "Not stored", tone: "ok", sort: 1 },
  hashed: { label: "Hashed", tone: "warn", sort: 2 },
  stored: { label: "Stored", tone: "danger", sort: 3 },
};

// The server answers every check in one response. The sweep runs while it works, then the answers
// land one row at a time, so a check that moved is easy to spot.
const MIN_SWEEP_MS = 650;
const STEP_MS = 80;
const SETTLE_MS = 420;

const clockFormat = new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit" });

type Run =
  | { phase: "idle" }
  | { phase: "sweeping" }
  | { phase: "revealing"; results: PrivacyCheck[]; revealed: number }
  | { phase: "failed"; error: Error };

type Step = "queued" | "active" | "done";

/** Frame "Privacy checks". Pills follow each API row (§3.4). */
export function PrivacyChecks() {
  const page = usePageData<PrivacyCheck[]>("/privacy-checks");
  return (
    <PageFrame title="Privacy checks" page={page}>
      {(items) => <Checks initial={items} />}
    </PageFrame>
  );
}

/** Frames "Privacy checks · Checking" and "· Checked": Check again runs the server's checks and
 *  plays them back row by row. */
function Checks({ initial }: { initial: PrivacyCheck[] }) {
  const navigate = useNavigate();
  const location = useLocation();
  const [current, setCurrent] = useState(initial);
  const [run, setRun] = useState<Run>({ phase: "idle" });
  const [runs, setRuns] = useState(0);
  const [checkedAt, setCheckedAt] = useState(() => new Date());
  // What each check said before the latest run, so the rows that moved can say so.
  const [before, setBefore] = useState<Map<string, PrivacyCheck["state"]> | null>(null);
  const latest = useRef(0);

  useEffect(
    () => () => {
      latest.current += 1;
    },
    [],
  );

  const running = run.phase === "sweeping" || run.phase === "revealing";

  const start = useCallback(() => {
    if (running) return;
    const mine = ++latest.current;
    const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const started = performance.now();
    setBefore(new Map(current.map((item) => [item.item, item.state])));
    setRuns((value) => value + 1);
    setRun({ phase: "sweeping" });
    api<PrivacyCheck[]>("/privacy-checks")
      .then(async (results) => {
        const left = reduced ? 0 : MIN_SWEEP_MS - (performance.now() - started);
        if (left > 0) await new Promise((resolve) => setTimeout(resolve, left));
        if (latest.current !== mine) return;
        setRun({ phase: "revealing", results, revealed: reduced ? results.length : 0 });
      })
      .catch((failure: unknown) => {
        if (latest.current !== mine) return;
        if (failure instanceof ApiError && failure.status === 401) {
          navigate("/sign-in", { replace: true, state: { from: location.pathname } });
          return;
        }
        setRun({ phase: "failed", error: failure instanceof Error ? failure : new Error(String(failure)) });
      });
  }, [running, current, navigate, location.pathname]);

  // One answer per step, then a short settle so the last pill lands before the summary.
  useEffect(() => {
    if (run.phase !== "revealing") return;
    const done = run.revealed >= run.results.length;
    const timer = setTimeout(
      () => {
        if (done) {
          setCurrent(run.results);
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

  const rows = run.phase === "revealing" ? run.results : current;
  const revealed = run.phase === "revealing" ? run.revealed : run.phase === "sweeping" ? 0 : rows.length;
  // While a run plays, the tiles count only the answers that have landed.
  const counted = running ? rows.slice(0, revealed) : current;
  const tally = (...states: PrivacyCheck["state"][]) => counted.filter((item) => states.includes(item.state)).length;
  // Nothing has landed while the server works: a dash, not a zero that reads as a result.
  const tile = (...states: PrivacyCheck["state"][]) => (run.phase === "sweeping" ? <span className="tick tick--waiting">–</span> : <Tick value={tally(...states)} />);

  const changed = before ? current.filter((item) => before.has(item.item) && before.get(item.item) !== item.state).length : 0;
  const fine = current.filter((item) => item.state === "sealed" || item.state === "not_stored");
  const known = current.filter((item) => item.state === "hashed" || item.state === "stored");
  const progress = run.phase === "revealing" ? run.revealed / Math.max(1, run.results.length) : running ? 0 : 1;

  const stepOf = (index: number): Step => {
    if (index < revealed) return "done";
    if (run.phase === "revealing" && index === revealed) return "active";
    return running ? "queued" : "done";
  };

  return (
    <>
      <PageHeader title="Privacy checks" meta="What this server keeps that could identify someone · checked now, from the database and the configuration">
        <Button icon={RefreshCw} onClick={start} disabled={running} className={running ? "button--running" : undefined}>
          {running ? "Checking…" : "Check again"}
        </Button>
      </PageHeader>
      <div className={running ? "stats stats--running" : "stats"}>
        <StatTile label="Sealed or not stored" value={tile("sealed", "not_stored")} sub={`of ${count(rows.length)} checks`} />
        <StatTile label="Hashed" value={tile("hashed")} sub="Kept as a hash, not in the clear" />
        <StatTile label="Stored in the clear" value={tile("stored")} sub="Needs a server change to remove" />
      </div>
      <div className="columns">
        <div className="column">
          <Card>
            <CardHead title="Checks" sub="From the database schema and the running configuration; see docs/anonymity-plan.md for the work behind each">
              <RunStatus run={run} total={rows.length} checkedAt={checkedAt} changed={before ? changed : null} />
              <button type="button" className="icon-button check-run__again" onClick={start} disabled={running} aria-label="Check again">
                <RefreshCw aria-hidden="true" />
              </button>
            </CardHead>
            <div
              className={`run-progress run-progress--${run.phase}`}
              style={{ "--progress": progress } as CSSProperties}
              role="progressbar"
              aria-label="Checks done"
              aria-valuemin={0}
              aria-valuemax={rows.length}
              aria-valuenow={revealed}
            >
              <span />
            </div>
            {run.phase === "failed" ? <RunFailed error={run.error} onRetry={start} /> : null}
            <div className={`checks checks--${run.phase}`} key={runs}>
              {rows.map((item, index) => {
                const step = stepOf(index);
                const was = before?.get(item.item);
                return (
                  <CheckRow
                    key={item.item}
                    item={item}
                    step={step}
                    landing={runs > 0 && run.phase !== "failed"}
                    was={step === "done" && was !== undefined && was !== item.state ? was : undefined}
                  />
                );
              })}
            </div>
          </Card>
        </div>
        <div className={running ? "column check-side check-side--stale" : "column check-side"} style={{ flex: "0 0 360px", width: 360 }}>
          <Card>
            <CardHead title="Never reaches this server" />
            <KeyValueRows rows={fine.map((item) => ({ key: item.item, value: <span style={{ color: "var(--ok-text)", fontWeight: 500 }}>{STATE[item.state].label}</span> }))} />
          </Card>
          <Card>
            <CardHead title="Still known to this server" sub="Needs a different design to remove" />
            <KeyValueRows
              rows={known.map((item) => ({
                key: item.item,
                value: <span style={{ color: item.state === "stored" ? "var(--warn-text)" : "var(--text-secondary)", fontWeight: 500 }}>{item.state === "stored" ? "Known" : "Hashed"}</span>,
              }))}
            />
          </Card>
        </div>
      </div>
    </>
  );
}

/** A number that rises into place each time it changes. */
function Tick({ value }: { value: number }) {
  return (
    <span className="tick" key={value}>
      {count(value)}
    </span>
  );
}

function CheckRow({ item, step, landing, was }: { item: PrivacyCheck; step: Step; landing: boolean; was: PrivacyCheck["state"] | undefined }) {
  const state = STATE[item.state];
  const classes = ["check", `check--${step}`, step === "done" && landing ? `check--landed check--${state.tone}` : ""];
  return (
    <div className={classes.filter(Boolean).join(" ")}>
      <div className="check__slot">
        {step === "done" ? (
          <StatusPill tone={state.tone}>{state.label}</StatusPill>
        ) : step === "active" ? (
          <span className="pill pill--checking">
            <span className="check__spinner" aria-hidden="true" />
            Checking
          </span>
        ) : (
          <span className="pill pill--queued">Waiting</span>
        )}
      </div>
      <div className="check__text">
        <div className="check__title">
          <span>{item.item}</span>
          {was ? <Chip tone="accent">Changed · was {STATE[was].label}</Chip> : null}
        </div>
        {item.detail ? <div className="check__detail">{item.detail}</div> : null}
      </div>
    </div>
  );
}

/** The Checks card's run state: running, n of m, or when it last ran and whether anything moved.
 *  `changed` is null before the first Check again. */
function RunStatus({ run, total, checkedAt, changed }: { run: Run; total: number; checkedAt: Date; changed: number | null }) {
  let body;
  let tone: "busy" | "ok" | "accent" | "danger";
  let announcement = "";
  if (run.phase === "sweeping") {
    tone = "busy";
    announcement = `Running ${total} checks`;
    body = (
      <>
        <span className="check__spinner" aria-hidden="true" />
        Running {count(total)} checks
      </>
    );
  } else if (run.phase === "revealing") {
    tone = "busy";
    body = (
      <>
        <span className="check__spinner" aria-hidden="true" />
        <span className="run-status__count">
          {count(Math.min(run.revealed, total))} of {count(total)}
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
  } else {
    tone = changed ? "accent" : "ok";
    const moved = changed === null ? "" : changed === 0 ? " · nothing changed" : ` · ${count(changed)} changed`;
    if (changed !== null) announcement = `Checks done${changed ? `, ${changed} changed` : ", nothing changed"}`;
    body = (
      <>
        <CircleCheck aria-hidden="true" />
        Checked {clockFormat.format(checkedAt)}
        {moved}
      </>
    );
  }
  return (
    <>
      <div className={`run-status run-status--${tone}`} key={run.phase} aria-hidden="true">
        {body}
      </div>
      <div className="visually-hidden" role="status" aria-live="polite">
        {announcement}
      </div>
    </>
  );
}

function RunFailed({ error, onRetry }: { error: Error; onRetry: () => void }) {
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
        <div className="run-failed__title">The checks couldn't run. These are the last results.</div>
        <div className="run-failed__body">{message}</div>
      </div>
      <Button icon={RefreshCw} onClick={onRetry}>
        Try again
      </Button>
    </div>
  );
}
