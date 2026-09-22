import {
  lazy,
  Suspense,
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type ClipboardEvent,
  type DragEvent,
  type FormEvent,
  type KeyboardEvent,
} from "react";
import {
  ArrowDown,
  ChevronLeft,
  Image,
  ImagePlus,
  Info,
  Mic,
  Paperclip,
  Plus,
  Reply,
  Search,
  Send,
  ShieldCheck,
  X,
} from "lucide-react";
import { clockTime, dayLabel, fullTimestamp, sameDay, MINUTE } from "../format";
import type { ChatMessage } from "../messaging";
import { peekImage, type LoadedImage } from "../media/images";
import {
  clipboardImages,
  imageFiles,
  MAX_PHOTOS_PER_SEND,
  PHOTO_ACCEPT,
  type PreparedImage,
} from "../media/prepareImage";
import {
  clipboardVideos,
  VIDEO_ACCEPT,
  videoFiles,
  type VideoSendDraft,
} from "../media/prepareVideo";
import { cancelVideoDownload, type LoadedVideo } from "../media/videos";
import { Avatar } from "./Avatar";
import { Highlight } from "./Highlight";
import { ImageBubble } from "./ImageBubble";
import {
  DeleteMessageDialog,
  MessageMenu,
  suppressClickAfterLongPress,
  type MessageMenuAction,
  type MessageMenuAnchor,
} from "./MessageMenu";
import { quoteOf, ReplyQuote, resolveQuote } from "./ReplyQuote";
import { useSwipeToReply } from "./useSwipeToReply";
import { Receipt } from "./Receipt";
import { TypingBubble, TypingLabel } from "./Typing";
import { VideoBubble } from "./VideoBubble";
import { VoiceBubble } from "./VoiceBubble";
import { VoiceDroplet, VoiceStrip } from "./VoiceRecorderBar";
import { useVoiceRecording } from "./useVoiceRecording";
import type { PeerActivity } from "../typing";
import type { VoiceTake } from "../voice/recorder";
import { clearTranscriptChoice, transcriptTail } from "../voice/transcriptView";

/* Only needed once a photo or video is opened or picked: kept out of the first download. */
const ImageComposer = lazy(() => import("./ImageComposer").then((m) => ({ default: m.ImageComposer })));
const ImageViewer = lazy(() => import("./ImageViewer").then((m) => ({ default: m.ImageViewer })));
const VideoComposer = lazy(() => import("./VideoComposer").then((m) => ({ default: m.VideoComposer })));
const VideoViewer = lazy(() => import("./VideoViewer").then((m) => ({ default: m.VideoViewer })));

const MEDIA_ACCEPT = `${PHOTO_ACCEPT},${VIDEO_ACCEPT}`;

/** Messages from the same sender inside this window render as one visual block. */
const GROUP_WINDOW = 5 * MINUTE;
/** How far off the bottom the reader can be before new messages stop auto-scrolling. */
const PIN_SLACK = 120;
/** The typing bubble's exit animation (index.css `typing-out`). */
const TYPING_OUT_MS = 180;

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

/**
 * One message row: the bubble, its reply header, the hover reply button and the swipe
 * gesture. A component of its own because the gesture needs hooks per row, and because a
 * drag then re-renders only this row.
 */
function MessageRow({
  row,
  query,
  inTail,
  peerName,
  myId,
  quoted,
  flashing,
  onReply,
  onMenu,
  onJump,
  onOpenPhoto,
  onOpenVideo,
  onLoadImage,
  onLoadVideo,
  onLoadVoice,
}: {
  row: Extract<Row, { kind: "message" }>;
  query: string;
  inTail: boolean;
  peerName: string;
  myId: string;
  quoted: Map<string, ChatMessage>;
  flashing: boolean;
  onReply: (message: ChatMessage) => void;
  /** Right-click, Shift+F10 / the Menu key, or a touch held still. `settle` is a held finger. */
  onMenu: (
    message: ChatMessage,
    anchor: MessageMenuAnchor,
    row: HTMLElement | null,
    settle?: boolean,
  ) => void;
  onJump: (id: string) => void;
  onOpenPhoto: (message: ChatMessage) => void;
  onOpenVideo: (message: ChatMessage) => void;
  onLoadImage: (message: ChatMessage) => Promise<LoadedImage | null>;
  onLoadVideo: (message: ChatMessage) => Promise<LoadedVideo | null>;
  onLoadVoice: (message: ChatMessage) => Promise<Uint8Array | null>;
}) {
  const { message } = row;
  const canReply = canQuote(message);
  const swipe = useSwipeToReply({
    enabled: canReply,
    isMine: message.isMine,
    onReply: () => onReply(message),
    onLongPress: (x, y) => {
      suppressClickAfterLongPress();
      onMenu(message, { x, y }, null, true);
    },
  });

  const voice = message.kind === "voice" && !message.deleted;
  const photo = isPhoto(message);
  const video = isVideo(message);
  const bubbleClass = [
    "bubble",
    message.isMine ? "out" : "in",
    row.first ? "first" : "",
    row.last ? "last" : "",
    message.deleted ? "deleted" : "",
    message.failed ? "failed" : "",
    message.pending ? "pending" : "",
    voice ? "voice-msg" : "",
    message.replyTo ? "has-reply" : "",
  ]
    .filter(Boolean)
    .join(" ");

  const quote = message.replyTo && !message.deleted ? (
    <ReplyQuote
      quote={resolveQuote(
        message.replyTo,
        quoted.get(message.replyTo.id.toLowerCase()),
        peerName,
        myId,
      )}
      onClick={() => onJump(message.replyTo!.id.toLowerCase())}
    />
  ) : null;

  let bubble = null;
  if (photo) {
    bubble = (
      <ImageBubble
        className={bubbleClass}
        message={message}
        query={query}
        loadImage={onLoadImage}
        onOpen={onOpenPhoto}
        quote={quote}
      />
    );
  } else if (video) {
    bubble = (
      <VideoBubble
        className={bubbleClass}
        message={message}
        query={query}
        onOpen={onOpenVideo}
        onDownload={(opened) => void onLoadVideo(opened)}
        onCancelDownload={cancelVideoDownload}
        quote={quote}
      />
    );
  } else if (voice) {
    /* The transcript folds away inside the bubble, behind the →A button. */
    bubble = (
      <div className={bubbleClass}>
        <VoiceBubble message={message} loadVoice={onLoadVoice} query={query} inTail={inTail} quote={quote} />
      </div>
    );
  } else {
    bubble = (
      <div className={bubbleClass}>
        {quote}
        <div className="bubble-body">
          <p className="bubble-text">
            <Highlight text={message.text} query={query} />
          </p>
          <span className="bubble-meta" title={fullTimestamp(message.createdAt)}>
            <time dateTime={message.createdAt}>{clockTime(message.createdAt)}</time>
            {message.isMine && !message.deleted ? <Receipt message={message} /> : null}
          </span>
        </div>
      </div>
    );
  }

  return (
    <div
      ref={swipe.rowRef}
      className={`msg-row${message.isMine ? " mine" : ""}${flashing ? " is-flashing" : ""}`}
      data-message-id={message.id.toLowerCase()}
      {...swipe.handlers}
      tabIndex={canReply ? undefined : 0}
      onContextMenu={(event) => {
        swipe.cancelLongPress();
        // Android fires contextmenu while the finger is still down. Keep the menu
        // inert until that finger lifts, or the release taps the item it opened on.
        const holding = swipe.isHolding();
        if (holding) suppressClickAfterLongPress();
        event.preventDefault();
        onMenu(message, { x: event.clientX, y: event.clientY }, event.currentTarget, holding);
      }}
      onKeyDown={(event) => {
        // The keyboard's own way into a context menu, from any control inside the row.
        if (event.key !== "ContextMenu" && !(event.shiftKey && event.key === "F10")) return;
        event.preventDefault();
        const bubbleNode = event.currentTarget.querySelector(".bubble") ?? event.currentTarget;
        const rect = bubbleNode.getBoundingClientRect();
        onMenu(message, { x: rect.left + 12, y: rect.bottom - 4 }, event.currentTarget);
      }}
    >
      {bubble}
      {canReply ? (
        <button
          className="bubble-reply-btn"
          type="button"
          aria-label="Reply to this message"
          title="Reply"
          onClick={() => onReply(message)}
        >
          <Reply size={15} aria-hidden="true" />
        </button>
      ) : null}
      {swipe.swiping ? (
        <span className="swipe-reply" aria-hidden="true">
          <Reply size={16} />
        </span>
      ) : null}
    </div>
  );
}

/**
 * Copies text, falling back to the legacy selection copy where the async clipboard is missing
 * or refused — a plain-http origin (the client served over a LAN address) has no
 * `navigator.clipboard` at all. Both paths only work inside the click that asked for them.
 */
function copyWithCommand(text: string): boolean {
  const area = document.createElement("textarea");
  area.value = text;
  area.setAttribute("readonly", "");
  area.style.position = "fixed";
  area.style.top = "0";
  area.style.left = "0";
  area.style.opacity = "0";
  document.body.appendChild(area);
  area.focus();
  area.select();
  let copied = false;
  try {
    copied = document.execCommand("copy");
  } catch {
    copied = false;
  }
  area.remove();
  return copied;
}

async function writeClipboard(text: string): Promise<boolean> {
  // A plain-http origin (the client on a LAN address) has no async clipboard.
  // The fallback has to run inside the click, before any await.
  const clipboard = navigator.clipboard;
  if (!clipboard?.writeText || !window.isSecureContext) return copyWithCommand(text);
  try {
    await clipboard.writeText(text);
    return true;
  } catch {
    return copyWithCommand(text);
  }
}

/** Only a message that reached the server can be quoted — the peer could never resolve the rest. */
function canQuote(message: ChatMessage): boolean {
  return !message.deleted && !message.pending && !message.failed && !message.id.startsWith("pending:");
}

/** What "Copy text" copies: the words of a message, never a stand-in such as "Photo". */
function copyableText(message: ChatMessage): string {
  if (message.deleted || (message.failed && !message.isMine)) return "";
  switch (message.kind) {
    case "text":
      return message.text;
    case "image":
    case "video":
      return message.caption?.trim() ?? "";
    case "voice":
      return message.transcript?.trim() ?? "";
    default:
      return "";
  }
}

/** A photo we can draw: sealed with a key, or one this tab is sending right now. */
function isPhoto(message: ChatMessage): boolean {
  if (message.kind !== "image" || message.deleted) return false;
  return Boolean(message.mediaKey) || Boolean(peekImage(message.id));
}

function isVideo(message: ChatMessage): boolean {
  return message.kind === "video" && !message.deleted;
}

function hasFiles(event: DragEvent): boolean {
  return Array.from(event.dataTransfer?.types ?? []).includes("Files");
}

export function Thread({
  peer,
  presence,
  online,
  activity = null,
  messages,
  loading,
  error,
  canSend,
  sending,
  draft,
  onDraftChange,
  onSend,
  onSendVoice,
  onRecordingChange,
  onSendImages,
  onSendVideos,
  onBack,
  onShowInfo,
  onLoadVoice,
  onLoadImage,
  onLoadVideo,
  replyTo,
  onReply,
  onCancelReply,
  onDelete,
  myId,
}: {
  peer: { id: string; username: string };
  presence: string;
  online: boolean;
  /** The peer is typing or recording a voice note to us right now. */
  activity?: PeerActivity | null;
  messages: ChatMessage[];
  loading: boolean;
  error: string | null;
  canSend: boolean;
  sending: boolean;
  draft: string;
  onDraftChange: (value: string) => void;
  onSend: () => void;
  onSendVoice: (take: VoiceTake) => void;
  /** True while this tab is capturing a voice note for `peer`. */
  onRecordingChange?: (recording: boolean) => void;
  /** Photos prepared in the send sheet; the caption belongs to the first. */
  onSendImages: (images: PreparedImage[], caption: string) => void;
  /** Clips from the send sheet; encoding starts after the bubbles land. */
  onSendVideos: (drafts: VideoSendDraft[], caption: string) => void;
  onBack: () => void;
  onLoadVoice: (message: ChatMessage) => Promise<Uint8Array | null>;
  onLoadImage: (message: ChatMessage) => Promise<LoadedImage | null>;
  onLoadVideo: (message: ChatMessage) => Promise<LoadedVideo | null>;
  onShowInfo: () => void;
  /** Message being answered; its quote sits above the composer until it is sent. */
  replyTo: ChatMessage | null;
  onReply: (message: ChatMessage) => void;
  onCancelReply: () => void;
  /** `everyone` is only offered for our own messages that reached the server. */
  onDelete: (message: ChatMessage, scope: "me" | "everyone") => void;
  /** Signed-in account, to tell "You" from the peer in a quote. */
  myId: string;
}) {
  const scroller = useRef<HTMLDivElement>(null);
  const foot = useRef<HTMLDivElement>(null);
  const field = useRef<HTMLTextAreaElement>(null);
  const [pinned, setPinned] = useState(true);
  /** Mirrors `pinned` for the resize observer, which outlives renders. */
  const pinnedRef = useRef(true);
  const [unseen, setUnseen] = useState(0);
  const [searchOpen, setSearchOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const searchField = useRef<HTMLInputElement>(null);
  const seenCount = useRef(0);
  /** Rendered rows, for the resize observer to tell new messages from in-place growth. */
  const rowCount = useRef(0);
  const photoPicker = useRef<HTMLInputElement>(null);
  /** Files in the send sheet; null while it is closed. */
  const [attaching, setAttaching] = useState<File[] | null>(null);
  const [attachingVideos, setAttachingVideos] = useState<File[] | null>(null);
  /** Photos from a mixed pick, shown after the video sheet closes. */
  const [photosAfterVideos, setPhotosAfterVideos] = useState<File[] | null>(null);
  const [viewing, setViewing] = useState<string | null>(null);
  /** Row flashing after a jump from a reply header. */
  const [flashing, setFlashing] = useState<string | null>(null);
  /** Open message menu: which message, where, and any text selected inside its bubble. */
  const [menu, setMenu] = useState<{
    message: ChatMessage;
    anchor: MessageMenuAnchor;
    selection: string;
    settle: boolean;
  } | null>(null);
  /** Message waiting on the delete confirmation (scope is picked there). */
  const [confirmDelete, setConfirmDelete] = useState<ChatMessage | null>(null);
  /** Short-lived, neutral status line ("Copied"). */
  const [notice, setNotice] = useState<string | null>(null);
  const flashTimer = useRef(0);
  const noticeTimer = useRef(0);
  const [watching, setWatching] = useState<string | null>(null);
  const [dropping, setDropping] = useState(false);
  const dragDepth = useRef(0);

  const toBottom = useCallback((behavior: ScrollBehavior = "auto") => {
    const node = scroller.current;
    if (!node) return;
    node.scrollTo({ top: node.scrollHeight, behavior });
    pinnedRef.current = true;
    setPinned(true);
    setUnseen(0);
  }, []);

  const onScroll = useCallback(() => {
    const node = scroller.current;
    if (!node) return;
    const distance = node.scrollHeight - node.scrollTop - node.clientHeight;
    const atBottom = distance <= PIN_SLACK;
    pinnedRef.current = atBottom;
    setPinned(atBottom);
    if (atBottom) setUnseen(0);
  }, []);

  const finePointer = () => window.matchMedia?.("(pointer: fine)").matches ?? false;

  const voice = useVoiceRecording({
    canSend,
    onSendVoice,
    onRecordingChange,
    resetKey: peer.id,
    onSettled: () => {
      if (finePointer()) field.current?.focus();
    },
  });

  /* The footer grows while recording (and as the field wraps), and a transcript
     unfolds in place; without this the newest messages would slide under the
     footer or out of view instead of staying pinned to the bottom. */
  useEffect(() => {
    const node = scroller.current;
    const inner = node?.firstElementChild;
    if (!node || typeof ResizeObserver === "undefined") return;
    let rowsAtLastResize = rowCount.current;
    const observer = new ResizeObserver((entries) => {
      let follow = false;
      for (const entry of entries) {
        if (entry.target !== inner) {
          follow = true;
          continue;
        }
        // Rows coming or going are scrolled by the effects below (smoothly, when that
        // is what they want); only an in-place resize is ours to follow.
        if (rowCount.current === rowsAtLastResize) follow = true;
        rowsAtLastResize = rowCount.current;
      }
      if (follow && pinnedRef.current) node.scrollTop = node.scrollHeight;
    });
    observer.observe(node);
    if (inner) observer.observe(inner);
    if (foot.current) observer.observe(foot.current);
    return () => observer.disconnect();
  }, []);

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
    return messages.filter((m) => {
      if (m.deleted) return false;
      if (m.text.toLowerCase().includes(needle)) return true;
      return Boolean(m.transcript && m.transcript.toLowerCase().includes(needle));
    });
  }, [messages, query]);
  const rows = useMemo(() => buildRows(visible), [visible]);

  /* The activity bubble sinks away when the peer stops, but gives way at once when
     their message arrives, so the message lands where the bubble was. */
  const lastIncoming = useMemo(() => {
    for (let i = messages.length - 1; i >= 0; i--) if (!messages[i].isMine) return messages[i].id;
    return null;
  }, [messages]);
  /** Whether the bubble is still on screen, and the newest message from them while it was. */
  const [activitySeen, setActivitySeen] = useState({
    shown: Boolean(activity),
    arrival: lastIncoming,
    word: activity ?? ("typing" as PeerActivity),
  });
  useEffect(() => {
    if (activity) {
      setActivitySeen({ shown: true, arrival: lastIncoming, word: activity });
      return;
    }
    const timer = window.setTimeout(
      () => setActivitySeen((seen) => ({ ...seen, shown: false })),
      TYPING_OUT_MS,
    );
    return () => window.clearTimeout(timer);
  }, [activity, lastIncoming]);
  const showActivity = Boolean(activity) || (activitySeen.shown && activitySeen.arrival === lastIncoming);
  const activityWord = activity ?? activitySeen.word;
  useLayoutEffect(() => {
    rowCount.current = rows.length;
  }, [rows.length]);

  /* The newest voice notes show their transcript unasked. Worked out from the whole
     thread (a search filter doesn't change what is newest), and synchronously, so
     the server's copy of a sent note takes over its bubble without a flicker. */
  const tailKey = useMemo(() => transcriptTail(messages).join(","), [messages]);
  const tail = useMemo(() => new Set(tailKey ? tailKey.split(",") : []), [tailKey]);
  const lastTail = useRef({ peer: peer.id, ids: [] as string[] });
  useLayoutEffect(() => {
    const before = lastTail.current;
    const ids = [...tail];
    lastTail.current = { peer: peer.id, ids };
    if (before.peer !== peer.id) return;
    // Pushed off the bottom by something newer: forget the reader's choice and fold.
    for (const id of before.ids) if (!tail.has(id)) clearTranscriptChoice(id);
  }, [peer.id, tail]);

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

  function openQueuedPhotos(queued: File[] | null) {
    if (queued?.length) setAttaching(queued);
    else if (finePointer()) field.current?.focus();
  }

  /** Opens the send sheet with these photos and videos, or adds them to one already open. */
  function attach(picked: File[]) {
    if (!canSend) return;
    const videos = videoFiles(picked);
    const photos = imageFiles(picked);
    if (videos.length === 0 && photos.length === 0) return;

    if (attachingVideos) {
      if (videos.length) {
        setAttachingVideos((open) => [...(open ?? []), ...videos].slice(0, MAX_PHOTOS_PER_SEND));
      }
      if (photos.length) {
        setPhotosAfterVideos((queued) => [...(queued ?? []), ...photos].slice(0, MAX_PHOTOS_PER_SEND));
      }
      return;
    }
    if (attaching) {
      if (photos.length) setAttaching((open) => [...(open ?? []), ...photos].slice(0, MAX_PHOTOS_PER_SEND));
      return;
    }
    if (videos.length) {
      if (photos.length) setPhotosAfterVideos(photos.slice(0, MAX_PHOTOS_PER_SEND));
      setAttachingVideos(videos.slice(0, MAX_PHOTOS_PER_SEND));
      return;
    }
    setAttaching(photos.slice(0, MAX_PHOTOS_PER_SEND));
  }

  const closeAttach = useCallback(() => {
    setAttaching(null);
    if (finePointer()) field.current?.focus();
  }, []);

  const sendAttached = useCallback(
    (images: PreparedImage[], caption: string) => {
      setAttaching(null);
      onSendImages(images, caption);
      if (finePointer()) field.current?.focus();
    },
    [onSendImages],
  );

  const closeVideoAttach = useCallback(() => {
    setAttachingVideos(null);
    const queued = photosAfterVideos;
    setPhotosAfterVideos(null);
    openQueuedPhotos(queued);
  }, [photosAfterVideos]);

  const sendAttachedVideos = useCallback(
    (drafts: VideoSendDraft[], caption: string) => {
      setAttachingVideos(null);
      onSendVideos(drafts, caption);
      const queued = photosAfterVideos;
      setPhotosAfterVideos(null);
      openQueuedPhotos(queued);
    },
    [onSendVideos, photosAfterVideos],
  );

  function onPaste(event: ClipboardEvent<HTMLTextAreaElement>) {
    const photos = clipboardImages(event.clipboardData);
    const videos = clipboardVideos(event.clipboardData);
    if (photos.length === 0 && videos.length === 0) return;
    event.preventDefault();
    attach([...photos, ...videos]);
  }

  /* Photos dragged anywhere over the chat can be dropped to send. Enter and leave fire
     for every child the pointer crosses, so count depth rather than trust the last one. */
  function onDragEnter(event: DragEvent<HTMLElement>) {
    if (!canSend || !hasFiles(event)) return;
    event.preventDefault();
    dragDepth.current += 1;
    setDropping(true);
  }

  function onDragOver(event: DragEvent<HTMLElement>) {
    if (!canSend || !hasFiles(event)) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = "copy";
  }

  function onDragLeave(event: DragEvent<HTMLElement>) {
    if (!hasFiles(event)) return;
    dragDepth.current = Math.max(0, dragDepth.current - 1);
    if (dragDepth.current === 0) setDropping(false);
  }

  function onDrop(event: DragEvent<HTMLElement>) {
    if (!hasFiles(event)) return;
    event.preventDefault();
    dragDepth.current = 0;
    setDropping(false);
    attach(Array.from(event.dataTransfer.files));
  }

  const photos = useMemo(() => messages.filter(isPhoto), [messages]);

  /** Quoted messages still in the thread, so every header resolves without a scan per row. */
  const quoted = useMemo(() => {
    const wanted = new Set(
      messages.map((m) => m.replyTo?.id.toLowerCase()).filter((id): id is string => Boolean(id)),
    );
    if (wanted.size === 0) return new Map<string, ChatMessage>();
    const byId = new Map<string, ChatMessage>();
    for (const m of messages) {
      const key = m.id.toLowerCase();
      if (wanted.has(key) && !byId.has(key)) byId.set(key, m);
    }
    return byId;
  }, [messages]);

  /* A short, neutral status line under the thread; the newest notice replaces the last. */
  const showNotice = useCallback((text: string, ms = 1800) => {
    setNotice(text);
    window.clearTimeout(noticeTimer.current);
    noticeTimer.current = window.setTimeout(() => setNotice(null), ms);
  }, []);

  /* Scrolls to the quoted message and flashes it; says so when it is no longer here. */
  const jumpTo = useCallback(
    (id: string) => {
      const key = id.toLowerCase();
      const node = scroller.current?.querySelector<HTMLElement>(
        `[data-message-id="${CSS.escape(key)}"]`,
      );
      if (!node) {
        showNotice("The original message isn’t in this chat any more.", 2400);
        return;
      }
      node.scrollIntoView({ behavior: "smooth", block: "center" });
      setFlashing(key);
      window.clearTimeout(flashTimer.current);
      flashTimer.current = window.setTimeout(() => setFlashing(null), 1400);
    },
    [showNotice],
  );

  useEffect(
    () => () => {
      window.clearTimeout(flashTimer.current);
      window.clearTimeout(noticeTimer.current);
    },
    [],
  );

  /* Opens the message menu, remembering any text the reader had selected in that bubble. */
  const openMenu = useCallback(
    (message: ChatMessage, anchor: MessageMenuAnchor, row: HTMLElement | null, settle = false) => {
      const selected = window.getSelection();
      let selection = "";
      if (selected && !selected.isCollapsed && row && selected.rangeCount > 0) {
        const node = selected.getRangeAt(0).commonAncestorContainer;
        if (row.contains(node)) selection = selected.toString().trim();
      }
      setMenu({ message, anchor, selection, settle });
    },
    [],
  );
  const closeMenu = useCallback(() => setMenu(null), []);

  function menuActions(message: ChatMessage, selection: string): MessageMenuAction[] {
    const actions: MessageMenuAction[] = [];
    if (canQuote(message)) actions.push("reply");
    if (selection || copyableText(message)) actions.push("copy");
    actions.push("delete");
    return actions;
  }

  async function copyToClipboard(text: string) {
    if (await writeClipboard(text)) showNotice("Copied to clipboard");
    else showNotice("The browser blocked the clipboard — select the text to copy it.", 2800);
  }

  function runMenuAction(action: MessageMenuAction) {
    if (!menu) return;
    const { message, selection } = menu;
    setMenu(null);
    if (action === "reply") onReply(message);
    else if (action === "copy") void copyToClipboard(selection || copyableText(message));
    else setConfirmDelete(message);
  }

  /* Escape drops the reply, the way it closes search — but search, an open message menu or the
     delete dialog each take that Escape first. */
  const escapeTaken = searchOpen || menu !== null || confirmDelete !== null;
  useEffect(() => {
    if (!replyTo) return;
    const onKey = (event: globalThis.KeyboardEvent) => {
      if (event.key !== "Escape") return;
      if (escapeTaken) return;
      onCancelReply();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [replyTo, escapeTaken, onCancelReply]);

  /* Answering something puts the caret back in the composer, as Telegram does. */
  useEffect(() => {
    if (replyTo && finePointer()) field.current?.focus();
  }, [replyTo]);

  return (
    <section
      className="thread"
      onDragEnter={onDragEnter}
      onDragOver={onDragOver}
      onDragLeave={onDragLeave}
      onDrop={onDrop}
    >
      <header className="thread-head">
        <button className="icon-btn back-mobile" type="button" aria-label="Back to chats" onClick={onBack}>
          <ChevronLeft size={20} />
        </button>
        <Avatar name={peer.username} seed={peer.id} online={online} />
        <div className="thread-peer">
          <strong>{peer.username}</strong>
          {activity ? (
            <TypingLabel word={activity} />
          ) : (
            <span className={online ? "online" : undefined}>{presence || " "}</span>
          )}
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
            rows.map((row) => {
              if (row.kind === "day") {
                return (
                  <div className="day-sep" key={row.key}>
                    <span>{row.label}</span>
                  </div>
                );
              }
              return (
                <MessageRow
                  key={row.key}
                  row={row}
                  query={query}
                  inTail={tail.has(row.message.id)}
                  peerName={peer.username}
                  myId={myId}
                  quoted={quoted}
                  flashing={flashing === row.message.id.toLowerCase()}
                  onReply={onReply}
                  onMenu={openMenu}
                  onJump={jumpTo}
                  onOpenPhoto={(opened) => setViewing(opened.id)}
                  onOpenVideo={(opened) => setWatching(opened.id)}
                  onLoadImage={onLoadImage}
                  onLoadVideo={onLoadVideo}
                  onLoadVoice={onLoadVoice}
                />
              );
            })
          )}
          {showActivity && !query ? (
            <TypingBubble name={peer.username} leaving={!activity} word={activityWord} />
          ) : null}
        </div>
      </div>

      <div className="thread-foot" ref={foot}>
        {!pinned && messages.length > 0 ? (
          <button className="jump-latest" type="button" onClick={() => toBottom("smooth")}>
            <ArrowDown size={15} aria-hidden="true" />
            {unseen > 0 ? `${unseen} new message${unseen > 1 ? "s" : ""}` : "Latest"}
          </button>
        ) : null}

        {error && messages.length > 0 ? (
          <p className="thread-banner" role="status">
            {error}
          </p>
        ) : null}

        {voice.hint ? (
          <p className="thread-banner voice-hint" role="status">
            {voice.hint}
          </p>
        ) : null}

        {notice ? (
          <p className="thread-banner notice" role="status">
            {notice}
          </p>
        ) : null}

        {replyTo ? (
          <div className="reply-bar">
            <Reply className="reply-bar-glyph" size={18} aria-hidden="true" />
            <ReplyQuote
              quote={quoteOf(
                messages.find((m) => m.id.toLowerCase() === replyTo.id.toLowerCase()) ?? replyTo,
                peer.username,
              )}
              variant="bar"
              onClick={() => jumpTo(replyTo.id.toLowerCase())}
            />
            <button
              className="icon-btn reply-bar-close"
              type="button"
              aria-label="Cancel reply"
              onClick={onCancelReply}
            >
              <X size={18} />
            </button>
          </div>
        ) : null}

        <div className="compose-shell" ref={voice.shellRef}>
          <VoiceStrip voice={voice} />
          <form
            className={draft.trim() ? "compose has-draft" : "compose"}
            onSubmit={submit}
            aria-busy={sending}
          >
            <button
              className="icon-btn compose-plus"
              type="button"
              aria-label="Send photos or videos"
              title="Send photos or videos"
              disabled={!canSend}
              onClick={() => photoPicker.current?.click()}
            >
              <Plus size={20} />
            </button>
            <button
              className="icon-btn compose-wide"
              type="button"
              aria-label="Attach a file"
              title="Files are coming to the web client soon"
              disabled
            >
              <Paperclip size={18} />
            </button>
            <button
              className="icon-btn compose-wide"
              type="button"
              aria-label="Send photos or videos"
              title="Send photos or videos — or paste or drop them here"
              disabled={!canSend}
              onClick={() => photoPicker.current?.click()}
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
                onPaste={onPaste}
                enterKeyHint="send"
                disabled={!canSend}
              />
            </div>
            {/* Stays enabled while a recording is open (aria-disabled only), so a
                held pointer keeps its capture until release. */}
            <button
              ref={voice.micRef}
              className="icon-btn compose-mic"
              type="button"
              aria-label="Record a voice message"
              title="Click or drag up to record"
              disabled={!canSend}
              aria-disabled={voice.phase !== "idle" || undefined}
              {...voice.micHandlers}
            >
              <Mic size={18} />
            </button>
            <button className="send" type="submit" aria-label="Send" disabled={!canSend || !draft.trim()}>
              <Send size={16} />
            </button>
          </form>
          <input
            ref={photoPicker}
            type="file"
            accept={MEDIA_ACCEPT}
            multiple
            hidden
            onChange={(event) => {
              attach(Array.from(event.target.files ?? []));
              event.target.value = "";
            }}
          />
          <VoiceDroplet voice={voice} />
        </div>
      </div>

      {dropping ? (
        <div className="drop-zone" aria-hidden="true">
          <div className="drop-card">
            <ImagePlus size={30} />
            <strong>Drop to send</strong>
            <span>Photos and videos are end-to-end encrypted</span>
          </div>
        </div>
      ) : null}

      {menu ? (
        <MessageMenu
          anchor={menu.anchor}
          actions={menuActions(menu.message, menu.selection)}
          copyLabel={menu.selection ? "Copy selection" : undefined}
          settle={menu.settle}
          onAction={runMenuAction}
          onClose={closeMenu}
        />
      ) : null}

      {confirmDelete ? (
        <DeleteMessageDialog
          message={confirmDelete}
          peerName={peer.username}
          preview={copyableText(confirmDelete)}
          onCancel={() => setConfirmDelete(null)}
          onDelete={(scope) => {
            const message = confirmDelete;
            setConfirmDelete(null);
            onDelete(message, scope);
          }}
        />
      ) : null}

      <Suspense fallback={null}>
        {attaching ? (
          <ImageComposer
            files={attaching}
            peerName={peer.username}
            onFilesChange={setAttaching}
            onClose={closeAttach}
            onSend={sendAttached}
          />
        ) : null}

        {attachingVideos ? (
          <VideoComposer
            files={attachingVideos}
            peerName={peer.username}
            onFilesChange={setAttachingVideos}
            onClose={closeVideoAttach}
            onSend={sendAttachedVideos}
          />
        ) : null}

        {viewing ? (
          <ImageViewer
            photos={photos}
            startId={viewing}
            peerName={peer.username}
            loadImage={onLoadImage}
            onClose={() => setViewing(null)}
          />
        ) : null}

        {watching && messages.some((m) => m.id === watching) ? (
          <VideoViewer
            message={messages.find((m) => m.id === watching)!}
            peerName={peer.username}
            loadVideo={onLoadVideo}
            onClose={() => setWatching(null)}
          />
        ) : null}
      </Suspense>
    </section>
  );
}
