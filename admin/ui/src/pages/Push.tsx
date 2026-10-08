import { BellRing, Info } from "lucide-react";
import { usePageData } from "../api/usePageData";
import type { Push as PushData } from "../api/types";
import { PageFrame } from "../components/PageFrame";
import { Card, CardBody, CardHead, Cell, Footnote, PageHeader, StatTile, TableHead, TableRow } from "../components/ui";
import { count } from "../format";

/** Frame "Push delivery": registrations and routing. Deliveries are logged by the API, never counted. */
export function Push() {
  const page = usePageData<PushData>("/push");
  return (
    <PageFrame title="Push delivery" page={page}>
      {(data) => (
        <>
          <PageHeader title="Push delivery" meta="Registrations and routing · deliveries are logged, never counted or kept per account" />
          <div className="stats">
            <StatTile label="APNs tokens" value={count(data.apns_tokens)} sub="iPhone and iPad · one alert token per device" />
            <StatTile label="Web Push subscriptions" value={count(data.web_push_subscriptions)} sub="Browsers, through their push service" />
            <StatTile label="UnifiedPush subscriptions" value={count(data.unifiedpush_subscriptions)} sub="Android · endpoints on allowed hosts" />
            <StatTile label="Notifications off" value={count(data.notifications_off)} sub="Devices that turned pushes off in Settings" />
          </div>
          <div className="columns">
            <div className="column">
              <Card fill>
                <CardHead title="By channel" sub="Every push carries only an opaque payload the device opens itself" />
                <TableHead>
                  <Cell>Channel</Cell>
                  <Cell width={90}>Registered</Cell>
                  <Cell width={240}>Sent to</Cell>
                  <Cell width={210}>Registration dropped when</Cell>
                </TableHead>
                {data.channels.map((channel) => (
                  <TableRow key={channel.name} style={{ minHeight: 56 }}>
                    <Cell>
                      <span className="text-primary" style={{ fontWeight: 500 }}>
                        {channel.name}
                      </span>
                    </Cell>
                    <Cell width={90}>
                      <span className="mono small text-primary">{count(channel.registered)}</span>
                    </Cell>
                    <Cell width={240}>
                      <span className="small text-secondary" style={{ lineHeight: 1.4 }}>
                        {channel.sent_to}
                      </span>
                    </Cell>
                    <Cell width={210}>
                      <span className="small text-secondary" style={{ lineHeight: 1.4 }}>
                        {channel.dropped_when}
                      </span>
                    </Cell>
                  </TableRow>
                ))}
                <Footnote icon={Info}>
                  A dropped registration is not an error for the person: the device registers again the next time it opens. Sends, deliveries and
                  failures are written to the API's log at warn level and nowhere else.
                </Footnote>
              </Card>
            </div>
            <div className="column" style={{ flex: "0 0 340px", width: 340 }}>
              <Card fill>
                <CardHead title="When a push fails" />
                <CardBody>
                  {[
                    ["Invalid token or gone subscription", "The registration is deleted at once. Nothing is retried."],
                    ["Apple refuses the server's own setup", "BadEnvironmentKeyInToken, InvalidProviderToken and the like: every APNs push fails the same way until APNS_* is fixed, and the log says so instead of calling Apple unreachable."],
                    ["Any other error", "Logged with the device id and reason; no retry, no counter. The next message tries again."],
                    ["Devices without a registration", "Reached over their open WebSocket only. A suspended app with a stale socket still gets its push if it has one."],
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
                <div className="table__spacer" />
                <Footnote icon={BellRing}>Test notification: a button in each app's Settings, POST /push/test, at most 6 a minute per device.</Footnote>
              </Card>
            </div>
          </div>
        </>
      )}
    </PageFrame>
  );
}
