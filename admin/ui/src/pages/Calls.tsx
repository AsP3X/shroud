import { Lock } from "lucide-react";
import { usePageData } from "../api/usePageData";
import type { Calls as CallsData } from "../api/types";
import { PageFrame } from "../components/PageFrame";
import { Card, CardBody, CardHead, Cell, Footnote, KeyValueRows, PageHeader, StatTile, TableHead, TableRow } from "../components/ui";
import { count, duration } from "../format";

function kind(url: string): "STUN" | "TURN" | "TURNS" {
  if (url.startsWith("turns:")) return "TURNS";
  if (url.startsWith("turn:")) return "TURN";
  return "STUN";
}

/** Frame "Calls": the ICE servers every caller gets, the TURN login lifetime and the call sweep.
 *  No outcomes, durations or relay traffic: the server records none of them. */
export function Calls() {
  const page = usePageData<CallsData>("/calls");
  return (
    <PageFrame title="Calls" page={page}>
      {(data) => {
        const ttl = data.turn ? duration(data.turn.credential_ttl_secs) : null;
        return (
          <>
            <PageHeader
              title="Calls"
              meta={data.turn ? `${data.turn.urls[0] ?? ""} · short-lived logins valid ${ttl}` : "No TURN relay configured · calls connect directly or not at all"}
            />
            <div className="stats">
              <StatTile label="Calls started" value={count(data.created_total)} sub="Since the last restart · the only call counter" />
              <StatTile label="Unanswered call ends after" value={duration(data.gc.ringing_timeout_secs)} sub="Still ringing" />
              <StatTile label="Silent participant times out after" value={duration(data.gc.participant_timeout_secs)} sub="No heartbeat from a device" />
              <StatTile label="Sweep" value={`every ${duration(data.gc.sweep_secs)}`} sub="Ends the calls above" />
            </div>
            <div className="columns">
              <div className="column">
                <Card>
                  <CardHead title="Handed to every caller" sub="GET /calls/ice-servers · from TURN_URLS and ICE_SERVERS_JSON; no outside STUN server unless configured" />
                  <TableHead>
                    <Cell>URL</Cell>
                    <Cell width={110}>Kind</Cell>
                    <Cell width={220}>Login</Cell>
                  </TableHead>
                  {data.ice_servers.map((server) => {
                    const k = kind(server.urls);
                    return (
                      <TableRow key={server.urls} style={{ minHeight: 52 }}>
                        <Cell>
                          <span className="mono small text-primary">{server.urls}</span>
                        </Cell>
                        <Cell width={110}>{k}</Cell>
                        <Cell width={220}>{k === "STUN" ? "None" : `Short-lived, valid ${ttl ?? "—"}`}</Cell>
                      </TableRow>
                    );
                  })}
                  {data.ice_servers.length === 0 ? <div className="empty__body" style={{ padding: "16px 20px" }}>No ICE servers configured.</div> : null}
                </Card>
              </div>
              <div className="column" style={{ flex: "0 0 380px", width: 380 }}>
                <Card>
                  <CardHead title="Relay" />
                  <KeyValueRows
                    rows={[
                      { key: "TURN URLs", value: <span className="text-primary">{data.turn ? count(data.turn.urls.length) : "None"}</span> },
                      { key: "Login lifetime", value: <span className="text-primary">{ttl ?? "—"}</span> },
                    ]}
                  />
                </Card>
                <Card fill>
                  <CardHead title="What the relay sees" />
                  <CardBody>
                    <div className="text-secondary" style={{ lineHeight: 1.5 }}>
                      Call audio and video are end-to-end encrypted; the relay forwards packets it can't read. Its logins are minted by the API
                      for one call and named at random, so even its own records don't say whose call it was.
                    </div>
                  </CardBody>
                  <Footnote icon={Lock}>
                    The server keeps no call outcomes, durations or relay usage. Call rows end with a reason and go with the account.
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
