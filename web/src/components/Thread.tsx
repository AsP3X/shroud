import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
  type FormEvent,
  type KeyboardEvent,
  type PointerEvent,
  type ReactNode,
} from "react";
import {
  ArrowDown,
  Check,
  CheckCheck,
  ChevronLeft,
  Clock,
  Image,
  Info,
  Mic,
  Paperclip,
  Plus,
  Search,
  Send,
  ShieldCheck,
  X,
} from "lucide-react";
import { clockTime, dayLabel, fullTimestamp, sameDay, MINUTE } from "../format";
import type { ChatMessage } from "../messaging";
import { Avatar } from "./Avatar";
import { VoiceBubble } from "./VoiceBubble";
import { VoiceLockedBar } from "./VoiceRecorderBar";
import { stopVoice } from "../voice/playback";
import {
  VOICE_LOCK_PX,
  cancelVoiceRecord,
  finishVoiceRecord,
  getVoiceRecorder,
  startVoiceRecord,
  subscribeVoiceRecorder,
  type VoiceTake,
} from "../voice/recorder";

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

/** Wraps every case-insensitive hit in <mark> so matches are findable by eye. */
function Highlight({ text, query }: { text: string; query: string }) {
  const needle = query.trim().toLowerCase();
  if (!needle) return <>{text}</>;
  const parts: ReactNode[] = [];
  const haystack = text.toLowerCase();
  let cursor = 0;
  for (;;) {
    const at = haystack.indexOf(needle, cursor);
    if (at === -1) {
      parts.push(text.slice(cursor));
      break;
    }
    if (at > cursor) parts.push(text.slice(cursor, at));
    parts.push(<mark key={at}>{text.slice(at, at + needle.length)}</mark>);
    cursor = at + needle.length;
  }
  return <>{parts}</>;
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
  onSendVoice,
  onBack,
  onShowInfo,
  onLoadVoice,
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
  onSendVoice: (take: VoiceTake) => void;
  onBack: () => void;
  onLoadVoice: (message: ChatMessage) => Promise<Uint8Array | null>;
  onShowInfo: () => void;
}) {
  const scroller = useRef<HTMLDivElement>(null);
  const field = useRef<HTMLTextAreaElement>(null);
  const composeRef = useRef<HTMLDivElement>(null);
  const micRef = useRef<HTMLButtonElement>(null);
  const recPhase = useRef<"idle" | "armed" | "locked">("idle");
  const dragOrigin = useRef<{ x: number; y: number } | null>(null);
  const holding = useRef(false);
  const [recUi, setRecUi] = useState<"idle" | "armed" | "locked">("idle");
  const [droplet, setDroplet] = useState<{ x: number; y: number; w: number; h: number } | null>(null);
  const [recHint, setRecHint] = useState<string | null>(null);
  const recSnap = useSyncExternalStore(subscribeVoiceRecorder, getVoiceRecorder);
  const [pinned, setPinned] = useState(true);
  const [unseen, setUnseen] = useState(0);
  const [searchOpen, setSearchOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const searchField = useRef<HTMLInputElement>(null);
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

  const finePointer = () => window.matchMedia?.("(pointer: fine)").matches ?? false;

  useLayoutEffect(() => {
    seenCount.current = 0;
    setUnseen(0);
    setSearchOpen(false);
    setSearchQuery("");
    toBottom("auto");
    // Only on pointer devices: focusing here would raise the on-screen keyboard
    // every time a chat is opened on a phone.
    if (finePointer()) field.current?.focus();
  }, [peer.id, toBottom]);

  useEffect(() => {
    recPhase.current = "idle";
    setRecUi("idle");
    setDroplet(null);
    cancelVoiceRecord();
  }, [peer.id]);

  useEffect(() => () => cancelVoiceRecord(), []);

  useLayoutEffect(() => {
    const added = messages.length - seenCount.current;
    seenCount.current = messages.length;
    if (added <= 0) return;
    const last = messages[messages.length - 1];
    if (pinned || last?.isMine) toBottom(messages.length === added ? "auto" : "smooth");
    else setUnseen((count) => count + added);
  }, [messages, pinned, toBottom]);

  useLayoutEffect(() => {
    if (searchOpen) searchField.current?.focus();
  }, [searchOpen]);

  const query = searchOpen ? searchQuery.trim() : "";
  const visible = useMemo(() => {
    if (!query) return messages;
    const needle = query.toLowerCase();
    return messages.filter((m) => !m.deleted && m.text.toLowerCase().includes(needle));
  }, [messages, query]);
  const rows = useMemo(() => buildRows(visible), [visible]);

  /* Filtering shrinks the thread; if we were pinned, stay on the latest match
     (and jump back to the real bottom when the query is cleared). */
  useLayoutEffect(() => {
    if (pinned) toBottom("auto");
  }, [query, pinned, toBottom]);

  /* Clicking Send moves focus to the button, so hand it back to the field —
     otherwise the composer goes cold after every message. */
  function send() {
    onSend();
    field.current?.focus();
  }

  function dropletTarget() {
    const bar = composeRef.current?.querySelector(".voice-locked")?.getBoundingClientRect();
    if (bar) {
      return { x: bar.left + bar.width / 2, y: bar.top + bar.height / 2, w: Math.max(160, bar.width - 24), h: bar.height };
    }
    const shell = composeRef.current?.getBoundingClientRect();
    const mic = micRef.current?.getBoundingClientRect();
    const x = shell ? shell.left + shell.width / 2 : (mic ? mic.left + mic.width / 2 : 0);
    const y = shell ? shell.top - 30 : (mic ? mic.top : 0);
    const w = shell ? Math.min(shell.width - 24, 420) : 280;
    return { x, y, w: Math.max(160, w), h: 44 };
  }

  function lockRecording() {
    if (recPhase.current === "locked") return;
    recPhase.current = "locked";
    const reduce = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
    setRecUi("locked");
    if (reduce) {
      setDroplet(null);
      return;
    }
    window.requestAnimationFrame(() => {
      setDroplet(dropletTarget());
      window.setTimeout(() => {
        if (recPhase.current === "locked") setDroplet(null);
      }, 380);
    });
  }

  async function beginRecording(from: DOMRect) {
    if (recPhase.current !== "idle") return;
    recPhase.current = "armed";
    setRecUi("armed");
    setRecHint(null);
    stopVoice();
    setDroplet({ x: from.left + from.width / 2, y: from.top + from.height / 2, w: 38, h: 38 });
    try {
      const started = await startVoiceRecord();
      if (!started) {
        if (recPhase.current === "armed") {
          recPhase.current = "idle";
          setRecUi("idle");
          setDroplet(null);
        }
        return;
      }
    } catch {
      recPhase.current = "idle";
      setRecUi("idle");
      setDroplet(null);
      holding.current = false;
      setRecHint("Microphone access is required for voice messages.");
      return;
    }
    if (recPhase.current !== "armed") return;
    if (!holding.current) lockRecording();
  }

  function onMicDown(event: PointerEvent<HTMLButtonElement>) {
    if (!canSend) return;
    event.preventDefault();
    event.currentTarget.setPointerCapture(event.pointerId);
    holding.current = true;
    dragOrigin.current = { x: event.clientX, y: event.clientY };
    void beginRecording(event.currentTarget.getBoundingClientRect());
  }

  function onMicMove(event: PointerEvent<HTMLButtonElement>) {
    if (!dragOrigin.current || recPhase.current === "idle") return;
    const dy = event.clientY - dragOrigin.current.y;
    const progress = Math.min(1, Math.max(0, -dy / VOICE_LOCK_PX));
    if (progress >= 1 && getVoiceRecorder().recording) {
      lockRecording();
      return;
    }
    if (recPhase.current !== "armed") return;
    const mic = micRef.current?.getBoundingClientRect();
    const target = dropletTarget();
    if (!mic) return;
    const x0 = mic.left + mic.width / 2;
    const y0 = mic.top + mic.height / 2;
    setDroplet({
      x: x0 + (target.x - x0) * progress,
      y: y0 + (target.y - y0) * progress,
      w: 38 + (target.w - 38) * progress,
      h: 38 + (target.h - 38) * progress * 0.4,
    });
  }

  function onMicUp() {
    holding.current = false;
    dragOrigin.current = null;
    if (recPhase.current === "armed" && getVoiceRecorder().recording) lockRecording();
  }

  function discardRecording() {
    recPhase.current = "idle";
    setRecUi("idle");
    setDroplet(null);
    cancelVoiceRecord();
  }

  async function sendRecording() {
    if (recPhase.current === "idle") return;
    recPhase.current = "idle";
    setRecUi("idle");
    setDroplet(null);
    try {
      const take = await finishVoiceRecord();
      if (!take) {
        setRecHint("That recording was too short.");
        return;
      }
      onSendVoice(take);
    } catch {
      cancelVoiceRecord();
      setRecHint("Could not finish the recording.");
    }
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

  function closeSearch() {
    setSearchOpen(false);
    setSearchQuery("");
    if (finePointer()) field.current?.focus();
  }

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
        <button
          className="icon-btn search-in-chat"
          type="button"
          aria-label="Search in chat"
          title="Search in chat"
          aria-pressed={searchOpen}
          onClick={() => {
            if (searchOpen) closeSearch();
            else setSearchOpen(true);
          }}
        >
          <Search size={18} />
        </button>
        <button className="icon-btn" type="button" aria-label="Contact info" title="Contact info" onClick={onShowInfo}>
          <Info size={18} />
        </button>
      </header>

      {searchOpen ? (
        <div className="thread-search">
          <Search size={15} aria-hidden="true" />
          <input
            ref={searchField}
            type="search"
            value={searchQuery}
            aria-label="Search in this conversation"
            placeholder={`Search in ${peer.username}`}
            autoComplete="off"
            autoCorrect="off"
            enterKeyHint="search"
            onChange={(event) => setSearchQuery(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Escape") closeSearch();
            }}
          />
          {query ? (
            <span className="thread-search-count" aria-live="polite">
              {visible.length === 0
                ? "No matches"
                : `${visible.length} message${visible.length > 1 ? "s" : ""}`}
            </span>
          ) : null}
          <button className="icon-btn" type="button" aria-label="Close search" onClick={closeSearch}>
            <X size={16} />
          </button>
        </div>
      ) : null}

      <div className="messages" ref={scroller} onScroll={onScroll}>
        <div className="messages-inner">
          {loading && messages.length === 0 ? (
            <p className="thread-note">Decrypting history…</p>
          ) : error && messages.length === 0 ? (
            <p className="thread-note thread-note-error">{error}</p>
          ) : query && visible.length === 0 ? (
            <p className="thread-note">No messages match “{query}”.</p>
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
                    row.message.kind === "voice" && !row.message.deleted ? "voice-msg" : "",
                  ]
                    .filter(Boolean)
                    .join(" ")}
                >
                  {row.message.kind === "voice" && !row.message.deleted ? (
                    <VoiceBubble message={row.message} loadVoice={onLoadVoice} />
                  ) : (
                    <>
                      <p className="bubble-text">
                        <Highlight text={row.message.text} query={query} />
                      </p>
                      <span className="bubble-meta" title={fullTimestamp(row.message.createdAt)}>
                        <time dateTime={row.message.createdAt}>{clockTime(row.message.createdAt)}</time>
                        {row.message.isMine && !row.message.deleted ? (
                          <Receipt message={row.message} />
                        ) : null}
                      </span>
                    </>
                  )}
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

      {recHint ? (
        <p className="thread-banner" role="status">
          {recHint}
        </p>
      ) : null}

      <div className={`compose-shell${recUi !== "idle" ? " recording" : ""}`} ref={composeRef}>
        {recUi !== "idle" ? (
          <VoiceLockedBar
            elapsed={recSnap.elapsed}
            levels={recSnap.liveLevels}
            onDiscard={discardRecording}
            onSend={() => void sendRecording()}
            sending={sending}
          />
        ) : null}
          <form
            className={draft.trim() ? "compose has-draft" : "compose"}
            onSubmit={submit}
            aria-busy={sending}
          >
            <button
              className="icon-btn compose-plus"
              type="button"
              aria-label="Attach"
              title="Media is coming to the web client soon"
              disabled
            >
              <Plus size={20} />
            </button>
            <button
              className="icon-btn compose-wide"
              type="button"
              aria-label="Attach a file"
              title="Media is coming to the web client soon"
              disabled
            >
              <Paperclip size={18} />
            </button>
            <button
              className="icon-btn compose-wide"
              type="button"
              aria-label="Send a photo"
              title="Media is coming to the web client soon"
              disabled
            >
              <Image size={18} />
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
            <button
              ref={micRef}
              className={`icon-btn compose-mic${recUi !== "idle" ? " recording" : ""}`}
              type="button"
              aria-label="Record a voice message"
              title="Click or drag up to record"
              disabled={!canSend || recUi === "locked"}
              onPointerDown={onMicDown}
              onPointerMove={onMicMove}
              onPointerUp={onMicUp}
              onPointerCancel={onMicUp}
            >
              <Mic size={18} />
            </button>
            <button className="send" type="submit" aria-label="Send" disabled={!canSend || !draft.trim()}>
              <Send size={16} />
            </button>
          </form>
        {droplet ? (
          <div
            className="voice-droplet"
            style={{
              left: droplet.x,
              top: droplet.y,
              width: droplet.w,
              height: droplet.h,
            }}
            aria-hidden="true"
          />
        ) : null}
      </div>
    </section>
  );
}
