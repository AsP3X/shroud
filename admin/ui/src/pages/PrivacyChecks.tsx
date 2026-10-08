import { RefreshCw } from "lucide-react";
import { usePageData } from "../api/usePageData";
import type { PrivacyCheck } from "../api/types";
import { PageFrame } from "../components/PageFrame";
import { Button, Card, CardHead, KeyValueRows, PageHeader, StatTile, StatusPill, type Tone } from "../components/ui";
import { count } from "../format";

const STATE: Record<PrivacyCheck["state"], { label: string; tone: Tone; sort: number }> = {
  sealed: { label: "Sealed", tone: "ok", sort: 0 },
  not_stored: { label: "Not stored", tone: "ok", sort: 1 },
  hashed: { label: "Hashed", tone: "warn", sort: 2 },
  stored: { label: "Stored", tone: "danger", sort: 3 },
};

/** Frame "Privacy checks": each row is a column check or a constant on the backend (§3.4). */
export function PrivacyChecks() {
  const page = usePageData<PrivacyCheck[]>("/privacy-checks");
  return (
    <PageFrame title="Privacy checks" page={page}>
      {(items) => {
        const stored = items.filter((item) => item.state === "stored");
        const hashed = items.filter((item) => item.state === "hashed");
        const fine = items.filter((item) => item.state === "sealed" || item.state === "not_stored");
        return (
          <>
            <PageHeader title="Privacy checks" meta="What this server keeps that could identify someone · checked now, from the database and the configuration">
              <Button icon={RefreshCw} onClick={page.reload}>
                Check again
              </Button>
            </PageHeader>
            <div className="stats">
              <StatTile label="Sealed or not stored" value={count(fine.length)} sub={`of ${count(items.length)} checks`} />
              <StatTile label="Hashed" value={count(hashed.length)} sub="Kept as a hash, not in the clear" />
              <StatTile label="Stored in the clear" value={count(stored.length)} sub="Needs a server change to remove" />
            </div>
            <div className="columns">
              <div className="column">
                <Card>
                  <CardHead title="Checks" sub="From the database schema and the running configuration; see docs/anonymity-plan.md for the work behind each" />
                  <div className="checks">
                    {items.map((item) => {
                      const state = STATE[item.state];
                      return (
                        <div className="check" key={item.item}>
                          <StatusPill tone={state.tone}>{state.label}</StatusPill>
                          <div className="check__text">
                            <div className="check__title">{item.item}</div>
                            {item.detail ? <div className="check__detail">{item.detail}</div> : null}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </Card>
              </div>
              <div className="column" style={{ flex: "0 0 360px", width: 360 }}>
                <Card>
                  <CardHead title="Never reaches this server" />
                  <KeyValueRows rows={fine.map((item) => ({ key: item.item, value: <span style={{ color: "var(--ok-text)", fontWeight: 500 }}>{STATE[item.state].label}</span> }))} />
                </Card>
                <Card>
                  <CardHead title="Still known to this server" sub="Needs a different design to remove" />
                  <KeyValueRows
                    rows={[...hashed, ...stored].map((item) => ({
                      key: item.item,
                      value: <span style={{ color: item.state === "stored" ? "var(--warn-text)" : "var(--text-secondary)", fontWeight: 500 }}>{item.state === "stored" ? "Known" : "Hashed"}</span>,
                    }))}
                  />
                </Card>
              </div>
            </div>
          </>
        );
      }}
    </PageFrame>
  );
}
