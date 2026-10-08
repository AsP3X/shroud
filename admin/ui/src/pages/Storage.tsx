import { HardDrive, Lock } from "lucide-react";
import { usePageData } from "../api/usePageData";
import type { Storage as StorageData } from "../api/types";
import { PageFrame } from "../components/PageFrame";
import { Card, CardBody, CardHead, Footnote, KeyValueRows, PageHeader, StatTile } from "../components/ui";
import { bytes, count } from "../format";

/** Frames "Storage" and "Storage · New server". Only the contract's fields: no daily chart,
 *  per-account sizes or last cleanup run, which the server does not record. */
export function Storage() {
  const page = usePageData<StorageData>("/storage");
  return (
    <PageFrame title="Storage" page={page}>
      {(data) => {
        const empty = data.objects === 0;
        const attached = Math.max(0, data.objects - data.unlinked_objects);
        const where =
          data.backend === "nebular"
            ? `Nebular · bucket ${data.bucket ?? "—"}${data.data_dir ? ` · local fallback ${data.data_dir}` : ""}`
            : `Local volume · ${data.data_dir ?? "—"}`;
        return (
          <>
            <PageHeader title="Storage" meta={where} />
            <div className="stats">
              <StatTile label="Stored ciphertext" value={empty ? "0 B" : bytes(data.bytes)} sub={empty ? "No objects yet" : `${count(data.objects)} objects`} />
              <StatTile label="Attached to messages" value={count(attached)} sub={empty ? "Nothing sent yet" : "Objects a message points at"} />
              <StatTile
                label="Waiting for cleanup"
                value={count(data.unlinked_objects)}
                sub={data.unlinked_objects === 0 ? "No unattached uploads" : "Unattached uploads · deleted an hour after upload"}
              />
              <StatTile label="Moved to Nebular" value={count(data.migrated_total)} sub={`${count(data.legacy_reads_total)} reads from the legacy volume since restart`} />
            </div>
            <div className="columns">
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
                    Media is encrypted on the device before upload, and every client labels it application/octet-stream. The server can't tell a
                    photo from a voice message.
                  </Footnote>
                </Card>
                <Card>
                  <CardHead title="Cleanup of unattached uploads" sub="Runs every 15 minutes on its own; there is nothing to start by hand" />
                  <CardBody>
                    <div className="text-secondary" style={{ lineHeight: 1.5 }}>
                      An upload that no message points at after 60 minutes is deleted, with its blob. A blob the store couldn't delete keeps its
                      row and is tried again on the next run. The server keeps no record of past runs; the counters above are since the last
                      restart.
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
      }}
    </PageFrame>
  );
}
