import { Bot, ChevronLeft, ChevronRight, Globe, Layers, Monitor, RefreshCw, SearchX, Smartphone, User, UserX } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { ApiError, api } from "../api/client";
import type { AccountStatus, PushKind, UserRow, UsersPage } from "../api/types";
import { Button, Card, Cell, PageHeader, SearchField, StatusPill, TableHead, TableRow } from "../components/ui";
import { count, day, relativeDay } from "../format";

const LIMIT = 50;
const QUERY = /^[0-9a-f-]{4,36}$/i;

type Segment = "all" | AccountStatus;

const PUSH_ICON: Record<Exclude<PushKind, "mixed">, { icon: typeof Smartphone; label: string }> = {
  apns: { icon: Smartphone, label: "APNs" },
  unifiedpush: { icon: Bot, label: "UnifiedPush" },
  web: { icon: Globe, label: "Web Push" },
  none: { icon: Monitor, label: "No push" },
};

export function PushIcons({ push }: { push: PushKind }) {
  if (push === "mixed") {
    return (
      <span className="push-icons" title="Mixed">
        <Layers size={15} aria-label="Mixed" />
      </span>
    );
  }
  const { icon: Icon, label } = PUSH_ICON[push];
  return (
    <span className="push-icons">
      <Icon size={15} aria-label={label} />
    </span>
  );
}

interface Load {
  state: "loading" | "ready" | "error";
  page: UsersPage | null;
  error: Error | null;
}

/** Frames "Users", "Users · Loading", "Users · No results", "Users · Couldn't load". */
export function Users() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const q = params.get("q") ?? "";
  const segment = (params.get("status") as Segment | null) ?? "all";
  const [typed, setTyped] = useState(q);
  const [cursors, setCursors] = useState<(string | null)[]>([null]);
  const [load, setLoad] = useState<Load>({ state: "loading", page: null, error: null });
  const [tick, setTick] = useState(0);

  const cursor = cursors[cursors.length - 1] ?? null;
  const pageIndex = cursors.length - 1;
  const queryOk = q === "" || QUERY.test(q);

  useEffect(() => {
    const handle = setTimeout(() => {
      if (typed === q) return;
      if (typed === "" || QUERY.test(typed)) {
        setCursors([null]);
        setParams((current) => {
          const next = new URLSearchParams(current);
          if (typed) next.set("q", typed);
          else next.delete("q");
          return next;
        });
      }
    }, 300);
    return () => clearTimeout(handle);
  }, [typed, q, setParams]);

  useEffect(() => {
    if (!queryOk) return;
    let cancelled = false;
    setLoad((current) => ({ ...current, state: "loading" }));
    const search = new URLSearchParams();
    if (q) search.set("q", q);
    if (segment !== "all") search.set("status", segment);
    if (cursor) search.set("cursor", cursor);
    search.set("limit", String(LIMIT));
    api<UsersPage>(`/users?${search}`)
      .then((page) => !cancelled && setLoad({ state: "ready", page, error: null }))
      .catch((error: unknown) => {
        if (cancelled) return;
        if (error instanceof ApiError && error.status === 401) {
          navigate("/sign-in", { replace: true, state: { from: "/users" } });
          return;
        }
        setLoad({ state: "error", page: null, error: error instanceof Error ? error : new Error(String(error)) });
      });
    return () => {
      cancelled = true;
    };
  }, [q, segment, cursor, queryOk, tick, navigate]);

  const totals = load.page?.totals ?? null;
  const items = load.page?.items ?? [];
  const totalForSegment = totals ? (segment === "all" ? totals.accounts : segment === "active" ? totals.active : totals.deleted) : null;
  const rangeStart = pageIndex * LIMIT + 1;
  const rangeEnd = pageIndex * LIMIT + items.length;

  const meta = useMemo(() => {
    if (load.state === "error") return "Account numbers unavailable";
    if (!totals) return "Loading…";
    return `${count(totals.accounts)} accounts · ${count(totals.active)} active · ${count(totals.deleted)} deleted`;
  }, [load.state, totals]);

  const choose = (next: Segment) => {
    setCursors([null]);
    setParams((current) => {
      const out = new URLSearchParams(current);
      if (next === "all") out.delete("status");
      else out.set("status", next);
      return out;
    });
  };

  const segments: { key: Segment; label: string; total: number | null }[] = [
    { key: "all", label: "All", total: totals?.accounts ?? null },
    { key: "active", label: "Active", total: totals?.active ?? null },
    { key: "deleted", label: "Deleted", total: totals?.deleted ?? null },
  ];

  return (
    <>
      <PageHeader title="Users" meta={meta} />

      <div className="toolbar">
        <div className="segments" role="tablist" aria-label="Account status">
          {segments.map(({ key, label, total }) => (
            <button
              key={key}
              type="button"
              role="tab"
              aria-selected={segment === key}
              className={segment === key ? "segment segment--active" : "segment"}
              onClick={() => choose(key)}
            >
              {label} {total === null ? "" : count(total)}
            </button>
          ))}
        </div>
        <span className="toolbar__spacer" />
        <div className="toolbar__search">
          <SearchField
            placeholder="Search by account ID"
            value={typed}
            onChange={(event) => setTyped(event.target.value.trim())}
            aria-invalid={typed !== "" && !QUERY.test(typed)}
          />
          {typed !== "" && !QUERY.test(typed) ? <div className="small text-tertiary">Type at least 4 characters of an account ID.</div> : null}
        </div>
      </div>

      <Card fill>
        <TableHead>
          <Cell>Account</Cell>
          <Cell width={130}>Created</Cell>
          <Cell width={90}>Devices</Cell>
          <Cell width={150}>Last active</Cell>
          <Cell width={150}>Push</Cell>
          <Cell width={120}>Status</Cell>
          <Cell width={44} />
        </TableHead>

        {load.state === "error" && load.error ? (
          <EmptyBlock
            icon={SearchX}
            title="Couldn't load accounts"
            body={
              load.error instanceof ApiError && load.error.code === "UPSTREAM"
                ? `${load.error.message} The list tries again when you ask it to.`
                : load.error.message
            }
          >
            <Button icon={RefreshCw} onClick={() => setTick((value) => value + 1)}>
              Try again
            </Button>
          </EmptyBlock>
        ) : null}

        {load.state === "ready" && items.length === 0 ? (
          <EmptyBlock
            icon={SearchX}
            title={q ? `No account matches “${q}”` : segment === "deleted" ? "No deleted accounts" : "No accounts yet"}
            body={q ? "Search looks at account IDs. Usernames aren't stored on this server, so they can't be searched." : "Accounts appear here as people register."}
          />
        ) : null}

        {items.map((row) => (
          <UserTableRow key={row.id} row={row} onOpen={() => navigate(`/users/${row.id}`)} />
        ))}

        <div className="table__spacer" />
        <div className="table-footer">
          <div className="legend">
            {(["apns", "unifiedpush", "web", "none"] as const).map((kind) => {
              const { icon: Icon, label } = PUSH_ICON[kind];
              return (
                <span className="legend__key" key={kind}>
                  <Icon size={13} aria-hidden="true" /> {label}
                </span>
              );
            })}
            <span className="legend__key">
              <Layers size={13} aria-hidden="true" /> Mixed
            </span>
          </div>
          <div className="pager">
            <span className="small text-secondary">
              {load.state === "loading" ? "Loading…" : load.state === "error" ? "—" : items.length === 0 ? "0 results" : `${count(rangeStart)}–${count(rangeEnd)}${totalForSegment === null ? "" : ` of ${count(totalForSegment)}`}`}
            </span>
            <button type="button" className="pager__button" aria-label="Previous page" disabled={pageIndex === 0} onClick={() => setCursors((stack) => stack.slice(0, -1))}>
              <ChevronLeft size={14} aria-hidden="true" />
            </button>
            <button
              type="button"
              className="pager__button"
              aria-label="Next page"
              disabled={!load.page?.next_cursor}
              onClick={() => load.page?.next_cursor && setCursors((stack) => [...stack, load.page?.next_cursor ?? null])}
            >
              <ChevronRight size={14} aria-hidden="true" />
            </button>
          </div>
        </div>
      </Card>
    </>
  );
}

function UserTableRow({ row, onOpen }: { row: UserRow; onOpen: () => void }) {
  const deleted = row.status === "deleted";
  return (
    <TableRow
      link
      role="link"
      tabIndex={0}
      onClick={onOpen}
      onKeyDown={(event) => {
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          onOpen();
        }
      }}
    >
      <Cell>
        <span className="avatar">{deleted ? <UserX aria-hidden="true" /> : <User aria-hidden="true" />}</span>
        <span className="name-block">
          <span className={deleted ? "mono text-secondary" : "mono text-primary"} style={{ fontWeight: 500 }}>
            {row.id.slice(0, 8)}
          </span>
          <span className="small text-tertiary">{deleted ? "Deleted account" : <span className="mono">{row.id.slice(0, 8)}…</span>}</span>
        </span>
      </Cell>
      <Cell width={130}>{day(row.created_on)}</Cell>
      <Cell width={90} className={deleted ? "mono text-tertiary" : "mono text-primary"}>
        {row.devices}
      </Cell>
      <Cell width={150}>{relativeDay(row.last_active_on)}</Cell>
      <Cell width={150}>{deleted ? <span className="text-tertiary">—</span> : <PushIcons push={row.push} />}</Cell>
      <Cell width={120}>{deleted ? <StatusPill tone="neutral">Deleted</StatusPill> : <StatusPill tone="ok">Active</StatusPill>}</Cell>
      <Cell width={44} right>
        <ChevronRight size={16} aria-hidden="true" className="text-tertiary" />
      </Cell>
    </TableRow>
  );
}

export function EmptyBlock({ icon: Icon, title, body, children }: { icon: typeof SearchX; title: string; body: string; children?: React.ReactNode }) {
  return (
    <div className="empty" role="status">
      <span className="empty__icon">
        <Icon size={20} aria-hidden="true" />
      </span>
      <div className="empty__title">{title}</div>
      <div className="empty__body">{body}</div>
      {children}
    </div>
  );
}
