import { RefreshCw } from "lucide-react";
import { usePageData } from "../api/usePageData";
import type { Retention as RetentionData } from "../api/types";
import { CheckCard, checkKeys, useCheckRun, waitingFor } from "../components/CheckRun";
import { PageFrame } from "../components/PageFrame";
import { Button, Card, CardBody, CardHead, Cell, PageHeader, TableHead, TableRow } from "../components/ui";
import { duration } from "../format";

function every(secs: number): string {
  return `every ${duration(secs)}`;
}

/** Frame "Data retention": constants in the build, not settings. */
export function Retention() {
  const page = usePageData<RetentionData>("/retention");
  return (
    <PageFrame title="Data retention" page={page}>
      {(data) => <RetentionBody data={data} />}
    </PageFrame>
  );
}

/** Frames "Data retention", "· Checking" and "· Check failed": the check counts rows each job
 *  should already have removed, when the page opens and on Check again. */
function RetentionBody({ data }: { data: RetentionData }) {
  const run = useCheckRun("/retention/check", waitingFor(data.automatic.map((row) => row.record)), checkKeys, { runOnMount: true });
  return (
    <>
      <PageHeader title="Data retention" meta={`Fixed rules in the server · ${data.jobs.length} background jobs · nothing else is aged out`}>
        <Button icon={RefreshCw} onClick={run.start} disabled={run.running} className={run.running ? "button--running" : undefined}>
          {run.running ? "Checking…" : "Check again"}
        </Button>
      </PageHeader>
      <div className="columns">
        <div className="column">
          <CheckCard
            run={run}
            title="Jobs keeping up"
            sub="Counts what each rule's job should already have deleted or ended, allowing one run of the job. Anything left means it isn't running"
          />
          <Card>
            <CardHead title="What the server deletes on its own" sub="Constants in the build, not settings. None of them touches message content." />
            <TableHead>
              <Cell>Record</Cell>
              <Cell width={170}>Gone after</Cell>
              <Cell width={120}>Checked</Cell>
              <Cell width={260}>What stays</Cell>
            </TableHead>
            {data.automatic.map((row) => (
              <TableRow key={row.record} style={{ minHeight: 48 }}>
                <Cell>
                  <span className="text-primary" style={{ fontWeight: 500 }}>
                    {row.record}
                  </span>
                </Cell>
                <Cell width={170}>
                  <span className="mono small text-primary">{duration(row.after_secs)}</span>
                </Cell>
                <Cell width={120}>
                  <span className="mono small text-primary">{every(row.every_secs)}</span>
                </Cell>
                <Cell width={260}>
                  <span className="small text-secondary" style={{ lineHeight: 1.4 }}>
                    {row.stays}
                  </span>
                </Cell>
              </TableRow>
            ))}
          </Card>
          <Card>
            <CardHead title="Kept until a person deletes it" sub="Rows the server never ages out" />
            <TableHead>
              <Cell>Record</Cell>
              <Cell width={430}>Goes when</Cell>
            </TableHead>
            {data.kept.map((row) => (
              <TableRow key={row.record} style={{ minHeight: 40 }}>
                <Cell>
                  <span className="text-primary" style={{ fontWeight: 500 }}>
                    {row.record}
                  </span>
                </Cell>
                <Cell width={430}>
                  <span className="small text-secondary" style={{ lineHeight: 1.4 }}>
                    {row.goes_when}
                  </span>
                </Cell>
              </TableRow>
            ))}
          </Card>
        </div>
        <div className="column" style={{ flex: "0 0 340px", width: 340 }}>
          <Card>
            <CardHead title="Background jobs" sub="Started with the server" />
            <div className="kv">
              {data.jobs.map((job) => (
                <div className="kv__row" key={job.name} style={{ flexDirection: "column", alignItems: "flex-start", gap: 2, padding: "10px 0" }}>
                  <span className="text-primary" style={{ fontWeight: 500 }}>
                    {job.name}
                  </span>
                  <span className="small text-tertiary" style={{ lineHeight: 1.45 }}>
                    {job.every_secs ? `${every(job.every_secs).replace(/^every/, "Every")} · ` : ""}
                    {job.detail}
                  </span>
                </div>
              ))}
            </div>
          </Card>
          <Card>
            <CardHead title="Not kept by the server" />
            <CardBody>
              {[
                ["Logs", "shroud-server writes to stdout at RUST_LOG level; at info it names no user, device, chat or call. Compose keeps at most 3 files of 10 MB per container, the oldest dropped first."],
                ["Redis", "Presence, rate-limit counters and fan-out only; started with saving and append-only off, so nothing reaches disk."],
                ["Backups", "Postgres dumps and Nebular copies are the deployment's own; the server has no view of them."],
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
