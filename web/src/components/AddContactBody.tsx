import { useEffect, useRef, useState } from "react";
import { QrCode, Search, UserRound } from "lucide-react";
import { api, ApiError, type Session, type UserCard } from "../api/client";
import { parseInvite, type Invite } from "../invite";
import { Avatar } from "./Avatar";

type Lookup =
  | { state: "idle" }
  | { state: "loading" }
  | { state: "found"; user: UserCard; code: string; known: string | null }
  | { state: "self" }
  | { state: "none"; message: string };

/**
 * Add contact: the field understands a share code or an invite link, looks it up as you type
 * and shows who you are about to ask before anything is sent. The server keeps usernames
 * private until two people are contacts, so a stranger shows as their share code; someone
 * already in your contacts shows by name and the button opens the chat instead.
 */
export function AddContactBody({
  session,
  contactNames,
  onSent,
  onOpenChat,
  onShowQr,
}: {
  session: Session;
  /** Lower-cased user id → username, for everyone already in your contacts. */
  contactNames: ReadonlyMap<string, string>;
  onSent: () => void | Promise<void>;
  onOpenChat: (userId: string, username: string) => void;
  onShowQr: () => void;
}) {
  const [value, setValue] = useState("");
  const [lookup, setLookup] = useState<Lookup>({ state: "idle" });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const timer = useRef(0);
  const seq = useRef(0);
  useEffect(() => () => window.clearTimeout(timer.current), []);

  function onChange(raw: string) {
    setValue(raw);
    setError(null);
    window.clearTimeout(timer.current);
    const id = ++seq.current;
    const parsed: Invite | null = parseInvite(raw);
    if (!parsed) {
      setLookup(raw.trim() ? { state: "none", message: "That isn’t a share code or a shroud.corespace.de/u/… link." } : { state: "idle" });
      return;
    }
    if (parsed.kind === "username") {
      setLookup({ state: "none", message: "Names can’t be looked up: ask them for their share code or invite link, or scan their QR code with the phone app." });
      return;
    }
    setLookup({ state: "loading" });
    timer.current = window.setTimeout(async () => {
      try {
        const user = await api.lookupUser(session.token, parsed);
        if (id !== seq.current) return;
        if (user.id.toLowerCase() === session.user.id.toLowerCase()) setLookup({ state: "self" });
        else setLookup({ state: "found", user, code: parsed.value, known: contactNames.get(user.id.toLowerCase()) ?? null });
      } catch (err) {
        if (id !== seq.current) return;
        const message =
          err instanceof ApiError && err.status === 404
            ? `No one on this server has the share code ${parsed.value}.`
            : err instanceof ApiError
              ? err.message
              : "Could not look that up. Check your connection and try again.";
        setLookup({ state: "none", message });
      }
    }, 350);
  }

  async function send() {
    if (lookup.state !== "found" || busy) return;
    if (lookup.known) {
      onOpenChat(lookup.user.id, lookup.known);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await api.createContactRequest(session.token, lookup.user.id);
      await onSent();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not send request.");
    } finally {
      setBusy(false);
    }
  }

  let result;
  if (lookup.state === "idle") {
    result = <p className="lookup-hint">Ask them for their share code or invite link, or scan their QR code with the phone app. They get the request on their devices.</p>;
  } else if (lookup.state === "loading") {
    result = (
      <div className="lookup-row is-loading" aria-label="Looking up">
        <span className="avatar avatar-md skeleton" />
        <span className="lookup-lines">
          <i className="skeleton" style={{ width: 140 }} />
          <i className="skeleton" style={{ width: 84 }} />
        </span>
      </div>
    );
  } else if (lookup.state === "self") {
    result = (
      <div className="lookup-row">
        <Avatar name={session.user.username} seed={session.user.id} />
        <span className="lookup-lines">
          <strong>{session.user.username}</strong>
          <small>@{session.user.username}</small>
        </span>
        <span className="lookup-status">That’s you</span>
      </div>
    );
  } else if (lookup.state === "found") {
    result = lookup.known ? (
      <div className="lookup-row">
        <Avatar name={lookup.known} seed={lookup.user.id} />
        <span className="lookup-lines">
          <strong>{lookup.known}</strong>
          <small>@{lookup.known}</small>
        </span>
        <span className="lookup-status">In your contacts</span>
      </div>
    ) : (
      <div className="lookup-row">
        <span className="avatar avatar-md lookup-stranger" aria-hidden="true">
          <UserRound size={20} />
        </span>
        <span className="lookup-lines">
          <strong>Share code {lookup.code}</strong>
          <small>Their name shows once they accept</small>
        </span>
        <span className="lookup-status">Not in your contacts</span>
      </div>
    );
  } else {
    result = <p className="lookup-none">{lookup.message}</p>;
  }

  return (
    <form
      className="lookup-form"
      onSubmit={(event) => {
        event.preventDefault();
        void send();
      }}
    >
      <label className="lookup">
        <Search size={18} aria-hidden="true" />
        <input
          value={value}
          onChange={(event) => onChange(event.target.value)}
          placeholder="Share code or invite link"
          aria-label="Share code or invite link"
          autoComplete="off"
          autoCapitalize="off"
          spellCheck={false}
          data-autofocus
        />
      </label>
      <div className="lookup-result" aria-live="polite">
        {result}
      </div>
      {error ? <p className="err">{error}</p> : null}
      <div className="dialog-foot">
        <button type="button" className="btn-text" onClick={onShowQr}>
          <QrCode size={16} aria-hidden="true" />
          Show my QR code
        </button>
        <span className="dialog-foot-gap" />
        <button className="btn btn-primary dialog-primary" type="submit" disabled={lookup.state !== "found" || busy}>
          {busy ? "Sending…" : lookup.state === "found" && lookup.known ? "Open chat" : "Send request"}
        </button>
      </div>
    </form>
  );
}
