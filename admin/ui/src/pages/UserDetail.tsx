import { Bot, ChevronLeft, Globe, Lock, LogOut, Monitor, ScrollText, Smartphone, Trash2, User, UserX } from "lucide-react";
import { useState, type FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { api } from "../api/client";
import { usePageData } from "../api/usePageData";
import type { AuditEntry, Device, Page, UserDetail as UserDetailData } from "../api/types";
import { Dialog } from "../components/Dialog";
import { LoadError, Loading } from "../components/LoadState";
import { useWrite, type WriteOutcome } from "../components/Reauth";
import { useSession } from "../components/Shell";
import { useToast } from "../components/Toast";
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

type Pending = { kind: "remove"; device: Device } | { kind: "sign-out-all" } | { kind: "delete" } | null;

/** Frame "User detail" with its four action frames: Remove device, Sign out all devices,
 *  Delete account (typed confirmation) and the Device removed toast. Every write goes through
 *  useWrite, which asks for a fresh code on 403 REAUTH_REQUIRED. */
export function UserDetail() {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const { session } = useSession();
  const toast = useToast();
  const user = usePageData<UserDetailData>(`/users/${encodeURIComponent(id)}`);
  const audit = usePageData<Page<AuditEntry>>(`/audit-log?target=${encodeURIComponent(id)}&limit=20`);
  const { write, reauthDialog } = useWrite();
  const [pending, setPending] = useState<Pending>(null);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<Extract<WriteOutcome, { ok: false }> | null>(null);
  const canWrite = session?.operator.role === "write";

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
  const entries = (audit.data?.items ?? []).filter((entry) => entry.target_id === data.id || data.devices.some((device) => device.id === entry.target_id));
  const notVisible = <span className="text-tertiary">Not visible</span>;
  const short = data.id.slice(0, 8);

  const perform = async (what: string, path: string, body: unknown, done: string, after?: () => void) => {
    setBusy(true);
    const outcome = await write(what, () => api<void>(path, { method: "POST", body }));
    setBusy(false);
    setPending(null);
    if (outcome.ok) {
      toast(done);
      user.reload();
      audit.reload();
      after?.();
    } else if (outcome.title !== "Cancelled") {
      setFailure(outcome);
    }
  };

  return (
    <>
      <Breadcrumb />
      <header className="page-header">
        <span className="avatar avatar--56">{deleted ? <UserX aria-hidden="true" /> : <User aria-hidden="true" />}</span>
        <div className="page-header__title">
          <div className="title-line">
            <h1 className="mono">{short}</h1>
            {deleted ? <StatusPill tone="neutral">Deleted</StatusPill> : <StatusPill tone="ok">Active</StatusPill>}
          </div>
          <div className="small mono text-tertiary">{data.id}</div>
        </div>
        {deleted || !canWrite ? null : (
          <>
            <Button icon={LogOut} onClick={() => setPending({ kind: "sign-out-all" })} disabled={active.length === 0}>
              Sign out all devices
            </Button>
            <Button variant="danger" icon={Trash2} onClick={() => setPending({ kind: "delete" })}>
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
              <DeviceRow key={device.id} device={device} canRemove={canWrite && !deleted} onRemove={() => setPending({ kind: "remove", device })} />
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
                <h2 className="card__title">Admin actions on this account</h2>
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
                    {entry.target_kind === "device" && entry.target_id ? ` ${deviceLabel(data.devices, entry.target_id)}` : ""}
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
                { key: "Media stored", value: <span className="text-primary">{`${bytes(data.counts.media_bytes)} · ${count(data.counts.media_objects)} objects`}</span> },
                { key: "PIN guard", value: <span className={data.pin_guard ? "text-primary" : "text-tertiary"}>{data.pin_guard ? "Set on a device" : "None"}</span> },
              ]}
            />
          </Card>
        </div>
      </div>

      {pending?.kind === "remove" ? (
        <Dialog
          icon={PLATFORM[pending.device.platform].icon}
          tone="danger"
          title={`Remove ${PLATFORM[pending.device.platform].label} ${shortId(pending.device.id)}?`}
          body={`Its session ends now, and it wipes its chats and keys the next time it connects. The account keeps working on its other ${count(Math.max(0, active.length - 1))} ${active.length - 1 === 1 ? "device" : "devices"}.`}
          onClose={() => setPending(null)}
          actions={
            <>
              <Button onClick={() => setPending(null)} disabled={busy}>
                Cancel
              </Button>
              <Button
                variant="primary"
                icon={PLATFORM[pending.device.platform].icon}
                disabled={busy}
                onClick={() => void perform("Removing a device", `/users/${data.id}/devices/${pending.device.id}/remove`, undefined, "Device removed. It wipes itself the next time it connects.")}
              >
                {busy ? "Removing…" : "Remove device"}
              </Button>
            </>
          }
        />
      ) : null}

      {pending?.kind === "sign-out-all" ? (
        <Dialog
          icon={LogOut}
          tone="warn"
          title={`Sign out all ${count(active.length)} ${active.length === 1 ? "device" : "devices"}?`}
          body={`Every session of ${short} ends now. The devices stay registered, and anyone with the account's password can sign in again.`}
          onClose={() => setPending(null)}
          actions={
            <>
              <Button onClick={() => setPending(null)} disabled={busy}>
                Cancel
              </Button>
              <Button className="button--warn" icon={LogOut} disabled={busy} onClick={() => void perform("Signing out every device", `/users/${data.id}/sign-out-all`, undefined, "Signed out all devices. They can sign in again with the password.")}>
                {busy ? "Signing out…" : "Sign out all"}
              </Button>
            </>
          }
        />
      ) : null}

      {pending?.kind === "delete" ? (
        <DeleteDialog
          short={short}
          devices={active.length}
          busy={busy}
          onClose={() => setPending(null)}
          onConfirm={() => void perform("Deleting an account", `/users/${data.id}/delete`, { confirm: data.id }, `Account ${short} deleted. Its devices wipe on their next connection.`, () => navigate("/users"))}
        />
      ) : null}

      {failure ? (
        <Dialog
          icon={Lock}
          tone="danger"
          title={failure.title}
          body={failure.body}
          onClose={() => setFailure(null)}
          actions={
            <Button variant="primary" onClick={() => setFailure(null)}>
              OK
            </Button>
          }
        />
      ) : null}

      {reauthDialog}
    </>
  );
}

function deviceLabel(devices: Device[], id: string): string {
  const device = devices.find((candidate) => candidate.id === id);
  return `${device ? PLATFORM[device.platform].label : "device"} ${shortId(id)}`;
}

function DeleteDialog({ short, devices, busy, onClose, onConfirm }: { short: string; devices: number; busy: boolean; onClose: () => void; onConfirm: () => void }) {
  const [typed, setTyped] = useState("");
  const ready = typed.trim().toLowerCase() === short.toLowerCase();
  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (ready && !busy) onConfirm();
  };
  return (
    <Dialog
      icon={Trash2}
      tone="danger"
      title={`Delete account ${short}?`}
      body={`All ${count(devices)} ${devices === 1 ? "device is" : "devices are"} signed out and wiped, and the account's keys, contacts and stored media are deleted. The username becomes free to register again. A placeholder stays so other people's chats show “Deleted account”. This can't be undone.`}
      onClose={onClose}
      actions={
        <>
          <Button onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button variant="danger" icon={Trash2} type="submit" form="delete-form" disabled={!ready || busy}>
            {busy ? "Deleting…" : "Delete account"}
          </Button>
        </>
      }
    >
      <form id="delete-form" className="field" onSubmit={submit}>
        <span className="field__label">
          <span>Type {short} to confirm</span>
        </span>
        <span className="input input--mono">
          <input autoComplete="off" autoCapitalize="none" spellCheck={false} value={typed} onChange={(event) => setTyped(event.target.value)} disabled={busy} aria-label={`Type ${short} to confirm`} />
        </span>
      </form>
    </Dialog>
  );
}

function DeviceRow({ device, canRemove, onRemove }: { device: Device; canRemove: boolean; onRemove: () => void }) {
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
      <Cell width={100} className="cell--keep">
        <StatusPill tone={session.tone}>{session.label}</StatusPill>
      </Cell>
      <Cell width={72} right>
        {device.revoked || !canRemove ? null : (
          <button type="button" className="link-button link-button--danger" onClick={onRemove} aria-label={`Remove ${label} ${shortId(device.id)}`}>
            Remove
          </button>
        )}
      </Cell>
    </TableRow>
  );
}

function Breadcrumb() {
  return (
    <Link to="/users" className="breadcrumb">
      <ChevronLeft size={15} aria-hidden="true" /> Users
    </Link>
  );
}
