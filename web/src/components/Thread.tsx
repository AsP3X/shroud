import {
  useCallback,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
  type KeyboardEvent,
} from "react";
import { ArrowDown, Check, CheckCheck, ChevronLeft, Clock, Info, Paperclip, Send, ShieldCheck } from "lucide-react";
import { clockTime, dayLabel, fullTimestamp, sameDay, MINUTE } from "../format";
import type { ChatMessage } from "../messaging";
import { Avatar } from "./Avatar";

/** Messages from the same sender inside this window render as one visual block. */
const GROUP_WINDOW = 5 * MINUTE;
/** How far off the bottom the reader can be before new messages stop auto-scrolling. */
const PIN_SLACK = 120;

type Row =
  | { kind: "day"; key: string; label: string }
  | { kind: "message"; key: string; message: ChatMessage; first: boolean; last: boolean };

function buildRows(messages: ChatMessage[]): Row[] {
  const rows: Row[] = [];
  for (let i = 0; i < messages.length; i++) {
    const message = messages[i];
    const prev = messages[i - 1];
    const next = messages[i + 1];
    const opensDay = !prev || !sameDay(prev.createdAt, message.createdAt);
    if (opensDay) {
      rows.push({ kind: "day", key: `day-${message.id}`, label: dayLabel(message.createdAt) });
    }
    const gapBefore = prev ? Date.parse(message.createdAt) - Date.parse(prev.createdAt) : Infinity;
    const gapAfter = next ? Date.parse(next.createdAt) - Date.parse(message.createdAt) : Infinity;
    rows.push({
      kind: "message",
      key: message.id,
      message,
      first: opensDay || !prev || prev.isMine !== message.isMine || gapBefore > GROUP_WINDOW,
      last:
        !next ||
        next.isMine !== message.isMine ||
        !sameDay(message.createdAt, next.createdAt) ||
        gapAfter > GROUP_WINDOW,
    });
  }
  return rows;
}

function Receipt({ message }: { message: ChatMessage }) {
  if (message.pending) return <Clock size={13} aria-label="Sending" />;
  if (message.read) return <CheckCheck size={14} className="receipt-read" aria-label="Read" />;
  if (message.delivered) return <CheckCheck size={14} aria-label="Delivered" />;
  return <Check size={14} aria-label="Sent" />;
}

export function Thread({
  peer,
  presence,
  online,
  messages,
  loading,
  error,
  canSend,
  sending,
  draft,
  onDraftChange,
  onSend,
  onBack,
  onShowInfo,
}: {
  peer: { id: string; username: string };
  presence: string;
  online: boolean;
  messages: ChatMessage[];
  loading: boolean;
  error: string | null;
  canSend: boolean;
  sending: boolean;
  draft: string;
  onDraftChange: (value: string) => void;
  onSend: () => void;
  onBack: () => void;
  onShowInfo: () => void;
}) {
  const scroller = useRef<HTMLDivElement>(null);
  const field = useRef<HTMLTextAreaElement>(null);
  const [pinned, setPinned] = useState(true);
  const [unseen, setUnseen] = useState(0);
  const seenCount = useRef(0);

  const toBottom = useCallback((behavior: ScrollBehavior = "auto") => {
    const node = scroller.current;
    if (!node) return;
    node.scrollTo({ top: node.scrollHeight, behavior });
    setPinned(true);
    setUnseen(0);
  }, []);

  const onScroll = useCallback(() => {
    const node = scroller.current;
    if (!node) return;
    const distance = node.scrollHeight - node.scrollTop - node.clientHeight;
    const atBottom = distance <= PIN_SLACK;
    setPinned(atBottom);
    if (atBottom) setUnseen(0);
  }, []);

  useLayoutEffect(() => {
    seenCount.current = 0;
    setUnseen(0);
    toBottom("auto");
    // Only on pointer devices: focusing here would raise the on-screen keyboard
    // every time a chat is opened on a phone.
    if (window.matchMedia?.("(pointer: fine)").matches) field.current?.focus();
  }, [peer.id, toBottom]);

  useLayoutEffect(() => {
    const added = messages.length - seenCount.current;
    seenCount.current = messages.length;
    if (added <= 0) return;
    const last = messages[messages.length - 1];
    if (pinned || last?.isMine) toBottom(messages.length === added ? "auto" : "smooth");
    else setUnseen((count) => count + added);
  }, [messages, pinned, toBottom]);

  /* Clicking Send moves focus to the button, so hand it back to the field —
     otherwise the composer goes cold after every message. */
  function send() {
    onSend();
    field.current?.focus();
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    send();
  }

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key !== "Enter" || event.shiftKey || event.nativeEvent.isComposing) return;
    event.preventDefault();
    send();
  }

  const rows = useMemo(() => buildRows(messages), [messages]);

  return (
    <section className="thread">
      <header className="thread-head">
        <button className="icon-btn back-mobile" type="button" aria-label="Back to chats" onClick={onBack}>
          <ChevronLeft size={20} />
        </button>
        <Avatar name={peer.username} seed={peer.id} online={online} />
        <div className="thread-peer">
          <strong>{peer.username}</strong>
          <span className={online ? "online" : undefined}>{presence || " "}</span>
        </div>
        <span className="e2e-badge" title="End-to-end encrypted">
          <ShieldCheck size={13} aria-hidden="true" />
          Encrypted
        </span>
        <button className="icon-btn" aria-label="Contact info" title="Contact info" onClick={onShowInfo}>
          <Info size={18} />
        </button>
      </header>

      <div className="messages" ref={scroller} onScroll={onScroll}>
        <div className="messages-inner">
          {loading && messages.length === 0 ? (
            <p className="thread-note">Decrypting history…</p>
          ) : error && messages.length === 0 ? (
            <p className="thread-note thread-note-error">{error}</p>
          ) : messages.length === 0 ? (
            <div className="thread-empty">
              <Avatar name={peer.username} seed={peer.id} size="lg" />
              <strong>{peer.username}</strong>
              <p>
                Messages here are end-to-end encrypted. Not even the Shroud server can read them.
              </p>
            </div>
          ) : (
            rows.map((row) =>
              row.kind === "day" ? (
                <div className="day-sep" key={row.key}>
                  <span>{row.label}</span>
                </div>
              ) : (
                <div
                  key={row.key}
                  className={[
                    "bubble",
                    row.message.isMine ? "out" : "in",
                    row.first ? "first" : "",
                    row.last ? "last" : "",
                    row.message.deleted ? "deleted" : "",
                    row.message.failed ? "failed" : "",
                    row.message.pending ? "pending" : "",
                  ]
                    .filter(Boolean)
                    .join(" ")}
                >
                  <p className="bubble-text">{row.message.text}</p>
                  <span className="bubble-meta" title={fullTimestamp(row.message.createdAt)}>
                    <time dateTime={row.message.createdAt}>{clockTime(row.message.createdAt)}</time>
                    {row.message.isMine && !row.message.deleted ? (
                      <Receipt message={row.message} />
                    ) : null}
                  </span>
                </div>
              ),
            )
          )}
        </div>
      </div>

      {error && messages.length > 0 ? (
        <p className="thread-banner" role="status">
          {error}
        </p>
      ) : null}

      {!pinned && messages.length > 0 ? (
        <button className="jump-latest" type="button" onClick={() => toBottom("smooth")}>
          <ArrowDown size={15} aria-hidden="true" />
          {unseen > 0 ? `${unseen} new message${unseen > 1 ? "s" : ""}` : "Latest"}
        </button>
      ) : null}

      <form className="compose" onSubmit={submit} aria-busy={sending}>
        <button
          className="icon-btn"
          type="button"
          aria-label="Attach a file"
          title="Media is coming to the web client soon"
          disabled
        >
          <Paperclip size={18} />
        </button>
        <div className="compose-grow" data-value={`${draft} `}>
          <textarea
            ref={field}
            className="compose-field"
            rows={1}
            placeholder={canSend ? "Message" : "Unlock your keys to send"}
            aria-label="Message"
            value={draft}
            onChange={(event) => onDraftChange(event.target.value)}
            onKeyDown={onKeyDown}
            enterKeyHint="send"
            disabled={!canSend}
          />
        </div>
        <button className="send" type="submit" aria-label="Send" disabled={!canSend || !draft.trim()}>
          <Send size={16} />
        </button>
      </form>
    </section>
  );
}
