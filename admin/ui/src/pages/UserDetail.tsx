import { Bot, ChevronLeft, Globe, Lock, LogOut, ScrollText, Smartphone, Trash2, User, UserX, Monitor } from "lucide-react";
import { Link, useParams } from "react-router-dom";
import { usePageData } from "../api/usePageData";
import type { AuditEntry, Device, Page, UserDetail as UserDetailData } from "../api/types";
import { LoadError, Loading } from "../components/LoadState";
import { Button, Card, CardHead, Cell, Footnote, KeyValueRows, StatusPill, TableHead, TableRow } from "../components/ui";
import { bytes, count, day, relativeDay, shortId } from "../format";

const PLATFORM: Record<Device["platform"], { icon: typeof Smartphone; label: string }> = {
  ios: { icon: Smartphone, label: "iOS app" },
  web: { icon: Globe, label: "Web browser" },
  android: { icon: Bot, label: "Android app" },
  unknown: { icon: Monitor, label: "Device" },
};

const PUSH: Record<Device["push"], string> = {
  apns: "APNs · alert",
  "apns+voip": "APNs · alert + VoIP",
  web: "Web Push",
  unifiedpush: "UnifiedPush",
  none: "—",
};

const ACTION_TEXT: Record<string, string> = {
  "device.remove": "Removed device",
  "user.sign_out_all": "Signed out all devices",
  "user.delete": "Deleted the account",
};

function DeviceRow({ device }: { device: Device }) {
  const { icon: Icon, label } = PLATFORM[device.platform];
  const session = device.revoked
    ? { tone: "neutral" as const, label: "Removed" }
    : device.session === "live"
      ? { tone: "ok" as const, label: "Active" }
      : { tone: "warn" as const, label: "Stale" };
  return (
    <TableRow style={{ minHeight: 60 }}>
      <Cell>
        <span className="service__icon">
          <Icon aria-hidden="true" />
        </span>
        <span className="name-block">
          <span className="device__line">
            <span className="device__platform">{label}</span>
            <span className="mono small text-tertiary">{shortId(device.id)}</span>
          </span>
          <span className="device__sealed">
            <Lock size={11} aria-hidden="true" /> Name sealed · added {day(device.added_on)}
          </span>
        </span>
      </Cell>
      <Cell width={110}>{relativeDay(device.last_seen_on)}</Cell>
      <Cell width={150} className={device.push === "none" ? "text-tertiary" : ""}>
        {PUSH[device.push]}
      </Cell>
      <Cell width={100}>
        <StatusPill tone={session.tone}>{session.label}</StatusPill>
      </Cell>
      <Cell width={72} right>
        {device.revoked ? null : (
          <button type="button" className="link-button link-button--danger" disabled title="Device removal arrives with the operator API (task C2.2)">
            Remove
          </button>
        )}
      </Cell>
    </TableRow>
  );
}

/** Frame "User detail", read-only: the two real actions are drawn but disabled until C2. */
export function UserDetail() {
  const { id = "" } = useParams();
  const user = usePageData<UserDetailData>(`/users/${encodeURIComponent(id)}`);
  const audit = usePageData<Page<AuditEntry>>(`/audit-log?target=${encodeURIComponent(id)}&limit=20`);

  if (user.error) {
    return (
      <>
        <Breadcrumb />
        <LoadError error={user.error} onRetry={user.reload} what="This account" />
      </>
    );
  }
  if (!user.data) {
    return (
      <>
        <Breadcrumb />
        <Loading />
      </>
    );
  }

  const data = user.data;
  const deleted = data.status === "deleted";
  const active = data.devices.filter((device) => !device.revoked);
  const removed = data.devices.length - active.length;
  const entries = (audit.data?.items ?? []).filter((entry) => entry.target_id === data.id || entry.target_kind === "user" && entry.target_id === data.id || data.devices.some((device) => device.id === entry.target_id));
  const notVisible = <span className="text-tertiary">Not visible</span>;

  return (
    <>
      <Breadcrumb />
      <header className="page-header">
        <span className="avatar avatar--56">{deleted ? <UserX aria-hidden="true" /> : <User aria-hidden="true" />}</span>
        <div className="page-header__title">
          <div className="title-line">
            <h1 className="mono">{data.id.slice(0, 8)}</h1>
            {deleted ? <StatusPill tone="neutral">Deleted</StatusPill> : <StatusPill tone="ok">Active</StatusPill>}
          </div>
          <div className="small mono text-tertiary">{data.id}</div>
        </div>
        {deleted ? null : (
          <>
            <Button icon={LogOut} disabled title="Arrives with the operator API (task C2.2)">
              Sign out all devices
            </Button>
            <Button variant="danger" icon={Trash2} disabled title="Arrives with the operator API (task C2.2)">
              Delete account
            </Button>
          </>
        )}
      </header>

      <div className="columns">
        <div className="column">
          <Card>
            <CardHead title="Devices" sub={`${count(active.length)} active · ${count(removed)} removed`} />
            <TableHead>
              <Cell>Device</Cell>
              <Cell width={110}>Last seen</Cell>
              <Cell width={150}>Push</Cell>
              <Cell width={100}>Session</Cell>
              <Cell width={72} />
            </TableHead>
            {data.devices.map((device) => (
              <DeviceRow key={device.id} device={device} />
            ))}
            {data.devices.length === 0 ? <div className="empty__body" style={{ padding: "16px 20px" }}>No devices.</div> : null}
            <Footnote>
              Removing a device revokes its session and wipes it on its next connection. The device row is kept so message
              history stays consistent for the other participants.
            </Footnote>
          </Card>

          <Card fill>
            <div className="card__head">
              <div className="card__head-text">
                <div className="card__title">Admin actions on this account</div>
              </div>
              <Link to="/audit-log" className="text-link">
                Open audit log
              </Link>
            </div>
            <div className="entries">
              {audit.error ? <div className="empty__body">The audit log couldn't load.</div> : null}
              {!audit.error && audit.data && entries.length === 0 ? <div className="empty__body">No operator has acted on this account.</div> : null}
              {entries.map((entry, index) => (
                <div className="entry" key={index}>
                  <ScrollText size={15} aria-hidden="true" className="text-tertiary" />
                  <span className="entry__what">
                    {ACTION_TEXT[entry.action] ?? entry.action}
                    {entry.target_kind === "device" && entry.target_id ? ` ${shortId(entry.target_id)}` : ""}
                    {entry.outcome !== "ok" ? ` · ${entry.outcome}` : ""}
                  </span>
                  <span className="small text-secondary">{entry.operator}</span>
                  <span className="small text-tertiary">{new Date(entry.at).toLocaleString("en-GB", { dateStyle: "medium", timeStyle: "short" })}</span>
                </div>
              ))}
            </div>
          </Card>
        </div>

        <div className="column" style={{ flex: "0 0 360px", width: 360 }}>
          <Card>
            <CardHead title="Account" />
            <KeyValueRows
              rows={[
                { key: "Created", value: <span className="text-primary">{day(data.created_on)}</span> },
                { key: "Last activity", value: <span className="text-primary">{relativeDay(data.last_active_on)}</span> },
                { key: "Username", value: <span className="text-tertiary">Not stored on this server</span> },
                { key: "Contacts", value: data.counts.contacts === null ? notVisible : <span className="text-primary">{count(data.counts.contacts)}</span> },
                { key: "Blocks", value: data.counts.blocks === null ? notVisible : <span className="text-primary">{count(data.counts.blocks)}</span> },
                { key: "Conversations", value: data.counts.conversations === null ? notVisible : <span className="text-primary">{count(data.counts.conversations)}</span> },
                { key: "Messages", value: notVisible },
              ]}
            />
          </Card>
          <Card>
            <CardHead title="Storage and lock" />
            <KeyValueRows
              rows={[
                {
                  key: "Media stored",
                  value: <span className="text-primary">{`${bytes(data.counts.media_bytes)} · ${count(data.counts.media_objects)} objects`}</span>,
                },
                { key: "PIN guard", value: <span className={data.pin_guard ? "text-primary" : "text-tertiary"}>{data.pin_guard ? "Set on a device" : "None"}</span> },
              ]}
            />
          </Card>
        </div>
      </div>
    </>
  );
}

function Breadcrumb() {
  return (
    <Link to="/users" className="breadcrumb">
      <ChevronLeft size={15} aria-hidden="true" /> Users
    </Link>
  );
}
