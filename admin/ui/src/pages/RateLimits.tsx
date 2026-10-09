import { RefreshCw } from "lucide-react";
import { usePageData } from "../api/usePageData";
import type { CheckLine, RateLimit } from "../api/types";
import {
  CHECK_STATE,
  ChangedChip,
  RunFailed,
  RunHead,
  RunPill,
  RunProgress,
  checkKeys,
  runListClass,
  runRowClass,
  useCheckRun,
  waitingFor,
  type CheckRun,
} from "../components/CheckRun";
import { PageFrame } from "../components/PageFrame";
import { Button, Card, CardBody, CardHead, Cell, Chip, Notice, PageHeader, TableHead, TableRow } from "../components/ui";

const COUNTED: Record<RateLimit["counted"], { label: string; tone?: "accent" }> = {
  ip: { label: "per IP" },
  account: { label: "per account", tone: "accent" },
  username: { label: "per username" },
  device: { label: "per device" },
};

function windowLabel(secs: number): string {
  if (secs % 3600 === 0) return secs === 3600 ? "hour" : `${secs / 3600} h`;
  if (secs % 60 === 0) return secs === 60 ? "min" : `${secs / 60} min`;
  return `${secs} s`;
}

const ACCOUNT_SIDE = /log in|password|session status|pin|lookup|share code|contact|key bundle|identity|device rename/i;

const STORE_ITEM = "Counter store";

/** A budget's line in `GET /rate-limits/check`: what it guards, and per what. */
function checkItem(row: RateLimit): string {
  return `${row.what} · ${COUNTED[row.counted].label}`;
}

/** The step and answer of `item` in the run, and what it said before while it hasn't landed. */
function lineOf(run: CheckRun<CheckLine[]>, item: string) {
  const index = run.data.findIndex((line) => line.item === item);
  const line = index >= 0 ? run.data[index] : undefined;
  const step = index < 0 ? "queued" : run.answered || run.running ? run.stepOf(index) : "queued";
  const shown = step === "done" ? line : run.settled.find((before) => before.item === item);
  return { step, line, shown } as const;
}

function Table({ rows, run }: { rows: RateLimit[]; run: CheckRun<CheckLine[]> }) {
  return (
    <>
      <TableHead>
        <Cell>What</Cell>
        <Cell width={110}>Counted</Cell>
        <Cell width={90} right>
          Budget
        </Cell>
        <Cell width={96} className="cell--keep">
          Check
        </Cell>
      </TableHead>
      <div className={runListClass(run, "")} key={run.runs}>
        {rows.map((row, index) => {
          const item = checkItem(row);
          const { step, line, shown } = lineOf(run, item);
          const state = CHECK_STATE[line?.state ?? "off"];
          const failed = step === "done" && line?.state === "failed";
          return (
            <TableRow key={`${row.what}-${row.counted}-${index}`} className={`table__row ${runRowClass(run, step, state.tone)}`} style={{ minHeight: 40 }}>
              <Cell>
                <span className="name-block run-text">
                  <span className="check__title">
                    <span className="text-primary">{row.what}</span>
                    {step === "done" && line ? <ChangedChip was={run.wasOf(item, state.label)} /> : null}
                  </span>
                  {failed ? <span className="small text-danger">{line?.detail}</span> : row.note ? <span className="small text-tertiary">{row.note}</span> : null}
                </span>
              </Cell>
              <Cell width={110}>
                <Chip tone={COUNTED[row.counted].tone}>{COUNTED[row.counted].label}</Chip>
              </Cell>
              <Cell width={90} right>
                <span className="mono small text-primary">
                  {row.limit} / {windowLabel(row.window_secs)}
                </span>
              </Cell>
              <Cell width={96} className="cell--keep">
                <span title={shown?.detail}>
                  <RunPill step={step} tone={state.tone}>
                    {state.label}
                  </RunPill>
                </span>
              </Cell>
            </TableRow>
          );
        })}
      </div>
    </>
  );
}

/** The run's head: status, progress, failure banner, and where counts are kept. */
function LimiterCheck({ run }: { run: CheckRun<CheckLine[]> }) {
  const { step, line, shown } = lineOf(run, STORE_ITEM);
  const state = CHECK_STATE[line?.state ?? "off"];
  const detail = step === "done" ? line?.detail : (shown?.detail ?? "Not checked yet");
  return (
    <Card>
      <CardHead
        title="Limiter check"
        sub="Tries every budget below on the live limiter with a throwaway key: the limit must pass and the next hit be refused. No person's counter is touched"
      >
        <RunHead run={run} busy={`Trying ${run.total - 1} budgets`} label="Check again" />
      </CardHead>
      <RunProgress run={run} />
      <RunFailed run={run} />
      <div className={runListClass(run, "checks")} key={run.runs}>
        <div className={`check ${runRowClass(run, step, state.tone)}`}>
          <RunPill step={step} tone={state.tone}>
            {state.label}
          </RunPill>
          <div className="check__text run-text">
            <div className="check__title">
              <span>{STORE_ITEM}</span>
              {step === "done" ? <ChangedChip was={run.wasOf(STORE_ITEM, state.label)} /> : null}
            </div>
            <div className="check__detail">{detail}</div>
          </div>
        </div>
      </div>
    </Card>
  );
}

/** Frame "Rate limits": the budgets are constants in the server build, generated into the API. */
export function RateLimits() {
  const page = usePageData<RateLimit[]>("/rate-limits");
  return (
    <PageFrame title="Rate limits" page={page}>
      {(rows) => <RateLimitsBody rows={rows} />}
    </PageFrame>
  );
}

/** Frames "Rate limits", "· Checking" and "· Check failed": the limiter check runs when the page
 *  opens and on Check again, its answers landing in the budget tables. */
function RateLimitsBody({ rows }: { rows: RateLimit[] }) {
  const run = useCheckRun("/rate-limits/check", waitingFor([STORE_ITEM, ...rows.map(checkItem)]), checkKeys, { runOnMount: true });
  const account = rows.filter((row) => ACCOUNT_SIDE.test(row.what));
  const traffic = rows.filter((row) => !ACCOUNT_SIDE.test(row.what));
  return (
    <>
      <PageHeader title="Rate limits" meta="Fixed windows built into the server · counted in Redis, or in the API process when Redis is not configured">
        <Button icon={RefreshCw} onClick={run.start} disabled={run.running} className={run.running ? "button--running" : undefined}>
          {run.running ? "Checking…" : "Check again"}
        </Button>
      </PageHeader>
      <Notice>
        Read-only. Each budget is a fixed window per scope and key (the key is a client IP, an account id or a username hash), built into
        the server rather than a setting. Over budget: 429 RATE_LIMITED with Retry-After set to the window. The server keeps no count of
        how often a limit is hit.
      </Notice>
      <LimiterCheck run={run} />
      <div className="columns">
        <div className="column" style={{ flex: "0 0 600px", width: 600 }}>
          <Card>
            <CardHead title="Signing in, the account and contacts" />
            <Table rows={account} run={run} />
          </Card>
        </div>
        <div className="column">
          <Card>
            <CardHead title="Messages, calls, media and relays" />
            <Table rows={traffic} run={run} />
          </Card>
          <Card>
            <CardHead title="How a limit is counted" />
            <CardBody>
              {[
                ["Redis, or this process", "With REDIS_URL set, every replica shares one counter. Without it, or while Redis is unreachable, each process counts on its own; a Redis outage never lets more through."],
                ["Client IPs behind a proxy", "IP budgets key on the address the server sees. With TRUST_FORWARDED_HEADERS the X-Forwarded-For address is used instead; only turn it on behind a trusted reverse proxy."],
              ].map(([title, detail]) => (
                <div key={title} style={{ display: "flex", flexDirection: "column", gap: 2 }}>
                  <span className="text-primary" style={{ fontWeight: 500 }}>
                    {title}
                  </span>
                  <span className="small text-tertiary" style={{ lineHeight: 1.45 }}>
                    {detail}
                  </span>
                </div>
              ))}
            </CardBody>
          </Card>
        </div>
      </div>
    </>
  );
}
