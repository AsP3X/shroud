import { ApiError, api, type Conversation, type TransferProgress, type WireMessage } from "./api/client";
import { aesGcmOpen, sealFile } from "./crypto/aes";
import { b64ToBytes, bytesToB64, utf8, utf8decode } from "./crypto/bytes";
import type { IdentityMaterial } from "./crypto/identity";
import { deleteMediaBlobs, loadMediaBlob, saveMediaBlob } from "./crypto/mediaCache";
import {
  clampTranscript,
  decodeWaveform,
  encodeWaveform,
  isFilePayload,
  isVideoPayload,
  isVoicePayload,
  MAX_MEDIA_PAYLOAD_PLAINTEXT_BYTES,
  MAX_SEALED_ENVELOPE_BYTES,
  parseMediaPayload,
  payloadLinkPreview,
  payloadReply,
  withReply,
  type MediaPayload,
} from "./crypto/mediaPayload";
import { clampSnippet, parseTextPayload, textWire, type ReplyRef } from "./reply";
import { fileTypeOf, sanitizeFileName, unsupportedRefusal } from "./files";
import { audioDisplayTitle, audioDurationOf, cleanTagText, isAudioFileName } from "./audioFiles";
import { linkPreviewWire, type LinkPreview } from "./links";
import { envelopeToWireB64, openMessage, sealMessage, wireB64ToEnvelope } from "./crypto/messageCrypto";
import {
  PeerKeyChanged,
  peerIdentityForSending,
  peerIdentityPublic as pinnedPeerIdentity,
} from "./crypto/peerIdentity";
import {
  forgetPlaintext,
  isWithdrawn,
  loadPlaintext,
  loadPreview,
  replacePreview,
  savePlaintext,
  savePreview,
} from "./crypto/plaintextCache";
import { MAX_THUMB_BYTES } from "./media/envelopePreview";
import { openReaction, pageReactionsFor, type Reaction } from "./reactions";
import { cacheSealedImage, releaseImage } from "./media/images";
import type { PreparedImage } from "./media/prepareImage";
import type { EncodedVideo } from "./media/prepareVideo";
import { cacheSealedPoster, cacheSealedVideo, releaseVideo } from "./media/videos";
import { releaseFile } from "./media/fileTransfer";
import { getVoicePlayback, stopVoice } from "./voice/playback";

/** `annotation` never reaches the thread: `applyAnnotations` folds it into its target. */
export type ChatKind = "text" | "image" | "voice" | "video" | "file" | "annotation";

/**
 * Sealed JSON inside `content_type = annotation`: data one participant attaches
 * to an earlier message. Today that is a voice transcript made on the
 * recipient's device and shared back (matches iOS `MessageAnnotation`).
 */
export type Annotation = { t: "transcript"; r: string; c: string };

/** Label for a voice note without a transcript (bubble text and chat preview). */
const VOICE_LABEL = "Voice message";
/** Stand-in text for a photo without a caption (search, chat preview), as on iOS. */
const PHOTO_LABEL = "Photo";
const VIDEO_LABEL = "Video";

export function parseAnnotation(raw: string): Annotation | null {
  try {
    const value = JSON.parse(raw) as Partial<Annotation> | null;
    if (!value || value.t !== "transcript") return null;
    if (typeof value.r !== "string" || typeof value.c !== "string") return null;
    const text = clampTranscript(value.c);
    return text ? { t: "transcript", r: value.r.toLowerCase(), c: text } : null;
  } catch {
    return null;
  }
}

export type ChatMessage = {
  id: string;
  senderUserId: string;
  text: string;
  createdAt: string;
  isMine: boolean;
  deleted: boolean;
  failed: boolean;
  kind: ChatKind;
  mediaObjectId?: string | null;
  voiceDurationMs?: number | null;
  voiceWaveform?: number[] | null;
  mediaKey?: string | null;
  mime?: string | null;
  transcript?: string | null;
  /** Caption sealed with a photo or video; null when there is none (`text` then reads "Photo"/"Video"). */
  caption?: string | null;
  imageWidth?: number | null;
  imageHeight?: number | null;
  /** Base64 JPEG preview (≤ 6 KB) sealed in the envelope, shown until the photo loads. */
  thumbnail?: string | null;
  /** Size of the media file itself, in bytes. */
  mediaBytes?: number | null;
  /** Length of a video, from its payload (`d`). */
  videoDurationMs?: number | null;
  /**
   * A shared file's name, cleaned again on this side (docs/file-sharing.md §5); its extension
   * decides the type. `mime` is then the type table's, never the sender's.
   */
  fileName?: string | null;
  /** A PDF's page count from its payload's `pg` (docs/file-sharing.md §10); null when unknown. */
  pageCount?: number | null;
  /**
   * An audio file's tags and length (docs/file-sharing.md §11.2): `ti` and `ar` cleaned again on
   * this side, `d` in ms. Null on other files and when the sender couldn't read them.
   */
  audioTitle?: string | null;
  audioArtist?: string | null;
  audioDurationMs?: number | null;
  /** Optimistic bubble shown until the server hands back a real id. */
  pending?: boolean;
  /** Set on `kind === "annotation"`; null when it could not be read. */
  annotation?: Annotation | null;
  /** The message this one quotes, sealed inside its own plaintext (see `reply.ts`). */
  replyTo?: ReplyRef | null;
  /**
   * Link preview the sender's phone sealed into the message (see `links.ts`). When it has a
   * large picture, that picture is this message's media blob (`mediaObjectId` / `mediaKey`),
   * with `thumbnail` as its blurred placeholder.
   */
  linkPreview?: LinkPreview | null;
  /** Optimistic link bubble whose large picture is still uploading (drawn from `adoptImage`). */
  localLinkImage?: boolean;
  delivered?: boolean;
  read?: boolean;
  /** Everyone's reactions, oldest change first; removals stay as entries with no emoji. */
  reactions?: Reaction[];
};

/** Annotation ids already decoded, so polling doesn't fetch them again every tick. */
const seenAnnotations = new Set<string>();
/**
 * Transcripts shared as annotations, by voice note id. Kept for the session so a
 * note that lands after its annotation (send completing, a later poll) still gets
 * it. Never consumed: `applyAnnotations` runs inside React state updaters, which
 * React may call twice, so reading it must not change it.
 */
const sharedTranscripts = new Map<string, string>();
const peerLocks = new Map<string, Promise<unknown>>();

/** Lock and logout: shared transcripts leave memory with the rest of the unlocked shell. */
export function forgetDecryptedState(): void {
  sharedTranscripts.clear();
  seenAnnotations.clear();
}

function noteSharedTranscript(annotation: Annotation): void {
  if (!sharedTranscripts.has(annotation.r)) sharedTranscripts.set(annotation.r, annotation.c);
}

/** The chat list shows a voice note's transcript; fill it in when it arrives late. */
function previewSharedTranscript(me: string, peerUserId: string, annotation: Annotation): void {
  const preview = loadPreview(me, peerUserId);
  if (!preview || preview.id?.toLowerCase() !== annotation.r) return;
  if (preview.text !== VOICE_LABEL) return; // the sender sealed one; it wins
  savePreview(me, peerUserId, { ...preview, text: annotation.c });
}

async function withPeerLock<T>(peerUserId: string, fn: () => Promise<T>): Promise<T> {
  const key = peerUserId.toLowerCase();
  const prev = peerLocks.get(key) ?? Promise.resolve();
  let release!: () => void;
  const next = new Promise<void>((resolve) => {
    release = resolve;
  });
  peerLocks.set(key, prev.then(() => next));
  await prev;
  try {
    return await fn();
  } finally {
    release();
  }
}

/*
 * Delivery acks, as on iOS: every message of the other person's that reaches this browser for
 * the first time is acknowledged with `POST /messages/{id}/delivered`, so the sender sees two
 * ticks before the chat is read — an iPhone or Android sender otherwise sees one tick until
 * then. iOS acks a thread page's new inbound ids once the page is shown
 * (`MessagingController.swift:1340-1344`), an older page's (`:1447-1451`) and a socket message
 * before it is decoded (`:4318-4320`); here `loadHistoryPage`, `fetchLatest` and
 * `ingestIncoming` do the same.
 *
 * "For the first time" is iOS's "not already in the thread": its body is not in this browser's
 * plaintext cache yet, and this tab has not acked it before. Notes (our own chat) never acks.
 * Neither does a message deleted for everyone: there is nothing left to deliver, and a chat the
 * peer cleared would otherwise send one request per tombstone on every reload.
 *
 * The server marks only this device's delivery row and tells the sender's devices
 * (`message.delivered`); an ack that is repeated (204) or not for this device (404) changes
 * nothing. So acks go out one at a time in the background, never hold up a chat opening, and a
 * failed one is not retried — like iOS's `try?`.
 */
const acknowledgedDeliveries = new Set<string>();
let deliveryQueue: Promise<void> = Promise.resolve();

/**
 * Whether `dto` is the peer's message this browser has not seen before. Asked before decoding,
 * which caches the body.
 */
function deliveryAckDue(dto: WireMessage, me: string, peerUserId: string): boolean {
  if (peerUserId.toLowerCase() === me.toLowerCase()) return false; // Notes
  if (dto.sender_user_id.toLowerCase() === me.toLowerCase()) return false;
  if (dto.deleted_for_everyone) return false;
  if (acknowledgedDeliveries.has(dto.id.toLowerCase())) return false;
  return loadPlaintext(dto.id, dto.sender_user_id) == null;
}

/** Queues one ack per id not acked yet in this tab; returns at once. */
function acknowledgeDelivery(token: string, messageIds: string[]): void {
  for (const raw of messageIds) {
    const id = raw.toLowerCase();
    if (acknowledgedDeliveries.has(id)) continue;
    acknowledgedDeliveries.add(id);
    deliveryQueue = deliveryQueue.then(() => postDelivered(token, id));
  }
}

/**
 * `POST /messages/{id}/delivered` with the lower-case id and no body (iOS
 * `MessagesService.swift:157-162`, server `routes/messages.rs` `mark_delivered`), errors
 * swallowed.
 */
async function postDelivered(token: string, messageId: string): Promise<void> {
  try {
    await api.markDelivered(token, messageId);
  } catch {
    /* offline or signed out: dropped, like an iOS ack that fails */
  }
}

/** Resolves once every ack queued so far went out (selftests). */
export function deliveryAcksSettled(): Promise<void> {
  return deliveryQueue;
}

/** The pinned identity key. A later change is remembered and the old key is what decrypts. */
export async function peerIdentityPublic(token: string, peerUserId: string): Promise<Uint8Array> {
  return pinnedPeerIdentity(token, peerUserId);
}

export { PeerKeyChanged, peerIdentityForSending };

/** The line a failed send shows. A changed identity key says so; everything else keeps `fallback`. */
export function sendFailureText(err: unknown, fallback: string): string {
  if (err instanceof PeerKeyChanged) return err.message;
  if (err instanceof ApiError) return err.message;
  return fallback;
}

export function peerIdForMessage(
  dto: WireMessage,
  me: string,
  conversations: Conversation[],
): string {
  const mine = dto.sender_user_id.toLowerCase() === me.toLowerCase();
  if (!mine) return dto.sender_user_id.toLowerCase();
  const conv = conversations.find(
    (c) => c.id.toLowerCase() === dto.conversation_id.toLowerCase(),
  );
  return (conv?.peer.id ?? dto.sender_user_id).toLowerCase();
}

export async function ingestIncoming(
  dto: WireMessage,
  me: string,
  peerUserId: string,
  token: string,
  material: IdentityMaterial,
): Promise<ChatMessage> {
  return withPeerLock(peerUserId, () => {
    // Acked before decoding, as iOS `ingestIncoming` does (`MessagingController.swift:4318-4320`).
    if (deliveryAckDue(dto, me, peerUserId)) acknowledgeDelivery(token, [dto.id]);
    return decodeIncoming(dto, me, peerUserId, token, material);
  });
}

/** The lowest seq of a page's reactions left unopened: the sender's key was out of reach. */
type Unopened = { seq: number | null };

/**
 * A decoded message with the reactions its history page carried. A record whose sender's key
 * can't be fetched right now is left out and noted in `unopened`: catch-up goes back for it.
 */
async function decodeWithReactions(
  dto: WireMessage,
  me: string,
  peer: string,
  token: string,
  material: IdentityMaterial,
  unopened: Unopened,
): Promise<ChatMessage> {
  const msg = await decodeIncoming(dto, me, peer, token, material);
  if (!dto.reactions?.length || msg.deleted || msg.kind === "annotation") return msg;
  const reactions: Reaction[] = [];
  for (const wire of pageReactionsFor(dto.id, dto.reactions, new Set([me.toLowerCase(), peer]))) {
    try {
      reactions.push(await openReaction(wire, me, material, (id) => peerIdentityPublic(token, id)));
    } catch {
      unopened.seq = Math.min(unopened.seq ?? wire.seq, wire.seq);
    }
  }
  return { ...msg, reactions };
}

export async function fetchLatest(
  token: string,
  me: string,
  peerUserId: string,
  material: IdentityMaterial,
  knownIds: Set<string>,
): Promise<{ messages: ChatMessage[]; reactionSeq: number | null; reactionUnopened: number | null }> {
  const peer = peerUserId.toLowerCase();
  return withPeerLock(peer, async () => {
    const res = await api.listMessages(token, peer);
    const fresh = [...res.messages]
      .reverse()
      .filter((dto) => !knownIds.has(dto.id) && !seenAnnotations.has(dto.id.toLowerCase()));
    const out: ChatMessage[] = [];
    const unopened: Unopened = { seq: null };
    const due = fresh.filter((dto) => deliveryAckDue(dto, me, peer)).map((dto) => dto.id);
    for (const dto of fresh) out.push(await decodeWithReactions(dto, me, peer, token, material, unopened));
    forgetTombstones(fresh);
    acknowledgeDelivery(token, due);
    return { messages: out, reactionSeq: res.reaction_seq ?? null, reactionUnopened: unopened.seq };
  });
}

function kindFromPayload(payload: MediaPayload | null, isMedia: boolean): ChatKind {
  if (!isMedia) return "text";
  if (payload && isFilePayload(payload)) return "file";
  if (payload && payloadLinkPreview(payload)) return "text";
  if (payload && isVoicePayload(payload)) return "voice";
  if (payload && isVideoPayload(payload)) return "video";
  return "image";
}

/** The bubble a decoded media payload draws (exported for the selftests). */
export function messageFromMediaPayload(
  base: Omit<ChatMessage, "text" | "kind">,
  payload: MediaPayload,
  mediaObjectId: string | null | undefined,
): ChatMessage {
  base = { ...base, replyTo: payloadReply(payload) };
  // A file before anything that sniffs `mime`: a PDF named .mp4 is still a file.
  if (isFilePayload(payload)) {
    const caption = payload.c?.trim() || "";
    const fileName = sanitizeFileName(payload.n ?? "");
    // Tags and a length only mean something on an audio file (§11.2); anywhere else they go.
    const audio = isAudioFileName(fileName);
    const audioTitle = audio ? cleanTagText(payload.ti) : null;
    const audioArtist = audio ? cleanTagText(payload.ar) : null;
    return {
      ...base,
      kind: "file",
      // What a search matches: the caption, else what the bubble calls the file.
      text: caption || (audio ? audioDisplayTitle(audioTitle, audioArtist, fileName) : fileName),
      caption: caption || null,
      fileName,
      mediaObjectId: mediaObjectId ?? null,
      mediaKey: payload.k,
      mime: fileTypeOf(fileName)?.mime ?? null,
      imageWidth: payload.w > 0 ? payload.w : null,
      imageHeight: payload.h > 0 ? payload.h : null,
      thumbnail: payload.th?.trim() || null,
      mediaBytes: payload.s ?? null,
      pageCount: payload.pg ?? null,
      audioTitle,
      audioArtist,
      audioDurationMs: audio ? audioDurationOf(payload.d) : null,
    };
  }
  const linkPreview = payloadLinkPreview(payload);
  if (linkPreview) {
    // A text message whose preview picture is the blob; it reads and quotes as text.
    return {
      ...base,
      kind: "text",
      text: payload.c?.trim() ?? "",
      linkPreview,
      mediaObjectId: mediaObjectId ?? null,
      mediaKey: payload.k,
      mime: payload.mime || "image/jpeg",
      imageWidth: payload.w > 0 ? payload.w : null,
      imageHeight: payload.h > 0 ? payload.h : null,
      thumbnail: payload.th?.trim() || null,
      mediaBytes: payload.s ?? null,
    };
  }
  if (isVoicePayload(payload)) {
    const sealed = payload.c?.trim() || null;
    const shared = sharedTranscripts.get(base.id.toLowerCase());
    const transcript = sealed || shared || null;
    return {
      ...base,
      kind: "voice",
      text: transcript || VOICE_LABEL,
      mediaObjectId: mediaObjectId ?? null,
      voiceDurationMs: payload.d ?? null,
      voiceWaveform: decodeWaveform(payload.wf),
      mediaKey: payload.k,
      mime: payload.mime || "audio/mp4",
      transcript,
    };
  }
  const caption = payload.c?.trim() || "";
  if (isVideoPayload(payload)) {
    return {
      ...base,
      kind: "video",
      text: caption || VIDEO_LABEL,
      caption: caption || null,
      mediaObjectId: mediaObjectId ?? null,
      mediaKey: payload.k,
      mime: payload.mime || "video/mp4",
      imageWidth: payload.w > 0 ? payload.w : null,
      imageHeight: payload.h > 0 ? payload.h : null,
      thumbnail: payload.th?.trim() || null,
      mediaBytes: payload.s ?? null,
      videoDurationMs: payload.d ?? null,
    };
  }
  return {
    ...base,
    kind: "image",
    text: caption || PHOTO_LABEL,
    caption: caption || null,
    mediaObjectId: mediaObjectId ?? null,
    mediaKey: payload.k,
    mime: payload.mime,
    imageWidth: payload.w > 0 ? payload.w : null,
    imageHeight: payload.h > 0 ? payload.h : null,
    thumbnail: payload.th?.trim() || null,
    mediaBytes: payload.s ?? null,
  };
}

/** The chat list's and the notification's line for an audio file without a caption (§7). */
function audioPreview(title: string | null | undefined, artist: string | null | undefined, name: string): string {
  return `🎵 ${audioDisplayTitle(title, artist, name)}`;
}

/** A file payload's preview line, straight from the payload (the caption, else its name or 🎵 title). */
function filePayloadPreview(payload: MediaPayload): string {
  const caption = payload.c?.trim();
  if (caption) return caption;
  const name = sanitizeFileName(payload.n ?? "");
  return isAudioFileName(name) ? audioPreview(payload.ti, payload.ar, name) : name;
}

/** What a chat row (and a notification that may show text) says about a message. */
export function previewCopy(msg: ChatMessage): string {
  if (msg.deleted) return "Message deleted";
  if (msg.failed) return "Encrypted message";
  if (msg.kind === "voice") return msg.transcript?.trim() || VOICE_LABEL;
  if (msg.kind === "video") return msg.text || VIDEO_LABEL;
  if (msg.kind === "image") return msg.text || PHOTO_LABEL;
  // The caption, else the file name; an audio file its 🎵 title (docs/file-sharing.md §7, §11.2).
  if (msg.kind === "file") {
    const caption = msg.caption?.trim();
    if (caption) return caption;
    if (msg.fileName && isAudioFileName(msg.fileName)) {
      return audioPreview(msg.audioTitle, msg.audioArtist, msg.fileName);
    }
    return msg.fileName || msg.text;
  }
  return msg.text;
}

export async function decodeIncoming(
  dto: WireMessage,
  me: string,
  peerUserId: string,
  token: string,
  material: IdentityMaterial,
): Promise<ChatMessage> {
  const isMine = dto.sender_user_id.toLowerCase() === me.toLowerCase();
  const isMedia = dto.content_type === "media";
  const isAnnotation = dto.content_type === "annotation";
  const base = {
    id: dto.id,
    senderUserId: dto.sender_user_id,
    createdAt: dto.created_at,
    isMine,
    deleted: dto.deleted_for_everyone,
    failed: false,
    kind: (isMedia ? "image" : "text") as ChatKind,
    mediaObjectId: dto.media_object_id ?? null,
    delivered: dto.delivered ?? undefined,
    read: dto.read ?? undefined,
  };
  /* Annotations never touch the chat preview: they are not messages to the user. */
  const annotationFrom = (plain: string | null): ChatMessage => {
    seenAnnotations.add(dto.id.toLowerCase());
    const annotation = plain == null ? null : parseAnnotation(plain);
    if (annotation) {
      noteSharedTranscript(annotation);
      previewSharedTranscript(me, peerUserId, annotation);
    }
    return {
      ...base,
      kind: "annotation",
      text: "",
      annotation,
    };
  };
  if (dto.deleted_for_everyone) {
    if (isAnnotation) return annotationFrom(null);
    const msg: ChatMessage = { ...base, text: "Message deleted" };
    rememberPreview(me, peerUserId, msg);
    return msg;
  }
  const cached = loadPlaintext(dto.id, dto.sender_user_id);
  if (isAnnotation && cached != null) return annotationFrom(cached);
  if (cached != null && cached !== "[media]") {
    if (isMedia) {
      const payload = parseMediaPayload(cached);
      if (payload) {
        const msg = messageFromMediaPayload(base, payload, dto.media_object_id);
        rememberPreview(me, peerUserId, msg);
        return msg;
      }
    } else {
      const parsed = parseTextPayload(cached);
      const msg: ChatMessage = {
        ...base,
        text: parsed.text,
        replyTo: parsed.replyTo,
        linkPreview: parsed.linkPreview,
      };
      rememberPreview(me, peerUserId, msg);
      return msg;
    }
  }
  if (!dto.ciphertext) {
    if (isAnnotation) return annotationFrom(null);
    const msg: ChatMessage = {
      ...base,
      text: isMedia ? "Media" : "[Unable to decrypt]",
      failed: true,
    };
    rememberPreview(me, peerUserId, msg);
    return msg;
  }
  try {
    const envelope = wireB64ToEnvelope(dto.ciphertext);
    const senderPub = isMine
      ? material.agreementPublic
      : await peerIdentityPublic(token, dto.sender_user_id);
    const plain = await openMessage({
      envelopeData: envelope,
      peerUserId,
      ourUserId: me,
      ourPrivate: material.agreementPrivate,
      ourIdentityPublic: material.agreementPublic,
      senderIdentityPublic: senderPub,
      asSender: isMine,
    });
    const decoded = utf8decode(plain);
    if (isAnnotation) {
      savePlaintext(dto.id, dto.sender_user_id, decoded);
      return annotationFrom(decoded);
    }
    if (isMedia) {
      savePlaintext(dto.id, dto.sender_user_id, decoded);
      const payload = parseMediaPayload(decoded);
      const msg = payload
        ? messageFromMediaPayload(base, payload, dto.media_object_id)
        : { ...base, text: "Media", kind: "image" as const };
      rememberPreview(me, peerUserId, msg);
      return msg;
    }
    savePlaintext(dto.id, dto.sender_user_id, decoded);
    const parsed = parseTextPayload(decoded);
    const msg: ChatMessage = {
      ...base,
      text: parsed.text,
      replyTo: parsed.replyTo,
      linkPreview: parsed.linkPreview,
    };
    rememberPreview(me, peerUserId, msg);
    return msg;
  } catch {
    if (isAnnotation) return annotationFrom(null);
    const msg: ChatMessage = {
      ...base,
      text: isMedia ? "Media" : "[Unable to decrypt]",
      failed: true,
    };
    rememberPreview(me, peerUserId, msg);
    return msg;
  }
}

/**
 * Folds annotations into the messages they point at and drops them from the list.
 * A shared transcript only fills a gap: one sealed by the sender, or shared
 * earlier, is never replaced (same rule as iOS). Pure with respect to its input:
 * safe to call from React state updaters.
 */
export function applyAnnotations(list: ChatMessage[]): ChatMessage[] {
  for (const m of list) {
    if (m.kind === "annotation" && m.annotation?.t === "transcript") {
      noteSharedTranscript(m.annotation);
    }
  }
  const out: ChatMessage[] = [];
  for (const m of list) {
    if (m.kind === "annotation") continue;
    const shared =
      m.kind === "voice" && !m.deleted && !m.transcript?.trim()
        ? sharedTranscripts.get(m.id.toLowerCase())
        : undefined;
    out.push(shared ? { ...m, transcript: shared, text: shared } : m);
  }
  return out;
}

function rememberPreview(me: string, peerUserId: string, msg: ChatMessage): void {
  savePreview(me, peerUserId, {
    id: msg.id,
    text: previewCopy(msg),
    at: msg.createdAt,
    isMine: msg.isMine,
    failed: msg.failed,
  });
}

const voiceLoads = new Map<string, Promise<Uint8Array | null>>();

/** Downloads and decrypts voice bytes (deduped). Safe to call from every bubble mount. */
export function ensureVoiceLoaded(message: ChatMessage, token: string): Promise<Uint8Array | null> {
  if (message.kind !== "voice" || message.deleted) {
    return Promise.resolve(null);
  }
  const key = message.id.toLowerCase();
  const existing = voiceLoads.get(key);
  if (existing) return existing;
  const task = (async () => {
    const cached = await loadMediaBlob(message.id);
    if (cached && cached.length > 0) return cached;
    if (!message.mediaObjectId || !message.mediaKey) return null;
    const sealed = await api.getMediaContent(token, message.mediaObjectId);
    const audio = await aesGcmOpen(b64ToBytes(message.mediaKey), sealed);
    // Deleted while it downloaded: nothing of it may be stored.
    if (isWithdrawn(message.id)) return null;
    await saveMediaBlob(message.id, audio);
    return audio;
  })().catch(() => null);
  voiceLoads.set(key, task);
  void task.finally(() => {
    if (voiceLoads.get(key) === task) voiceLoads.delete(key);
  });
  return task;
}

/** Stops a deleted voice note if it is the one playing: its bubble, pause button included, is gone. */
export function releaseVoice(messageId: string): void {
  const key = messageId.toLowerCase();
  voiceLoads.delete(key);
  if (getVoicePlayback().activeId?.toLowerCase() === key) stopVoice();
}

export function previewLine(me: string, peerUserId: string): string {
  const preview = loadPreview(me, peerUserId);
  if (!preview) return "Encrypted conversation";
  if (preview.failed) return "Encrypted message";
  return preview.isMine ? `You: ${preview.text}` : preview.text;
}

export async function hydratePreviews(
  token: string,
  me: string,
  peers: { id: string; lastMessageAt: string | null }[],
  material: IdentityMaterial,
  /** False once that chat was cleared after this refresh started. Skip writing its preview. */
  stillCurrent?: (peerId: string) => boolean,
): Promise<void> {
  const pending = peers.filter((p) => {
    if (stillCurrent && !stillCurrent(p.id)) return false;
    if (!p.lastMessageAt) return false;
    const have = loadPreview(me, p.id);
    if (!have) return true;
    const haveT = Date.parse(have.at) || 0;
    const lastT = Date.parse(p.lastMessageAt) || 0;
    return lastT - haveT > 1000;
  });
  for (const peer of pending) {
    try {
      if (stillCurrent && !stillCurrent(peer.id)) continue;
      // A few, so a transcript shared after the newest message can't stand in for it.
      const res = await api.listMessages(token, peer.id, { limit: "5" });
      // Cleared while the page was in flight: do not put the old line back.
      if (stillCurrent && !stillCurrent(peer.id)) continue;
      const dto = res.messages.find((m) => m.content_type !== "annotation");
      if (!dto) continue;
      const mine = dto.sender_user_id.toLowerCase() === me.toLowerCase();
      const cached = loadPlaintext(dto.id, dto.sender_user_id);
      if (cached && cached !== "[media]") {
        const payload = dto.content_type === "media" ? parseMediaPayload(cached) : null;
        let transcript = payload?.t === "voice" ? payload.c?.trim() || null : null;
        if (payload?.t === "voice" && !transcript) {
          for (const other of res.messages) {
            if (other.content_type !== "annotation") continue;
            const raw = loadPlaintext(other.id, other.sender_user_id);
            const shared = raw ? parseAnnotation(raw) : null;
            if (shared?.r === dto.id.toLowerCase()) {
              transcript = shared.c;
              break;
            }
          }
        }
        rememberPreview(me, peer.id, {
          id: dto.id,
          senderUserId: dto.sender_user_id,
          text: payload
            ? isFilePayload(payload)
              ? filePayloadPreview(payload)
              : payloadLinkPreview(payload)
              ? payload.c?.trim() || ""
              : isVoicePayload(payload)
              ? transcript || VOICE_LABEL
              : isVideoPayload(payload)
                ? payload.c?.trim() || VIDEO_LABEL
                : payload.c?.trim() || PHOTO_LABEL
            : parseTextPayload(cached).text,
          createdAt: dto.created_at,
          isMine: mine,
          deleted: Boolean(dto.deleted_for_everyone),
          failed: false,
          kind: kindFromPayload(payload, dto.content_type === "media"),
          transcript,
        });
        continue;
      }
      if (mine || dto.deleted_for_everyone) {
        await decodeIncoming(dto, me, peer.id, token, material);
        if (stillCurrent && !stillCurrent(peer.id)) {
          forgetMessageLocally(dto.id);
          replacePreview(me, peer.id, null);
        }
      } else {
        rememberPreview(me, peer.id, {
          id: dto.id,
          senderUserId: dto.sender_user_id,
          text: "Encrypted message",
          createdAt: dto.created_at,
          isMine: false,
          deleted: false,
          failed: true,
          kind: "text",
        });
      }
    } catch {
      /* leave existing preview */
    }
  }
}

/** Where the next older page starts: the oldest message the server has handed us so far. */
export type HistoryCursor = { createdAt: string; id: string };

export type HistoryPage = {
  /** Oldest first, annotations already folded in. */
  messages: ChatMessage[];
  /** Pass back to `loadHistoryPage` for the page before this one; null once the start is reached. */
  older: HistoryCursor | null;
  /** The chat's highest reaction seq as the page was read; null from servers without reactions. */
  reactionSeq: number | null;
  /** The lowest seq of a reaction on the page left unopened (see `decodeWithReactions`). */
  reactionUnopened: number | null;
};

/** Newest page on open: small, so a chat shows up after one short decrypt. */
export const FIRST_PAGE_SIZE = 40;
/** Older pages walked in the background or on scroll (server max is 100). */
export const OLDER_PAGE_SIZE = 100;

/**
 * One page of a thread, newest first from the server. Without `before` it is the newest
 * page; with it, the page just older than that cursor. The chat opens on the first and
 * walks back a page at a time, so a long history never has to be decrypted in one go.
 */
export async function loadHistoryPage(
  token: string,
  me: string,
  peerUserId: string,
  material: IdentityMaterial,
  before: HistoryCursor | null = null,
  limit = before ? OLDER_PAGE_SIZE : FIRST_PAGE_SIZE,
): Promise<HistoryPage> {
  const peer = peerUserId.toLowerCase();
  return withPeerLock(peer, async () => {
    const extra: Record<string, string> = { limit: String(limit) };
    if (before) {
      extra.before_created_at = before.createdAt;
      extra.before_id = before.id.toLowerCase();
    }
    const res = await api.listMessages(token, peer, extra);
    const out: ChatMessage[] = [];
    const unopened: Unopened = { seq: null };
    const chronological = [...res.messages].reverse();
    const due = chronological.filter((dto) => deliveryAckDue(dto, me, peer)).map((dto) => dto.id);
    for (const dto of chronological) {
      out.push(await decodeWithReactions(dto, me, peer, token, material, unopened));
    }
    forgetTombstones(res.messages);
    // Once the page is decoded, so its acks never hold up the chat (iOS `:1340-1344`, `:1447-1451`).
    acknowledgeDelivery(token, due);
    const oldest = res.messages[res.messages.length - 1];
    const more = (res.has_more || res.messages.length >= limit) && oldest;
    return {
      messages: applyAnnotations(out),
      older: more ? { createdAt: oldest.created_at, id: oldest.id } : null,
      reactionSeq: res.reaction_seq ?? null,
      reactionUnopened: unopened.seq,
    };
  });
}

export async function sendText(opts: {
  token: string;
  me: string;
  peerUserId: string;
  text: string;
  material: IdentityMaterial;
  /** Quote sealed with the body; the request itself is unchanged. */
  replyTo?: ReplyRef | null;
  /** Link preview built in this browser (see linkPreview/); sealed as `lp`, trimmed to fit. */
  linkPreview?: LinkPreview | null;
}): Promise<ChatMessage> {
  const peer = opts.peerUserId.toLowerCase();
  const me = opts.me.toLowerCase();
  const { wire, sealedPreview } = textWire(opts.text, opts.replyTo, opts.linkPreview);
  return withPeerLock(peer, async () => {
    const peerPub = await peerIdentityForSending(opts.token, peer);
    const envelope = await sealMessage({
      plaintext: utf8(wire),
      peerUserId: peer,
      ourUserId: me,
      ourPrivate: opts.material.agreementPrivate,
      ourIdentityPublic: opts.material.agreementPublic,
      peerIdentityPublic: peerPub,
    });
    const clientId = crypto.randomUUID();
    const dto = await api.sendMessage(opts.token, {
      peer_user_id: peer,
      client_message_id: clientId,
      content_type: "text",
      ciphertext: envelopeToWireB64(envelope),
    });
    // Cache what was sealed (quote included) so a reload rebuilds the same bubble.
    savePlaintext(dto.id, dto.sender_user_id, wire);
    const msg: ChatMessage = {
      id: dto.id,
      senderUserId: dto.sender_user_id,
      text: opts.text,
      createdAt: dto.created_at,
      isMine: true,
      deleted: false,
      failed: false,
      kind: "text",
      replyTo: opts.replyTo ?? null,
      linkPreview: sealedPreview,
      delivered: dto.delivered ?? false,
      read: dto.read ?? false,
    };
    rememberPreview(me, peer, msg);
    return msg;
  });
}

/**
 * Sends a text message whose link preview has a large picture: the picture is encrypted and
 * uploaded like a photo, and the message goes out as a `t: "link"` media message — the same
 * shape iOS `deliverLinkWithImage` sends, and a build without link support shows it as a photo
 * with the text as its caption. The recipient downloads the picture from Shroud, never from
 * the website.
 */
export async function sendLinkWithImage(opts: {
  token: string;
  me: string;
  peerUserId: string;
  material: IdentityMaterial;
  text: string;
  replyTo?: ReplyRef | null;
  /** Preview with its thumbnail (dropped here: the blob is the picture). */
  preview: LinkPreview;
  image: Uint8Array;
  width: number;
  height: number;
  /** Base64 JPEG drawn blurred until the picture is in. */
  placeholder: string | null;
  clientMessageId?: string;
}): Promise<ChatMessage> {
  const { key, sealed } = await sealFile(opts.image);
  const upload = await api.createMediaUpload(opts.token, sealed.byteLength);
  await api.putMediaContent(opts.token, upload.media_object_id, sealed);
  const placeholder =
    opts.placeholder && Math.floor((opts.placeholder.length * 3) / 4) <= MAX_THUMB_BYTES ? opts.placeholder : null;
  const payload: MediaPayload = withReply(
    {
      t: "link",
      mime: "image/jpeg",
      w: opts.width,
      h: opts.height,
      k: bytesToB64(key),
      s: opts.image.byteLength,
      c: opts.text,
      lp: linkPreviewWire({ ...opts.preview, thumbnail: null }),
      ...(placeholder ? { th: placeholder } : {}),
    },
    opts.replyTo,
  );
  return sendMediaEnvelope(opts, payload, upload.media_object_id, "link preview", (id) => {
    void cacheSealedImage(id, sealed);
  });
}

export async function sendVoice(opts: {
  token: string;
  me: string;
  peerUserId: string;
  material: IdentityMaterial;
  take: {
    data: Uint8Array;
    mime: string;
    durationMs: number;
    waveform: number[];
    transcript?: string | null;
  };
  replyTo?: ReplyRef | null;
}): Promise<ChatMessage> {
  const peer = opts.peerUserId.toLowerCase();
  const me = opts.me.toLowerCase();
  return withPeerLock(peer, async () => {
    const { key, sealed } = await sealFile(opts.take.data);
    const upload = await api.createMediaUpload(opts.token, sealed.byteLength);
    await api.putMediaContent(opts.token, upload.media_object_id, sealed);
    const transcript = opts.take.transcript ? clampTranscript(opts.take.transcript) || null : null;
    const payload: MediaPayload = withReply(
      {
        t: "voice",
        mime: opts.take.mime || "audio/wav",
        w: 0,
        h: 0,
        k: bytesToB64(key),
        d: opts.take.durationMs,
        wf: encodeWaveform(opts.take.waveform),
        s: opts.take.data.byteLength,
        // Sealed like the iPhone does, so the recipient never has to transcribe it.
        ...(transcript ? { c: transcript } : {}),
      },
      opts.replyTo,
    );
    const peerPub = await peerIdentityForSending(opts.token, peer);
    let plaintext = utf8(JSON.stringify(payload));
    if (plaintext.byteLength > MAX_MEDIA_PAYLOAD_PLAINTEXT_BYTES && payload.c) {
      delete payload.c;
      plaintext = utf8(JSON.stringify(payload));
    }
    let envelope = await sealMessage({
      plaintext,
      peerUserId: peer,
      ourUserId: me,
      ourPrivate: opts.material.agreementPrivate,
      ourIdentityPublic: opts.material.agreementPublic,
      peerIdentityPublic: peerPub,
    });
    if (envelope.byteLength > MAX_SEALED_ENVELOPE_BYTES && payload.c) {
      delete payload.c;
      plaintext = utf8(JSON.stringify(payload));
      envelope = await sealMessage({
        plaintext,
        peerUserId: peer,
        ourUserId: me,
        ourPrivate: opts.material.agreementPrivate,
        ourIdentityPublic: opts.material.agreementPublic,
        peerIdentityPublic: peerPub,
      });
    }
    if (envelope.byteLength > MAX_SEALED_ENVELOPE_BYTES) {
      throw new Error("Media message is too large to send. Try a shorter voice note.");
    }
    const clientId = crypto.randomUUID();
    const dto = await api.sendMessage(opts.token, {
      peer_user_id: peer,
      client_message_id: clientId,
      content_type: "media",
      ciphertext: envelopeToWireB64(envelope),
      media_object_id: upload.media_object_id,
    });
    const kept = await keptMediaSend({
      dto,
      uploadedMediaObjectId: upload.media_object_id,
      thisAttemptJson: JSON.stringify(payload),
      openKept: (ciphertext) => openOwnEnvelope(ciphertext, peer, me, opts.material),
    });
    if (kept.payloadJson != null) savePlaintext(dto.id, dto.sender_user_id, kept.payloadJson);
    await saveMediaBlob(dto.id, opts.take.data);
    const shown = kept.payloadJson ? parseMediaPayload(kept.payloadJson) : null;
    const msg = messageFromMediaPayload(
      {
        id: dto.id,
        senderUserId: dto.sender_user_id,
        createdAt: dto.created_at,
        isMine: true,
        deleted: false,
        failed: false,
        delivered: dto.delivered ?? false,
        read: dto.read ?? false,
      },
      shown ?? payload,
      kept.mediaObjectId,
    );
    if (!shown) msg.mediaKey = null;
    rememberPreview(me, peer, msg);
    return msg;
  });
}

/**
 * Encrypts, uploads and sends one photo (same wire format as iOS `finishImageSend`).
 * The upload runs outside the peer lock — only sealing the envelope has to wait its
 * turn — so a slow upload never holds up decrypting what the peer sends meanwhile.
 */
export async function sendImage(opts: {
  token: string;
  me: string;
  peerUserId: string;
  material: IdentityMaterial;
  image: PreparedImage;
  caption?: string | null;
  /** Quote sealed with the photo (first item of an album only, as on iOS). */
  replyTo?: ReplyRef | null;
  /** Idempotency key: the optimistic bubble's id, so a replayed send can't land twice. */
  clientMessageId?: string;
  onProgress?: TransferProgress;
}): Promise<ChatMessage> {
  const { image } = opts;
  const caption = opts.caption?.trim() || null;

  const { key, sealed } = await sealFile(image.bytes);
  const upload = await api.createMediaUpload(opts.token, sealed.byteLength);
  await api.putMediaContent(opts.token, upload.media_object_id, sealed, opts.onProgress);

  const thumb = image.thumb && image.thumb.byteLength <= MAX_THUMB_BYTES ? bytesToB64(image.thumb) : null;
  const payload: MediaPayload = withReply(
    {
      t: "image",
      mime: image.mime,
      w: image.width,
      h: image.height,
      k: bytesToB64(key),
      s: image.bytes.byteLength,
      ...(caption ? { c: caption } : {}),
      ...(thumb ? { th: thumb } : {}),
    },
    opts.replyTo,
  );
  return sendMediaEnvelope(opts, payload, upload.media_object_id, "photo", (id) => {
    void cacheSealedImage(id, sealed);
  });
}

/**
 * Encrypts, uploads and sends one converted clip (same wire format as iOS
 * `finishVideoSend`: `t: "video"`, the length in `d`). As with photos, only
 * sealing the envelope waits for the peer lock.
 */
export async function sendVideo(opts: {
  token: string;
  me: string;
  peerUserId: string;
  material: IdentityMaterial;
  video: EncodedVideo;
  caption?: string | null;
  /** Quote sealed with the clip (first item of an album only, as on iOS). */
  replyTo?: ReplyRef | null;
  /** Idempotency key: the optimistic bubble's id, so a replayed send can't land twice. */
  clientMessageId?: string;
  onProgress?: TransferProgress;
  /** Every byte is up; sealing and sending the envelope remain. */
  onUploaded?: () => void;
}): Promise<ChatMessage> {
  const { video } = opts;
  const caption = opts.caption?.trim() || null;

  const { key, sealed } = await sealFile(video.bytes);
  const upload = await api.createMediaUpload(opts.token, sealed.byteLength);
  await api.putMediaContent(opts.token, upload.media_object_id, sealed, opts.onProgress);
  opts.onUploaded?.();

  const thumb = video.thumb && video.thumb.byteLength <= MAX_THUMB_BYTES ? bytesToB64(video.thumb) : null;
  const payload: MediaPayload = withReply(
    {
      t: "video",
      mime: video.mime,
      w: video.width,
      h: video.height,
      k: bytesToB64(key),
      d: video.durationMs,
      s: video.bytes.byteLength,
      ...(caption ? { c: caption } : {}),
      ...(thumb ? { th: thumb } : {}),
    },
    opts.replyTo,
  );
  return sendMediaEnvelope(opts, payload, upload.media_object_id, "video", (id) => {
    void cacheSealedVideo(id, sealed);
    if (video.poster) void cacheSealedPoster(id, key, video.poster);
  });
}

/** A file already sealed as SHRF1 under its own key: what a retry uploads again, unchanged. */
export type SealedFile = { blob: Blob; key: Uint8Array };

/**
 * What an audio file's sender read from it (docs/file-sharing.md §11.2): `d`, `ti`, `ar`, and the
 * cover as `th` with its edge as `w`/`h`. Matches `AudioSendInfo` (media/audioSendPreview.ts).
 */
export type AudioSendFields = {
  durationMs: number | null;
  title: string | null;
  artist: string | null;
  thumb: Uint8Array | null;
  width: number;
  height: number;
};

/** What a PDF's sender read from it (docs/file-sharing.md §10.1): the `th` JPEG and its size, and `pg`. */
export type PdfSendPreview = { thumb: Uint8Array | null; width: number; height: number; pages: number };

/**
 * Uploads one sealed file and sends it as a `t: "file"` media message (docs/file-sharing.md
 * §1). `name` is already cleaned; the MIME sealed is the type table's, and the upload itself is
 * `application/octet-stream`, so the server never learns what kind of file it holds. As with
 * photos, only sealing the envelope waits for the peer lock.
 */
export async function sendFile(opts: {
  token: string;
  me: string;
  peerUserId: string;
  material: IdentityMaterial;
  sealed: SealedFile;
  name: string;
  /** Plaintext size, sealed as `s`; the receiver checks the blob against it. */
  size: number;
  caption?: string | null;
  /**
   * A PDF's sealed preview (`th`, `w`, `h`) and page count (`pg`), docs/file-sharing.md §10.1;
   * still being read while the file uploads, so it may come as a promise.
   */
  pdf?: PdfSendPreview | Promise<PdfSendPreview | null> | null;
  /** An audio file's §11.2 fields, read within 2 s of the pick (may still be coming). */
  audio?: AudioSendFields | Promise<AudioSendFields | null> | null;
  /** Quote sealed with the file (first of a batch only). */
  replyTo?: ReplyRef | null;
  /** Idempotency key: the optimistic bubble's id, so a replayed send can't land twice. */
  clientMessageId?: string;
  onProgress?: TransferProgress;
  /** Every byte is up; sealing and sending the envelope remain. */
  onUploaded?: () => void;
}): Promise<ChatMessage> {
  const type = fileTypeOf(opts.name);
  if (!type) throw new Error(unsupportedRefusal(opts.name));
  const caption = opts.caption?.trim() || null;

  const upload = await api.createMediaUpload(opts.token, opts.sealed.blob.size);
  await api.putMediaContent(opts.token, upload.media_object_id, opts.sealed.blob, opts.onProgress);
  opts.onUploaded?.();

  const pdf = type.ext === "pdf" ? await Promise.resolve(opts.pdf ?? null).catch(() => null) : null;
  const audio = type.category === "audio" ? await Promise.resolve(opts.audio ?? null).catch(() => null) : null;
  const picture = pdf ?? audio;
  const thumb = picture?.thumb && picture.thumb.byteLength <= MAX_THUMB_BYTES ? bytesToB64(picture.thumb) : null;
  const pages = pdf && Number.isInteger(pdf.pages) && pdf.pages >= 1 ? pdf.pages : null;
  const durationMs = audio ? audioDurationOf(audio.durationMs) : null;
  const title = audio ? cleanTagText(audio.title) : null;
  const artist = audio ? cleanTagText(audio.artist) : null;

  const payload: MediaPayload = withReply(
    {
      t: "file",
      n: opts.name,
      mime: type.mime,
      w: thumb ? picture!.width : 0,
      h: thumb ? picture!.height : 0,
      k: bytesToB64(opts.sealed.key),
      s: opts.size,
      ...(caption ? { c: caption } : {}),
      ...(thumb ? { th: thumb } : {}),
      ...(pages ? { pg: pages } : {}),
      ...(durationMs ? { d: durationMs } : {}),
      ...(title ? { ti: title } : {}),
      ...(artist ? { ar: artist } : {}),
    },
    opts.replyTo,
  );
  // Nothing to keep: the web caches no file (§8); the sender's tab still holds the original.
  return sendMediaEnvelope(opts, payload, upload.media_object_id, "file", () => {});
}


/**
 * The row an idempotent media send should remember.
 *
 * A retried `client_message_id` comes back as the first attempt's message. That row's blob
 * and file key are not this upload, which the server leaves unlinked and deletes after an
 * hour. Caching this attempt's key would pair the server's blob with the wrong key.
 * `payloadJson` is null when the kept envelope cannot be opened: cache nothing then.
 */
export async function keptMediaSend(opts: {
  dto: WireMessage;
  uploadedMediaObjectId: string;
  thisAttemptJson: string;
  openKept: (ciphertext: string) => Promise<string>;
}): Promise<{ mediaObjectId: string; payloadJson: string | null }> {
  const stored = opts.dto.media_object_id ?? opts.uploadedMediaObjectId;
  if (stored.toLowerCase() === opts.uploadedMediaObjectId.toLowerCase()) {
    return { mediaObjectId: stored, payloadJson: opts.thisAttemptJson };
  }
  if (!opts.dto.ciphertext) return { mediaObjectId: stored, payloadJson: null };
  try {
    const opened = await opts.openKept(opts.dto.ciphertext);
    if (!parseMediaPayload(opened)) return { mediaObjectId: stored, payloadJson: null };
    return { mediaObjectId: stored, payloadJson: opened };
  } catch (err) {
    if (err instanceof Error && err.name === "AbortError") throw err;
    return { mediaObjectId: stored, payloadJson: null };
  }
}

/** Opens our own media envelope (the self box) so a replay can cache the row the server kept. */
async function openOwnEnvelope(
  ciphertext: string,
  peer: string,
  me: string,
  material: IdentityMaterial,
): Promise<string> {
  const plain = await openMessage({
    envelopeData: wireB64ToEnvelope(ciphertext),
    peerUserId: peer,
    ourUserId: me,
    ourPrivate: material.agreementPrivate,
    ourIdentityPublic: material.agreementPublic,
    senderIdentityPublic: material.agreementPublic,
    asSender: true,
  });
  return utf8decode(plain);
}

/** Seals a media payload for the peer and sends it; `keep` stores the sent file under the server's id. */
async function sendMediaEnvelope(
  opts: {
    token: string;
    me: string;
    peerUserId: string;
    material: IdentityMaterial;
    clientMessageId?: string;
  },
  payload: MediaPayload,
  mediaObjectId: string,
  noun: "photo" | "video" | "file" | "link preview",
  keep: (messageId: string) => void,
): Promise<ChatMessage> {
  const peer = opts.peerUserId.toLowerCase();
  const me = opts.me.toLowerCase();
  return withPeerLock(peer, async () => {
    const peerPub = await peerIdentityForSending(opts.token, peer);
    const seal = () =>
      sealMessage({
        plaintext: utf8(JSON.stringify(payload)),
        peerUserId: peer,
        ourUserId: me,
        ourPrivate: opts.material.agreementPrivate,
        ourIdentityPublic: opts.material.agreementPublic,
        peerIdentityPublic: peerPub,
      });
    // Same budget as iOS: the preview is the first thing to go when the envelope runs long.
    if (utf8(JSON.stringify(payload)).byteLength > MAX_MEDIA_PAYLOAD_PLAINTEXT_BYTES) delete payload.th;
    let envelope = await seal();
    if (envelope.byteLength > MAX_SEALED_ENVELOPE_BYTES && payload.th) {
      delete payload.th;
      envelope = await seal();
    }
    if (envelope.byteLength > MAX_SEALED_ENVELOPE_BYTES) {
      throw new Error(`That caption is too long to send with a ${noun}.`);
    }
    const dto = await api.sendMessage(opts.token, {
      peer_user_id: peer,
      client_message_id: opts.clientMessageId ?? crypto.randomUUID(),
      content_type: "media",
      ciphertext: envelopeToWireB64(envelope),
      media_object_id: mediaObjectId,
    });
    const kept = await keptMediaSend({
      dto,
      uploadedMediaObjectId: mediaObjectId,
      thisAttemptJson: JSON.stringify(payload),
      openKept: (ciphertext) => openOwnEnvelope(ciphertext, peer, me, opts.material),
    });
    if (kept.payloadJson != null) savePlaintext(dto.id, dto.sender_user_id, kept.payloadJson);
    keep(dto.id);
    const shown = kept.payloadJson ? parseMediaPayload(kept.payloadJson) : null;
    const msg = messageFromMediaPayload(
      {
        id: dto.id,
        senderUserId: dto.sender_user_id,
        createdAt: dto.created_at,
        isMine: true,
        deleted: false,
        failed: false,
        delivered: dto.delivered ?? false,
        read: dto.read ?? false,
      },
      shown ?? payload,
      kept.mediaObjectId,
    );
    if (!shown) msg.mediaKey = null;
    rememberPreview(me, peer, msg);
    return msg;
  });
}

/**
 * Keep a transcript made on this device and share it as an annotation, so the
 * other side — and our other devices, iPhone included — show it without
 * transcribing again. Best effort: the bubble already has the text locally.
 */
export async function shareTranscript(opts: {
  token: string;
  me: string;
  peerUserId: string;
  messageId: string;
  transcript: string;
  material: IdentityMaterial;
}): Promise<void> {
  const text = clampTranscript(opts.transcript);
  if (!text || opts.messageId.startsWith("pending:")) return;
  const annotation: Annotation = { t: "transcript", r: opts.messageId.toLowerCase(), c: text };
  noteSharedTranscript(annotation);
  previewSharedTranscript(opts.me, opts.peerUserId, annotation);

  const peer = opts.peerUserId.toLowerCase();
  const me = opts.me.toLowerCase();
  await withPeerLock(peer, async () => {
    const peerPub = await peerIdentityForSending(opts.token, peer);
    const envelope = await sealMessage({
      plaintext: utf8(JSON.stringify(annotation)),
      peerUserId: peer,
      ourUserId: me,
      ourPrivate: opts.material.agreementPrivate,
      ourIdentityPublic: opts.material.agreementPublic,
      peerIdentityPublic: peerPub,
    });
    const dto = await api.sendMessage(opts.token, {
      peer_user_id: peer,
      client_message_id: crypto.randomUUID(),
      content_type: "annotation",
      ciphertext: envelopeToWireB64(envelope),
    });
    savePlaintext(dto.id, dto.sender_user_id, JSON.stringify(annotation));
  });
}

/** A bubble that never reached the server: an optimistic send, or one that failed before it did. */
export function isUnsent(message: ChatMessage): boolean {
  return message.pending === true || message.id.startsWith("pending:");
}

/**
 * The quote a reply to `message` should carry. Null while the message is still optimistic —
 * its id is local, so the other side could never resolve it.
 */
export function replyRefFor(message: ChatMessage): ReplyRef | null {
  if (message.pending || message.failed || message.deleted) return null;
  if (message.id.startsWith("pending:")) return null;
  // An audio file quotes as `audio`, by its display title (docs/file-sharing.md §1, §11.2).
  const audio = message.kind === "file" && isAudioFileName(message.fileName);
  const kind = message.kind === "annotation" ? "text" : audio ? "audio" : message.kind;
  // Media bubbles keep a stand-in label in `text`; only a real caption is worth sealing.
  const snippet =
    kind === "voice"
      ? ""
      : kind === "audio"
        ? audioDisplayTitle(message.audioTitle, message.audioArtist, message.fileName ?? "")
      : kind === "file"
        ? message.fileName ?? ""
        : kind === "image" || kind === "video"
          ? message.caption?.trim() || ""
          : message.text;
  return {
    id: message.id.toLowerCase(),
    senderUserId: message.senderUserId.toLowerCase(),
    kind,
    snippet: clampSnippet(snippet),
  };
}

/**
 * A message its author unsent: nothing of it survives but the fact that it existed — not its
 * words, its media key, nor the quote it carried (iOS `tombstoneMessage` does the same).
 */
export function tombstone(message: ChatMessage): ChatMessage {
  return {
    ...message,
    text: "Message deleted",
    deleted: true,
    caption: null,
    transcript: null,
    thumbnail: null,
    fileName: null,
    pageCount: null,
    audioTitle: null,
    audioArtist: null,
    audioDurationMs: null,
    mediaKey: null,
    mediaObjectId: null,
    replyTo: null,
    linkPreview: null,
    reactions: [],
  };
}

/** Forgets every local copy of a deleted message: its decrypted body and any media bytes. */
export function forgetMessageLocally(messageId: string): void {
  forgetPlaintext(messageId);
  void deleteMediaBlobs(messageId);
}

/**
 * Tombstones a page brought back. A message deleted without this tab being told (a chat
 * deleted for both, a deleted account, an event missed while offline) still has its body and
 * media cached here, never shown again but kept; they go now, once per tab.
 */
function forgetTombstones(dtos: WireMessage[]): void {
  const ids = dtos.filter((dto) => dto.deleted_for_everyone && !isWithdrawn(dto.id)).map((dto) => dto.id);
  if (ids.length === 0) return;
  for (const id of ids) {
    releaseImage(id);
    releaseVideo(id);
    releaseVoice(id);
    releaseFile(id);
    forgetPlaintext(id);
  }
  void deleteMediaBlobs(...ids);
}

/**
 * Points the chat-list line at whatever is newest in `thread` — after a delete, the line must
 * stop quoting a message that is gone. `thread` is the chat with the delete already applied.
 */
export function rewritePreview(me: string, peerUserId: string, thread: ChatMessage[]): void {
  const newest = [...thread].reverse().find((m) => m.kind !== "annotation");
  replacePreview(
    me,
    peerUserId,
    newest
      ? {
          id: newest.id,
          text: previewCopy(newest),
          at: newest.createdAt,
          isMine: newest.isMine,
          failed: newest.failed,
        }
      : null,
  );
}
