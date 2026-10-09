import { HardDrive, Lock, RefreshCw } from "lucide-react";
import type { CSSProperties, ReactNode } from "react";
import { usePageData } from "../api/usePageData";
import type { Storage as StorageData } from "../api/types";
import { RunFailed, RunHead, Tick, runListClass, runRowClass, useCheckRun, type CheckRun, type Step } from "../components/CheckRun";
import { PageFrame } from "../components/PageFrame";
import { Button, Card, CardBody, CardHead, Footnote, KeyValueRows, PageHeader, StatTile } from "../components/ui";
import { bytes, count } from "../format";

interface Figure {
  key: string;
  raw: number;
  value: string;
  sub: string;
  format: (value: number) => string;
}

/** The four tiles, in the order a run lands them. `raw` is what a run compares. */
function figures(data: StorageData): Figure[] {
  const empty = data.objects === 0;
  const attached = Math.max(0, data.objects - data.unlinked_objects);
  return [
    { key: "Stored ciphertext", raw: data.bytes, value: empty ? "0 B" : bytes(data.bytes), sub: empty ? "No objects yet" : `${count(data.objects)} objects`, format: bytes },
    { key: "Attached to messages", raw: attached, value: count(attached), sub: empty ? "Nothing sent yet" : "Objects a message points at", format: count },
    {
      key: "Waiting for cleanup",
      raw: data.unlinked_objects,
      value: count(data.unlinked_objects),
      sub: data.unlinked_objects === 0 ? "No unattached uploads" : "Unattached uploads · deleted an hour after upload",
      format: count,
    },
    { key: "Moved to Nebular", raw: data.migrated_total, value: count(data.migrated_total), sub: `${count(data.legacy_reads_total)} reads from the legacy volume since restart`, format: count },
  ];
}

const keyed = (data: StorageData) => figures(data).map((figure) => ({ key: figure.key, state: String(figure.raw) }));

/** Frames "Storage" and "Storage · New server". Only the contract's fields: no daily chart,
 *  per-account sizes or last cleanup run, which the server does not record. */
export function Storage() {
  const page = usePageData<StorageData>("/storage");
  return (
    <PageFrame title="Storage" page={page}>
      {(data) => <StorageBody initial={data} />}
    </PageFrame>
  );
}

/** Frames "Storage · Counting" and "Storage · Counted": Refresh asks the server again and lands
 *  the tiles one by one, each saying how far it moved since the last count. */
function StorageBody({ initial }: { initial: StorageData }) {
  const run = useCheckRun("/storage", initial, keyed);
  const data = run.settled;
  const where =
    data.backend === "nebular"
      ? `Nebular · bucket ${data.bucket ?? "—"}${data.data_dir ? ` · local fallback ${data.data_dir}` : ""}`
      : `Local volume · ${data.data_dir ?? "—"}`;

  return (
    <>
      <PageHeader title="Storage" meta={where}>
        <RunHead run={run} busy="Counting storage" label="Refresh" quiet />
        <Button icon={RefreshCw} onClick={run.start} disabled={run.running} className={run.running ? "button--running" : undefined}>
          {run.running ? "Refreshing…" : "Refresh"}
        </Button>
      </PageHeader>
      <RunFailed run={run} />
      <div className={runListClass(run, "stats")} key={run.runs}>
        {figures(run.data).map((figure, index) => {
          const step = run.stepOf(index);
          return (
            <StatTile
              key={figure.key}
              label={figure.key}
              className={runRowClass(run, step, "neutral")}
              style={{ "--i": index } as CSSProperties}
              value={<FigureValue run={run} step={step} figure={figure} />}
              sub={<span className="run-text">{figure.sub}</span>}
            />
          );
        })}
      </div>
      <div className={run.running ? "columns run-stale" : "columns"}>
        <div className="column">
          <Card>
            <CardHead title="Where media lives" sub="Encrypted blobs; the server stores and serves them without reading them" />
            <KeyValueRows
              rows={[
                { key: "Backend", value: <span className="text-primary">{data.backend === "nebular" ? "Nebular (S3-compatible)" : "Local volume"}</span> },
                ...(data.bucket ? [{ key: "Bucket", value: <span className="mono text-primary">{data.bucket}</span> }] : []),
                ...(data.data_dir ? [{ key: data.backend === "nebular" ? "Legacy local volume" : "Data directory", value: <span className="mono text-primary">{data.data_dir}</span> }] : []),
                { key: "Largest object", value: <span className="text-primary">{bytes(data.max_object_bytes)} · written to disk as it arrives</span> },
              ]}
            />
            <Footnote icon={Lock}>
              Media is encrypted on the device before upload, and every client labels it application/octet-stream. The server can't tell a photo
              from a voice message.
            </Footnote>
          </Card>
          <Card>
            <CardHead title="Cleanup of unattached uploads" sub="Runs every 15 minutes on its own; there is nothing to start by hand" />
            <CardBody>
              <div className="text-secondary" style={{ lineHeight: 1.5 }}>
                An upload that no message points at after 60 minutes is deleted, with its blob. A blob the store couldn't delete keeps its row and
                is tried again on the next run. The server keeps no record of past runs; the counters above are since the last restart.
              </div>
            </CardBody>
          </Card>
        </div>
        <div className="column" style={{ flex: "0 0 380px", width: 380 }}>
          <Card fill>
            <CardHead title="Legacy volume" sub="Blobs from releases before Nebular" />
            <KeyValueRows
              rows={[
                { key: "Reads served from it", value: <span className="mono text-primary">{count(data.legacy_reads_total)}</span> },
                { key: "Blobs moved into Nebular", value: <span className="mono text-primary">{count(data.migrated_total)}</span> },
              ]}
            />
            <Footnote icon={HardDrive}>
              {data.backend === "nebular"
                ? "The migration job moves remaining blobs into Nebular until the local volume is empty. Both counters restart with the server."
                : "This server stores media on its local volume only; nothing is migrated."}
            </Footnote>
          </Card>
        </div>
      </div>
    </>
  );
}

/** A tile's value: an empty bar while queued, Counting while it is next, then the figure and how
 *  far it moved since the last count. */
function FigureValue({ run, step, figure }: { run: CheckRun<StorageData>; step: Step; figure: Figure }) {
  if (step === "queued") return <span className="figure-skeleton" aria-label="Waiting" />;
  if (step === "active") {
    return (
      <span className="figure-counting">
        <span className="run-spinner" aria-hidden="true" />
        Counting
      </span>
    );
  }
  const was = run.wasOf(figure.key, String(figure.raw));
  let delta: ReactNode = null;
  if (was !== undefined) {
    const moved = figure.raw - Number(was);
    delta = (
      <span className="chip chip--accent figure-delta">
        {moved > 0 ? "+" : "−"}
        {figure.format(Math.abs(moved))}
        <span className="figure-delta__since">{"\u00a0"}since last count</span>
      </span>
    );
  }
  return (
    <span className="figure">
      <Tick value={figure.value} />
      {delta}
    </span>
  );
}
