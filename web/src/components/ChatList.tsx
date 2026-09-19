import { QrCode, Search, SquarePen, X } from "lucide-react";
import { Avatar } from "./Avatar";
import { TypingLabel } from "./Typing";

export type ListEntry = {
  id: string;
  username: string;
  subtitle: string;
  timestamp?: string;
  online: boolean;
  /** "typing" replaces the preview, as on iOS. */
  typing?: boolean;
};

export type RequestEntry = { id: string; username: string };

export function ChatList({
  title,
  query,
  onQueryChange,
  onAdd,
  addLabel,
  onShowQr,
  loading,
  error,
  requests,
  onRespond,
  entries,
  selectedId,
  onSelect,
  empty,
  className,
}: {
  title: string;
  query: string;
  onQueryChange: (value: string) => void;
  onAdd: () => void;
  addLabel: string;
  /** Shown on Contacts, where iOS puts its QR button too. */
  onShowQr?: () => void;
  loading: boolean;
  error: string | null;
  requests: RequestEntry[];
  onRespond: (id: string, accept: boolean) => void;
  entries: ListEntry[];
  selectedId: string | null;
  onSelect: (entry: ListEntry) => void;
  empty: { title: string; body: string };
  className?: string;
}) {
  const searching = query.trim().length > 0;
  return (
    <section className={className ? `pane ${className}` : "pane"} aria-label={title}>
      <header className="pane-head">
        <div className="pane-title-row">
          <h1>{title}</h1>
          <div className="pane-actions">
            {onShowQr ? (
              <button
                type="button"
                className="icon-btn accent"
                aria-label="Show my QR code"
                title="Show my QR code"
                onClick={onShowQr}
              >
                <QrCode size={18} />
              </button>
            ) : null}
            <button type="button" className="icon-btn" aria-label={addLabel} title={addLabel} onClick={onAdd}>
              <SquarePen size={18} />
            </button>
          </div>
        </div>
        <div className="search">
          <Search size={15} aria-hidden="true" />
          <input
            value={query}
            onChange={(event) => onQueryChange(event.target.value)}
            placeholder={`Search ${title.toLowerCase()}`}
            aria-label={`Search ${title.toLowerCase()}`}
            type="search"
          />
          {searching ? (
            <button className="search-clear" aria-label="Clear search" onClick={() => onQueryChange("")}>
              <X size={14} />
            </button>
          ) : null}
        </div>
      </header>

      <div className="rows">
        {error ? (
          <p className="pane-error" role="status">
            {error}
          </p>
        ) : null}

        {requests.length > 0 && !searching ? (
          <>
            <h2 className="list-label">Requests</h2>
            {requests.map((request) => (
              <div key={request.id} className="row row-static">
                <Avatar name={request.username} seed={request.id} />
                <div className="row-copy">
                  <strong>{request.username}</strong>
                  <span>Wants to connect</span>
                </div>
                <div className="row-actions">
                  <button type="button" className="mini-btn" onClick={() => onRespond(request.id, true)}>
                    Accept
                  </button>
                  <button type="button" className="mini-btn ghost" onClick={() => onRespond(request.id, false)}>
                    Ignore
                  </button>
                </div>
              </div>
            ))}
            <h2 className="list-label">{title}</h2>
          </>
        ) : null}

        {loading && entries.length === 0 && !error ? (
          <ul className="skeletons" aria-hidden="true">
            {[0, 1, 2, 3, 4].map((n) => (
              <li key={n} className="row skeleton-row">
                <span className="skeleton skeleton-avatar" />
                <span className="row-copy">
                  <span className="skeleton skeleton-line" style={{ width: `${45 + n * 7}%` }} />
                  <span className="skeleton skeleton-line short" style={{ width: `${60 - n * 5}%` }} />
                </span>
              </li>
            ))}
          </ul>
        ) : null}

        {!loading && entries.length === 0 && requests.length === 0 && !error ? (
          <div className="rows-empty">
            <strong>{searching ? "Nothing found" : empty.title}</strong>
            <p>{searching ? `No match for “${query.trim()}”.` : empty.body}</p>
          </div>
        ) : null}

        {entries.map((entry) => {
          const active = selectedId?.toLowerCase() === entry.id.toLowerCase();
          return (
            <button
              type="button"
              key={entry.id}
              className={active ? "row row-button active" : "row row-button"}
              aria-current={active ? "true" : undefined}
              onClick={() => onSelect(entry)}
            >
              <Avatar name={entry.username} seed={entry.id} online={entry.online} />
              <span className="row-copy">
                <strong>{entry.username}</strong>
                {entry.typing ? <TypingLabel /> : <span>{entry.subtitle}</span>}
              </span>
              {entry.timestamp ? <time className="row-time">{entry.timestamp}</time> : null}
            </button>
          );
        })}
      </div>
    </section>
  );
}
