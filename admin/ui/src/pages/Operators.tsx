import { Check, Copy, Ellipsis, KeyRound, ShieldAlert, ShieldCheck, UserPlus } from "lucide-react";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { api } from "../api/client";
import { usePageData } from "../api/usePageData";
import type { Operator, Role, SetupUrl } from "../api/types";
import { Dialog } from "../components/Dialog";
import { PageFrame } from "../components/PageFrame";
import { useWrite, type WriteOutcome } from "../components/Reauth";
import { useSession } from "../components/Shell";
import { useToast } from "../components/Toast";
import { Button, Card, CardHead, Cell, KeyValueRows, PageHeader, StatusPill, TableHead, TableRow } from "../components/ui";
import { count } from "../format";

const ROLE_LABEL: Record<Role, string> = { write: "Admin", read: "View only" };
const when = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });

type Pending = { kind: "invite" } | { kind: "link"; url: string; name: string } | { kind: "disable"; operator: Operator } | { kind: "reset"; operator: Operator } | null;

/** Frame "Operators" (contract §3.6). A `read` operator sees the list without buttons. */
export function Operators() {
  const page = usePageData<Operator[]>("/operators");
  const { session } = useSession();
  const toast = useToast();
  const { write, reauthDialog } = useWrite();
  const [pending, setPending] = useState<Pending>(null);
  const [failure, setFailure] = useState<Extract<WriteOutcome, { ok: false }> | null>(null);
  const [busy, setBusy] = useState(false);
  const canWrite = session?.operator.role === "write";

  const run = async <T,>(what: string, call: () => Promise<T>): Promise<T | null> => {
    setBusy(true);
    let result: T | null = null;
    const outcome = await write(what, async () => {
      result = await call();
    });
    setBusy(false);
    if (!outcome.ok) {
      if (outcome.title !== "Cancelled") setFailure(outcome);
      return null;
    }
    page.reload();
    return result;
  };

  return (
    <PageFrame title="Operators" page={page}>
      {(operators) => {
        const admins = operators.filter((operator) => operator.role === "write").length;
        return (
          <>
            <PageHeader title="Operators" meta="People who can sign in to this console">
              {canWrite ? (
                <Button variant="primary" icon={UserPlus} onClick={() => setPending({ kind: "invite" })}>
                  Add operator
                </Button>
              ) : null}
            </PageHeader>
            <div className="columns">
              <div className="column">
                <Card>
                  <CardHead title="Operators" sub={`${count(operators.length)} ${operators.length === 1 ? "operator" : "operators"} · ${count(admins)} ${admins === 1 ? "admin" : "admins"}`} />
                  <TableHead>
                    <Cell>Operator</Cell>
                    <Cell width={100}>Role</Cell>
                    <Cell width={130}>Authenticator</Cell>
                    <Cell width={130}>Last sign-in</Cell>
                    <Cell width={96}>Status</Cell>
                    <Cell width={36} />
                  </TableHead>
                  {operators.map((operator) => (
                    <OperatorRow
                      key={operator.id}
                      operator={operator}
                      you={operator.id === session?.operator.id}
                      canWrite={canWrite}
                      onRole={(role) => void run("Changing a role", () => api<void>(`/operators/${operator.id}`, { method: "PATCH", body: { role } })).then((done) => done !== null && toast(`${operator.name} is now ${ROLE_LABEL[role].toLowerCase()}.`))}
                      onDisable={() => setPending({ kind: "disable", operator })}
                      onEnable={() => void run("Enabling an operator", () => api<void>(`/operators/${operator.id}`, { method: "PATCH", body: { enabled: true } })).then((done) => done !== null && toast(`${operator.name} can sign in again.`))}
                      onReset={() => setPending({ kind: "reset", operator })}
                    />
                  ))}
                </Card>
              </div>
              <div className="column" style={{ flex: "0 0 360px", width: 360 }}>
                <Card>
                  <CardHead title="Roles" />
                  <KeyValueRows
                    rows={[
                      { key: "Admin", value: <span className="text-primary">Every page and action</span> },
                      { key: "View only", value: <span className="text-primary">Reads pages, changes nothing</span> },
                    ]}
                  />
                </Card>
                <Card>
                  <CardHead title="Signing in" />
                  <KeyValueRows
                    rows={[
                      { key: "Session", value: <span className="text-primary">12 h · ends after 30 min idle</span> },
                      { key: "Writes", value: <span className="text-primary">A fresh code within 5 min</span> },
                      { key: "Recovery", value: <span className="text-primary">8 one-time codes at setup</span> },
                    ]}
                  />
                </Card>
              </div>
            </div>

            {pending?.kind === "invite" ? (
              <InviteDialog
                busy={busy}
                onClose={() => setPending(null)}
                onInvite={(name, role) =>
                  void run("Inviting an operator", () => api<SetupUrl>("/operators", { method: "POST", body: { name, role } })).then((result) => {
                    if (result) setPending({ kind: "link", url: result.setup_url, name });
                  })
                }
              />
            ) : null}
            {pending?.kind === "link" ? <LinkDialog name={pending.name} url={pending.url} onClose={() => setPending(null)} /> : null}
            {pending?.kind === "disable" ? (
              <Dialog
                icon={ShieldAlert}
                tone="danger"
                title={`Disable ${pending.operator.name}?`}
                body="Their sessions end now and they can't sign in until an admin enables them again. Nothing they did is removed from the audit log."
                onClose={() => setPending(null)}
                actions={
                  <>
                    <Button onClick={() => setPending(null)} disabled={busy}>
                      Cancel
                    </Button>
                    <Button
                      variant="danger"
                      icon={ShieldAlert}
                      disabled={busy}
                      onClick={() => {
                        const target = pending.operator;
                        void run("Disabling an operator", () => api<void>(`/operators/${target.id}`, { method: "PATCH", body: { enabled: false } })).then((done) => {
                          setPending(null);
                          if (done !== null) toast(`${target.name} is disabled.`);
                        });
                      }}
                    >
                      {busy ? "Disabling…" : "Disable"}
                    </Button>
                  </>
                }
              />
            ) : null}
            {pending?.kind === "reset" ? (
              <Dialog
                icon={KeyRound}
                tone="warn"
                title={`Reset the authenticator of ${pending.operator.name}?`}
                body="Their current codes stop working at once. You get a one-time setup link to pass on; they choose a new password and enrol an authenticator again."
                onClose={() => setPending(null)}
                actions={
                  <>
                    <Button onClick={() => setPending(null)} disabled={busy}>
                      Cancel
                    </Button>
                    <Button
                      className="button--warn"
                      icon={KeyRound}
                      disabled={busy}
                      onClick={() => {
                        const target = pending.operator;
                        void run("Resetting an authenticator", () => api<SetupUrl>(`/operators/${target.id}/reset-totp`, { method: "POST" })).then((result) => {
                          if (result) setPending({ kind: "link", url: result.setup_url, name: target.name });
                          else setPending(null);
                        });
                      }}
                    >
                      {busy ? "Resetting…" : "Reset"}
                    </Button>
                  </>
                }
              />
            ) : null}
            {failure ? (
              <Dialog icon={ShieldAlert} tone="danger" title={failure.title} body={failure.body} onClose={() => setFailure(null)} actions={<Button variant="primary" onClick={() => setFailure(null)}>OK</Button>} />
            ) : null}
            {reauthDialog}
          </>
        );
      }}
    </PageFrame>
  );
}

function OperatorRow({ operator, you, canWrite, onRole, onDisable, onEnable, onReset }: { operator: Operator; you: boolean; canWrite: boolean; onRole: (role: Role) => void; onDisable: () => void; onEnable: () => void; onReset: () => void }) {
  const [open, setOpen] = useState(false);
  const anchor = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const onDown = (event: MouseEvent) => {
      if (!anchor.current?.contains(event.target as Node)) setOpen(false);
    };
    const onKey = (event: KeyboardEvent) => event.key === "Escape" && setOpen(false);
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  const status = !operator.enabled ? { tone: "neutral" as const, label: "Disabled" } : operator.totp_enrolled ? { tone: "ok" as const, label: "Active" } : { tone: "warn" as const, label: "Invited" };
  const initials = operator.name.slice(0, 2).toUpperCase();
  const choose = (action: () => void) => {
    setOpen(false);
    action();
  };

  return (
    <TableRow style={{ minHeight: 54 }}>
      <Cell>
        <span className="operator__avatar" aria-hidden="true">
          {initials}
        </span>
        <span className="text-primary" style={{ fontWeight: 500 }}>
          {operator.name}
        </span>
        {you ? <span className="chip">You</span> : null}
      </Cell>
      <Cell width={100}>{ROLE_LABEL[operator.role]}</Cell>
      <Cell width={130}>
        {operator.totp_enrolled ? <ShieldCheck size={15} aria-hidden="true" /> : <ShieldAlert size={15} aria-hidden="true" />}
        {operator.totp_enrolled ? "Set up" : "Not set up yet"}
      </Cell>
      <Cell width={130}>{operator.last_sign_in_at ? when.format(new Date(operator.last_sign_in_at)) : "Never"}</Cell>
      <Cell width={96} className="cell--keep">
        <StatusPill tone={status.tone}>{status.label}</StatusPill>
      </Cell>
      <Cell width={36} right>
        {canWrite ? (
          <div className="menu-anchor" ref={anchor}>
            <button type="button" className="icon-button" aria-label={`Actions for ${operator.name}`} aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((value) => !value)}>
              <Ellipsis aria-hidden="true" />
            </button>
            {open ? (
              <div className="menu" role="menu">
                {operator.role === "write" ? (
                  <button type="button" role="menuitem" className="menu__item" onClick={() => choose(() => onRole("read"))} disabled={you}>
                    Make view only{you ? " (not yourself)" : ""}
                  </button>
                ) : (
                  <button type="button" role="menuitem" className="menu__item" onClick={() => choose(() => onRole("write"))}>
                    Make admin
                  </button>
                )}
                <button type="button" role="menuitem" className="menu__item" onClick={() => choose(onReset)}>
                  Reset authenticator
                </button>
                {operator.enabled ? (
                  <button type="button" role="menuitem" className="menu__item menu__item--danger" onClick={() => choose(onDisable)} disabled={you}>
                    Disable{you ? " (not yourself)" : ""}
                  </button>
                ) : (
                  <button type="button" role="menuitem" className="menu__item" onClick={() => choose(onEnable)}>
                    Enable
                  </button>
                )}
              </div>
            ) : null}
          </div>
        ) : null}
      </Cell>
    </TableRow>
  );
}

function InviteDialog({ busy, onClose, onInvite }: { busy: boolean; onClose: () => void; onInvite: (name: string, role: Role) => void }) {
  const [name, setName] = useState("");
  const [role, setRole] = useState<Role>("read");
  const valid = /^[a-z0-9_.-]{2,32}$/i.test(name.trim());
  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (valid && !busy) onInvite(name.trim(), role);
  };
  return (
    <Dialog
      icon={UserPlus}
      tone="accent"
      title="Add an operator"
      body="They get a one-time link to choose a password and enrol an authenticator. The link is shown to you once."
      onClose={onClose}
      actions={
        <>
          <Button onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button variant="primary" icon={UserPlus} type="submit" form="invite-form" disabled={!valid || busy}>
            {busy ? "Creating…" : "Create link"}
          </Button>
        </>
      }
    >
      <form id="invite-form" className="auth-form" onSubmit={submit}>
        <label className="field">
          <span className="field__label">
            <span>Name</span>
          </span>
          <span className="input input--mono">
            <input autoComplete="off" autoCapitalize="none" spellCheck={false} value={name} onChange={(event) => setName(event.target.value)} disabled={busy} placeholder="e.g. ops2" />
          </span>
        </label>
        <div className="field">
          <span className="field__label">
            <span>Role</span>
          </span>
          <div className="radio-row" role="radiogroup" aria-label="Role">
            {(["read", "write"] as Role[]).map((candidate) => (
              <button key={candidate} type="button" role="radio" aria-checked={role === candidate} className={role === candidate ? "radio radio--active" : "radio"} onClick={() => setRole(candidate)}>
                <span className="radio__title">{ROLE_LABEL[candidate]}</span>
                <span className="small text-tertiary">{candidate === "write" ? "Every page and action" : "Reads pages, changes nothing"}</span>
              </button>
            ))}
          </div>
        </div>
      </form>
    </Dialog>
  );
}

function LinkDialog({ name, url, onClose }: { name: string; url: string; onClose: () => void }) {
  const [copied, setCopied] = useState(false);
  const full = new URL(url, window.location.origin).toString();
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(full);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  };
  return (
    <Dialog
      icon={KeyRound}
      tone="accent"
      title={`Setup link for ${name}`}
      body="Pass it on over a channel you trust. It works once and expires 15 minutes after it was made; this is the only time it is shown."
      onClose={onClose}
      actions={
        <>
          <Button icon={copied ? Check : Copy} onClick={() => void copy()}>
            {copied ? "Copied" : "Copy link"}
          </Button>
          <Button variant="primary" onClick={onClose}>
            Done
          </Button>
        </>
      }
    >
      <div className="setup-link">
        <code>{full}</code>
      </div>
    </Dialog>
  );
}
