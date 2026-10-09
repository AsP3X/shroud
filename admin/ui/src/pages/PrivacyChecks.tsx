import { RefreshCw } from "lucide-react";
import { usePageData } from "../api/usePageData";
import type { PrivacyCheck } from "../api/types";
import { ChangedChip, RunFailed, RunHead, RunPill, RunProgress, Tick, runListClass, runRowClass, useCheckRun } from "../components/CheckRun";
import { PageFrame } from "../components/PageFrame";
import { Button, Card, CardHead, KeyValueRows, PageHeader, StatTile, type Tone } from "../components/ui";
import { count } from "../format";

const STATE: Record<PrivacyCheck["state"], { label: string; tone: Tone; sort: number }> = {
  sealed: { label: "Sealed", tone: "ok", sort: 0 },
  not_stored: { label: "Not stored", tone: "ok", sort: 1 },
  hashed: { label: "Hashed", tone: "warn", sort: 2 },
  stored: { label: "Stored", tone: "danger", sort: 3 },
};

const keyed = (items: PrivacyCheck[]) => items.map((item) => ({ key: item.item, state: STATE[item.state].label }));

/** Frame "Privacy checks". Pills follow each API row (§3.4). */
export function PrivacyChecks() {
  const page = usePageData<PrivacyCheck[]>("/privacy-checks");
  return (
    <PageFrame title="Privacy checks" page={page}>
      {(items) => <Checks initial={items} />}
    </PageFrame>
  );
}

/** Frames "Privacy checks · Checking", "· Checked" and "· Couldn't check": Check again runs the
 *  server's checks and plays them back row by row. */
function Checks({ initial }: { initial: PrivacyCheck[] }) {
  const run = useCheckRun("/privacy-checks", initial, keyed);
  const rows = run.data;
  // While a run plays, the tiles count only the answers that have landed; a dash until the first.
  const counted = rows.slice(0, run.revealed);
  const tile = (...states: PrivacyCheck["state"][]) =>
    run.phase === "sweeping" ? <span className="tick tick--waiting">–</span> : <Tick value={count(counted.filter((item) => states.includes(item.state)).length)} />;

  const fine = run.settled.filter((item) => item.state === "sealed" || item.state === "not_stored");
  const known = run.settled.filter((item) => item.state === "hashed" || item.state === "stored");

  return (
    <>
      <PageHeader title="Privacy checks" meta="What this server keeps that could identify someone · checked now, from the database and the configuration">
        <Button icon={RefreshCw} onClick={run.start} disabled={run.running} className={run.running ? "button--running" : undefined}>
          {run.running ? "Checking…" : "Check again"}
        </Button>
      </PageHeader>
      <div className={run.running ? "stats stats--running" : "stats"}>
        <StatTile label="Sealed or not stored" value={tile("sealed", "not_stored")} sub={`of ${count(rows.length)} checks`} />
        <StatTile label="Hashed" value={tile("hashed")} sub="Kept as a hash, not in the clear" />
        <StatTile label="Stored in the clear" value={tile("stored")} sub="Needs a server change to remove" />
      </div>
      <div className="columns">
        <div className="column">
          <Card>
            <CardHead title="Checks" sub="From the database schema and the running configuration; see docs/anonymity-plan.md for the work behind each">
              <RunHead run={run} busy={`Running ${count(run.total)} checks`} label="Check again" />
            </CardHead>
            <RunProgress run={run} />
            <RunFailed run={run} />
            <div className={runListClass(run, "checks")} key={run.runs}>
              {rows.map((item, index) => {
                const state = STATE[item.state];
                const step = run.stepOf(index);
                return (
                  <div className={`check ${runRowClass(run, step, state.tone)}`} key={item.item}>
                    <RunPill step={step} tone={state.tone}>
                      {state.label}
                    </RunPill>
                    <div className="check__text run-text">
                      <div className="check__title">
                        <span>{item.item}</span>
                        {step === "done" ? <ChangedChip was={run.wasOf(item.item, state.label)} /> : null}
                      </div>
                      {item.detail ? <div className="check__detail">{item.detail}</div> : null}
                    </div>
                  </div>
                );
              })}
            </div>
          </Card>
        </div>
        <div className={run.running ? "column run-stale" : "column"} style={{ flex: "0 0 360px", width: 360 }}>
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
