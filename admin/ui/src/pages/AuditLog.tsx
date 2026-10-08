import type { LucideIcon } from "lucide-react";
import { ChevronLeft, ChevronRight, KeyRound, LogIn, LogOut, ScrollText, Smartphone, UserX, Wrench } from "lucide-react";
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ApiError, api } from "../api/client";
import type { AuditEntry, Page } from "../api/types";
import { Card, Cell, PageHeader, SearchField, StatusPill, TableHead, TableRow, type Tone } from "../components/ui";
import { count, shortId } from "../format";
import { EmptyBlock } from "./Users";

const LIMIT = 100;
const ID = /^[0-9a-f-]{4,36}$/i;

type Segment = "all" | "account" | "sign-in" | "maintenance";

const SEGMENTS: { key: Segment; label: string; test: (action: string) => boolean }[] = [
  { key: "all", label: "All", test: () => true },
  { key: "account", label: "Account changes", test: (action) => action.startsWith("user.") || action.startsWith("device.") },
  { key: "sign-in", label: "Sign-ins", test: (action) => action.startsWith("session.") || action.startsWith("setup.") },
  { key: "maintenance", label: "Maintenance", test: (action) => action.startsWith("operator.") || action.startsWith("media.") },
];

const ACTIONS: Record<string, { label: string; icon: LucideIcon }> = {
  "session.sign_in": { label: "Signed in", icon: LogIn },
  "session.sign_out": { label: "Signed out", icon: LogOut },
  "session.recovery": { label: "Signed in with a recovery code", icon: KeyRound },
  "setup.complete": { label: "Finished setup", icon: KeyRound },
  "device.remove": { label: "Removed device", icon: Smartphone },
  "user.sign_out_all": { label: "Signed out all devices", icon: LogOut },
  "user.delete": { label: "Deleted account", icon: UserX },
  "operator.create": { label: "Invited an operator", icon: KeyRound },
  "operator.update": { label: "Changed an operator", icon: KeyRound },
  "operator.reset_totp": { label: "Reset an authenticator", icon: KeyRound },
  "media.orphan_sweep": { label: "Orphan media sweep", icon: Wrench },
};

const OUTCOME: Record<AuditEntry["outcome"], { label: string; tone: Tone }> = {
  ok: { label: "Done", tone: "ok" },
  refused: { label: "Rejected", tone: "warn" },
  failed: { label: "Failed", tone: "danger" },
};

function describe(action: string): { label: string; icon: LucideIcon } {
  return ACTIONS[action] ?? { label: action, icon: ScrollText };
}

function target(entry: AuditEntry): string {
  const parts: string[] = [];
  if (entry.target_kind === "user" && entry.target_id) parts.push(entry.target_id.slice(0, 8));
  if (entry.target_kind === "device" && entry.target_id) parts.push(`device ${shortId(entry.target_id)}`);
  if (entry.target_kind === "operator" && entry.target_id) parts.push(`operator ${entry.target_id.slice(0, 8)}`);
  if (entry.detail) parts.push(entry.detail);
  return parts.join(" · ") || "—";
}

const when = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit", second: "2-digit" });

/** Frames "Audit log" and "Audit log · Empty". Entries come from the console's own schema and
 *  are never editable here. No client address appears: the backend records none. */
export function AuditLog() {
  const navigate = useNavigate();
  const [segment, setSegment] = useState<Segment>("all");
  const [filter, setFilter] = useState("");
  const [cursors, setCursors] = useState<(string | null)[]>([null]);
  const [state, setState] = useState<{ page: Page<AuditEntry> | null; error: Error | null; loading: boolean }>({ page: null, error: null, loading: true });
  const [tick, setTick] = useState(0);
  const cursor = cursors[cursors.length - 1] ?? null;
  const targetFilter = ID.test(filter) ? filter : null;

  useEffect(() => {
    let cancelled = false;
    setState((current) => ({ ...current, loading: true }));
    const search = new URLSearchParams({ limit: String(LIMIT) });
    if (cursor) search.set("cursor", cursor);
    if (targetFilter) search.set("target", targetFilter);
    api<Page<AuditEntry>>(`/audit-log?${search}`)
      .then((page) => !cancelled && setState({ page, error: null, loading: false }))
      .catch((error: unknown) => {
        if (cancelled) return;
        if (error instanceof ApiError && error.status === 401) {
          navigate("/sign-in", { replace: true, state: { from: "/audit-log" } });
          return;
        }
        setState({ page: null, error: error instanceof Error ? error : new Error(String(error)), loading: false });
      });
    return () => {
      cancelled = true;
    };
  }, [cursor, targetFilter, tick, navigate]);

  const test = SEGMENTS.find((entry) => entry.key === segment)?.test ?? (() => true);
  const operatorFilter = filter && !targetFilter ? filter.toLowerCase() : null;
  const items = (state.page?.items ?? []).filter((entry) => test(entry.action) && (!operatorFilter || entry.operator.toLowerCase().includes(operatorFilter)));
  const pageIndex = cursors.length - 1;

  return (
    <>
      <PageHeader title="Audit log" meta="Every operator sign-in and action. Entries can't be edited or removed here." />
      <div className="toolbar">
        <div className="segments" role="tablist" aria-label="Kind of entry">
          {SEGMENTS.map(({ key, label }) => (
            <button key={key} type="button" role="tab" aria-selected={segment === key} className={segment === key ? "segment segment--active" : "segment"} onClick={() => setSegment(key)}>
              {label}
            </button>
          ))}
        </div>
        <span className="toolbar__spacer" />
        <SearchField placeholder="Filter by operator or account ID" value={filter} onChange={(event) => setFilter(event.target.value.trim())} />
      </div>
      <Card fill>
        <TableHead>
          <Cell width={170}>Time</Cell>
          <Cell width={130}>Operator</Cell>
          <Cell>Action</Cell>
          <Cell width={260}>Target</Cell>
          <Cell width={130}>Result</Cell>
        </TableHead>
        {state.error ? (
          <EmptyBlock icon={ScrollText} title="Couldn't load the audit log" body={state.error.message}>
            <button type="button" className="button button--secondary" onClick={() => setTick((value) => value + 1)}>
              Try again
            </button>
          </EmptyBlock>
        ) : null}
        {!state.error && !state.loading && items.length === 0 ? (
          <EmptyBlock
            icon={ScrollText}
            title={state.page && state.page.items.length > 0 ? "Nothing matches" : "Nothing logged yet"}
            body={state.page && state.page.items.length > 0 ? "No entry on this page matches the filter." : "Operator sign-ins, and every action taken on an account, appear here as they happen."}
          />
        ) : null}
        {items.map((entry, index) => {
          const { label, icon: Icon } = describe(entry.action);
          const outcome = OUTCOME[entry.outcome];
          return (
            <TableRow key={`${entry.at}-${index}`} style={{ minHeight: 52 }}>
              <Cell width={170}>
                <span className="mono small">{when.format(new Date(entry.at))}</span>
              </Cell>
              <Cell width={130}>
                <span className="mono text-primary">{entry.operator}</span>
              </Cell>
              <Cell>
                <Icon size={15} aria-hidden="true" className="text-tertiary" />
                <span className="text-primary" style={{ fontWeight: 500 }}>
                  {label}
                </span>
              </Cell>
              <Cell width={260}>
                <span className="text-secondary" style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                  {target(entry)}
                </span>
              </Cell>
              <Cell width={130}>
                <StatusPill tone={outcome.tone}>{outcome.label}</StatusPill>
              </Cell>
            </TableRow>
          );
        })}
        <div className="table__spacer" />
        <div className="table-footer">
          <span className="small text-tertiary">Sign-ins carry no client address.</span>
          <div className="pager">
            <span className="small text-secondary">{state.loading ? "Loading…" : items.length === 0 ? "0 entries" : `${count(pageIndex * LIMIT + 1)}–${count(pageIndex * LIMIT + items.length)}`}</span>
            <button type="button" className="pager__button" aria-label="Newer" disabled={pageIndex === 0} onClick={() => setCursors((stack) => stack.slice(0, -1))}>
              <ChevronLeft size={14} aria-hidden="true" />
            </button>
            <button type="button" className="pager__button" aria-label="Older" disabled={!state.page?.next_cursor} onClick={() => state.page?.next_cursor && setCursors((stack) => [...stack, state.page?.next_cursor ?? null])}>
              <ChevronRight size={14} aria-hidden="true" />
            </button>
          </div>
        </div>
      </Card>
    </>
  );
}
