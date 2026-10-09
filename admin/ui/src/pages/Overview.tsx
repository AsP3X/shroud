import type { LucideIcon } from "lucide-react";
import { Database, Globe, HardDrive, Layers, Link2, Phone, RefreshCw, Smartphone, TriangleAlert, Radio } from "lucide-react";
import { Link } from "react-router-dom";
import { usePageData } from "../api/usePageData";
import type { Overview as OverviewData } from "../api/types";
import { LoadError, Loading } from "../components/LoadState";
import { Banner, Button, Card, CardHead, KeyValueRows, PageHeader, StatTile, StatusPill, type Tone } from "../components/ui";
import { clock, count, duration, percent, uptime } from "../format";

interface ServiceRow {
  icon: LucideIcon;
  name: string;
  detail: string;
  tone: Tone;
  label: string;
}

function probe(state: "ok" | "error" | "skipped", okDetail: string, errorDetail: string, skippedDetail?: string): Pick<ServiceRow, "detail" | "tone" | "label"> {
  if (state === "ok") return { detail: okDetail, tone: "ok", label: "Healthy" };
  if (state === "skipped") return { detail: skippedDetail ?? "Not configured", tone: "neutral", label: "Skipped" };
  return { detail: errorDetail, tone: "danger", label: "Down" };
}

function configured(detail: string | null): Pick<ServiceRow, "detail" | "tone" | "label"> {
  return detail === null
    ? { detail: "Not set in the environment", tone: "neutral", label: "Off" }
    : { detail, tone: "neutral", label: "Configured" };
}

function services(data: OverviewData): ServiceRow[] {
  const { ready, configured: c } = data;
  return [
    { icon: Database, name: "PostgreSQL", ...probe(ready.database, "Answers the readiness probe", "Not answering · the API retries by itself") },
    {
      icon: Layers,
      name: "Redis",
      ...probe(ready.redis, "Shared rate limits, presence and fan-out", "Not answering · limits counted in process meanwhile", "Not configured · limits and presence kept in process"),
    },
    { icon: HardDrive, name: "Media store", ...probe(ready.media, "Answers the readiness probe", "Not answering · uploads and downloads fail") },
    { icon: Smartphone, name: "Apple Push (APNs)", ...configured(c.apns ? `${c.apns.environment === "production" ? "Production" : "Sandbox"} · topic ${c.apns.topic}` : null) },
    { icon: Globe, name: "Web Push", ...configured(c.web_push ? `${count(c.web_push.subscriptions)} subscriptions` : null) },
    {
      icon: Radio,
      name: "UnifiedPush",
      ...configured(c.unifiedpush ? (c.unifiedpush.public_hosts ? "Any public host" : `Endpoints allowed on ${c.unifiedpush.allowed_hosts.join(", ")}`) : null),
    },
    { icon: Phone, name: "TURN relay", ...configured(c.turn ? `${c.turn.urls[0] ?? ""} · logins valid ${duration(c.turn.credential_ttl_secs)}` : null) },
    { icon: Link2, name: "Link preview relay", ...configured(`Browser TLS tunnel · at most ${c.link_relay.max_per_account} per account`) },
  ];
}

const ATTENTION: Record<OverviewData["attention"][number]["kind"], { title: (n?: number) => string; body: string; to: string }> = {
  no_min_version: {
    title: () => "No minimum client version set",
    body: "Builds from before the sender tag still accept messages the server could forge. Once the new builds are out, set IOS_MIN_VERSION and ANDROID_MIN_VERSION.",
    to: "/client-versions",
  },
  legacy_media: {
    title: (n) => `${n === undefined ? "Some" : count(n)} media read${n === 1 ? "" : "s"} from the legacy volume`,
    body: "Reads still fall back to local disk. The migration job moves blobs into Nebular as it runs.",
    to: "/storage",
  },
  not_ready: {
    title: () => "The readiness probe is failing",
    body: "Check the database and Redis containers. The server reconnects by itself once they answer.",
    to: "/",
  },
};

/** Frames "Overview", "Overview · Database down" and "Overview · Light". */
export function Overview() {
  const { data, error, loading, reload } = usePageData<OverviewData>("/overview");

  if (error) {
    return (
      <>
        <PageHeader title="Overview" />
        <LoadError error={error} onRetry={reload} what="The overview" />
      </>
    );
  }
  if (!data) {
    return (
      <>
        <PageHeader title="Overview" />
        <Loading />
      </>
    );
  }

  const { server, stats, ready, metrics, attention } = data;
  const rows = services(data);
  const traffic = [
    { key: "HTTP requests", value: count(metrics.http_requests_total) },
    { key: "HTTP errors", value: `${count(metrics.http_errors_total)} · ${percent(metrics.http_errors_total, metrics.http_requests_total)}` },
    { key: "Media uploads", value: count(metrics.media_puts_total) },
    { key: "Media downloads", value: count(metrics.media_gets_total) },
    { key: "Media store errors", value: count(metrics.media_store_errors_total) },
    { key: "Calls started", value: count(metrics.calls_created_total) },
    { key: "Legacy media reads", value: count(metrics.media_legacy_reads_total) },
    { key: "Media moved to Nebular", value: count(metrics.media_migrated_total) },
  ];

  return (
    <>
      <PageHeader
        title="Overview"
        meta={`shroud-server ${server.version} · up ${uptime(server.started_at, new Date(server.checked_at).getTime())} · checked ${clock(server.checked_at)}`}
      >
        <Button icon={RefreshCw} onClick={reload} disabled={loading}>
          Refresh
        </Button>
      </PageHeader>

      {attention[0] ? (
        <div className="mobile-only">
          <Link to={ATTENTION[attention[0].kind].to} style={{ display: "block" }}>
            <Banner title={ATTENTION[attention[0].kind].title(attention[0].count)} body={attention.length > 1 ? `${attention.length} items need attention · see below` : ATTENTION[attention[0].kind].body} />
          </Link>
        </div>
      ) : null}

      <div className="stats">
        <StatTile label="Accounts" value={count(stats.accounts)} sub={`+${count(stats.accounts_7d)} in the last 7 days · ${count(stats.accounts_deleted)} deleted`} />
        <StatTile label="Active devices" value={count(stats.devices_active_30d)} sub="Not revoked · seen in 30 days" />
        <StatTile label="Live connections" value={count(stats.ws_connections)} sub="WebSocket, right now" />
        <StatTile label="Messages relayed" value={count(stats.messages_sent_total)} sub="Since the last restart" />
      </div>

      <div className="columns">
        <div className="column">
          <Card fill>
            <CardHead title="Services" sub="Probed by /health/ready every 30 s: PostgreSQL, Redis, media store. The rest shows configuration">
              <StatusPill tone={ready.status === "ok" ? "ok" : "danger"}>{ready.status === "ok" ? "Ready" : "Not ready"}</StatusPill>
            </CardHead>
            <div className="services">
              {rows.map(({ icon: Icon, name, detail, tone, label }) => (
                <div className="service" key={name}>
                  <span className="service__icon">
                    <Icon aria-hidden="true" />
                  </span>
                  <span className="service__text">
                    <span className="service__name">{name}</span>
                    <span className="service__detail">{detail}</span>
                  </span>
                  <StatusPill tone={tone}>{label}</StatusPill>
                </div>
              ))}
            </div>
          </Card>
        </div>
        <div className="column" style={{ flex: "0 0 380px", width: 380 }}>
          <Card>
            <CardHead title="Since the last restart" sub="From /operator/metrics" />
            <KeyValueRows rows={traffic.map((row) => ({ key: row.key, value: <span className="mono text-primary">{row.value}</span> }))} />
          </Card>
          <Card fill>
            <CardHead title="Needs attention" />
            <div className="attention">
              {attention.length === 0 ? (
                <div className="attention__item">
                  <span className="attention__text">
                    <span className="attention__body">Nothing right now.</span>
                  </span>
                </div>
              ) : (
                attention.map((item) => {
                  const text = ATTENTION[item.kind];
                  return (
                    <Link to={text.to} className="attention__item" key={item.kind}>
                      <TriangleAlert aria-hidden="true" />
                      <span className="attention__text">
                        <span className="attention__title">{text.title(item.count)}</span>
                        <span className="attention__body">{text.body}</span>
                      </span>
                    </Link>
                  );
                })
              )}
            </div>
          </Card>
        </div>
      </div>
    </>
  );
}
