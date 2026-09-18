import { api, type Conversation, type WireMessage } from "./api/client";
import { aesGcmOpen, sealFile } from "./crypto/aes";
import { b64ToBytes, bytesToB64, utf8, utf8decode } from "./crypto/bytes";
import type { IdentityMaterial } from "./crypto/identity";
import { loadMediaBlob, saveMediaBlob } from "./crypto/mediaCache";
import {
  clampTranscript,
  decodeWaveform,
  encodeWaveform,
  isVoicePayload,
  parseMediaPayload,
  type MediaPayload,
} from "./crypto/mediaPayload";
import { envelopeToWireB64, openMessage, sealMessage, wireB64ToEnvelope } from "./crypto/messageCrypto";
import {
  loadPlaintext,
  loadPreview,
  savePlaintext,
  savePreview,
} from "./crypto/plaintextCache";

/** `annotation` never reaches the thread: `applyAnnotations` folds it into its target. */
export type ChatKind = "text" | "image" | "voice" | "video" | "annotation";

/**
 * Sealed JSON inside `content_type = annotation`: data one participant attaches
 * to an earlier message. Today that is a voice transcript made on the
 * recipient's device and shared back (matches iOS `MessageAnnotation`).
 */
export type Annotation = { t: "transcript"; r: string; c: string };

/** Label for a voice note without a transcript (bubble text and chat preview). */
const VOICE_LABEL = "Voice message";

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
  /** Optimistic bubble shown until the server hands back a real id. */
  pending?: boolean;
  /** Set on `kind === "annotation"`; null when it could not be read. */
  annotation?: Annotation | null;
  delivered?: boolean;
  read?: boolean;
};

const peerKeyCache = new Map<string, Uint8Array>();
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

export async function peerIdentityPublic(
  token: string,
  peerUserId: string,
): Promise<Uint8Array> {
  const cached = peerKeyCache.get(peerUserId.toLowerCase());
  if (cached) return cached;
  const res = await api.peerIdentity(token, peerUserId);
  const key = b64ToBytes(res.identity_key);
  peerKeyCache.set(peerUserId.toLowerCase(), key);
  return key;
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
  return withPeerLock(peerUserId, () => decodeIncoming(dto, me, peerUserId, token, material));
}

export async function fetchLatest(
  token: string,
  me: string,
  peerUserId: string,
  material: IdentityMaterial,
  knownIds: Set<string>,
): Promise<ChatMessage[]> {
  const peer = peerUserId.toLowerCase();
  return withPeerLock(peer, async () => {
    const res = await api.listMessages(token, peer);
    const chronological = [...res.messages].reverse();
    const out: ChatMessage[] = [];
    for (const dto of chronological) {
      if (knownIds.has(dto.id) || seenAnnotations.has(dto.id.toLowerCase())) continue;
      out.push(await decodeIncoming(dto, me, peer, token, material));
    }
    return out;
  });
}

function kindFromPayload(payload: MediaPayload | null, isMedia: boolean): ChatKind {
  if (!isMedia) return "text";
  if (payload?.t === "voice") return "voice";
  if (payload?.t === "video") return "video";
  return "image";
}

function messageFromMediaPayload(
  base: Omit<ChatMessage, "text" | "kind">,
  payload: MediaPayload,
  mediaObjectId: string | null | undefined,
): ChatMessage {
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
  if (payload.t === "video") {
    return {
      ...base,
      kind: "video",
      text: caption || "Video",
      mediaObjectId: mediaObjectId ?? null,
    };
  }
  return {
    ...base,
    kind: "image",
    text: caption || "Photo",
    mediaObjectId: mediaObjectId ?? null,
  };
}

function previewCopy(msg: ChatMessage): string {
  if (msg.deleted) return "Message deleted";
  if (msg.failed) return "Encrypted message";
  if (msg.kind === "voice") return msg.transcript?.trim() || VOICE_LABEL;
  if (msg.kind === "video") return msg.text === "Video" ? "Video" : msg.text;
  if (msg.kind === "image") return msg.text === "Photo" ? "Photo" : msg.text;
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
  const cached = loadPlaintext(dto.id);
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
      const msg: ChatMessage = { ...base, text: cached };
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
      savePlaintext(dto.id, decoded);
      return annotationFrom(decoded);
    }
    if (isMedia) {
      savePlaintext(dto.id, decoded);
      const payload = parseMediaPayload(decoded);
      const msg = payload
        ? messageFromMediaPayload(base, payload, dto.media_object_id)
        : { ...base, text: "Media", kind: "image" as const };
      rememberPreview(me, peerUserId, msg);
      return msg;
    }
    savePlaintext(dto.id, decoded);
    const msg: ChatMessage = { ...base, text: decoded };
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
    await saveMediaBlob(message.id, audio);
    return audio;
  })().catch(() => null);
  voiceLoads.set(key, task);
  void task.finally(() => {
    if (voiceLoads.get(key) === task) voiceLoads.delete(key);
  });
  return task;
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
): Promise<void> {
  const pending = peers.filter((p) => {
    if (!p.lastMessageAt) return false;
    const have = loadPreview(me, p.id);
    if (!have) return true;
    const haveT = Date.parse(have.at) || 0;
    const lastT = Date.parse(p.lastMessageAt) || 0;
    return lastT - haveT > 1000;
  });
  for (const peer of pending) {
    try {
      // A few, so a transcript shared after the newest message can't stand in for it.
      const res = await api.listMessages(token, peer.id, { limit: "5" });
      const dto = res.messages.find((m) => m.content_type !== "annotation");
      if (!dto) continue;
      const mine = dto.sender_user_id.toLowerCase() === me.toLowerCase();
      const cached = loadPlaintext(dto.id);
      if (cached && cached !== "[media]") {
        const payload = dto.content_type === "media" ? parseMediaPayload(cached) : null;
        let transcript = payload?.t === "voice" ? payload.c?.trim() || null : null;
        if (payload?.t === "voice" && !transcript) {
          for (const other of res.messages) {
            if (other.content_type !== "annotation") continue;
            const raw = loadPlaintext(other.id);
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
            ? payload.t === "voice"
              ? transcript || VOICE_LABEL
              : payload.t === "video"
                ? payload.c?.trim() || "Video"
                : payload.c?.trim() || "Photo"
            : cached,
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

export async function loadHistory(
  token: string,
  me: string,
  peerUserId: string,
  material: IdentityMaterial,
): Promise<ChatMessage[]> {
  const peer = peerUserId.toLowerCase();
  return withPeerLock(peer, async () => {
    const pages: WireMessage[][] = [];
    let beforeAt: string | undefined;
    let beforeId: string | undefined;
    for (let i = 0; i < 40; i++) {
      const extra: Record<string, string> = {};
      if (beforeAt && beforeId) {
        extra.before_created_at = beforeAt;
        extra.before_id = beforeId.toLowerCase();
      }
      const res = await api.listMessages(token, peer, extra);
      pages.push(res.messages);
      const oldest = res.messages[res.messages.length - 1];
      const full = res.messages.length >= 100;
      if ((res.has_more || full) && oldest) {
        beforeAt = oldest.created_at;
        beforeId = oldest.id;
      } else {
        break;
      }
    }
    const chronological: WireMessage[] = [];
    for (let i = pages.length - 1; i >= 0; i--) {
      chronological.push(...[...pages[i]].reverse());
    }
    const out: ChatMessage[] = [];
    for (const dto of chronological) {
      out.push(await decodeIncoming(dto, me, peer, token, material));
    }
    return applyAnnotations(out);
  });
}

export async function sendText(opts: {
  token: string;
  me: string;
  peerUserId: string;
  text: string;
  material: IdentityMaterial;
}): Promise<ChatMessage> {
  const peer = opts.peerUserId.toLowerCase();
  const me = opts.me.toLowerCase();
  return withPeerLock(peer, async () => {
    const peerPub = await peerIdentityPublic(opts.token, peer);
    const envelope = await sealMessage({
      plaintext: utf8(opts.text),
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
    savePlaintext(dto.id, opts.text);
    const msg: ChatMessage = {
      id: dto.id,
      senderUserId: dto.sender_user_id,
      text: opts.text,
      createdAt: dto.created_at,
      isMine: true,
      deleted: false,
      failed: false,
      kind: "text",
      delivered: dto.delivered ?? false,
      read: dto.read ?? false,
    };
    rememberPreview(me, peer, msg);
    return msg;
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
}): Promise<ChatMessage> {
  const peer = opts.peerUserId.toLowerCase();
  const me = opts.me.toLowerCase();
  return withPeerLock(peer, async () => {
    const { key, sealed } = await sealFile(opts.take.data);
    const upload = await api.createMediaUpload(opts.token, sealed.byteLength);
    await api.putMediaContent(opts.token, upload.media_object_id, sealed);
    const transcript = opts.take.transcript ? clampTranscript(opts.take.transcript) || null : null;
    const payload: MediaPayload = {
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
    };
    const peerPub = await peerIdentityPublic(opts.token, peer);
    const envelope = await sealMessage({
      plaintext: utf8(JSON.stringify(payload)),
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
      content_type: "media",
      ciphertext: envelopeToWireB64(envelope),
      media_object_id: upload.media_object_id,
    });
    savePlaintext(dto.id, JSON.stringify(payload));
    await saveMediaBlob(dto.id, opts.take.data);
    const msg: ChatMessage = {
      id: dto.id,
      senderUserId: dto.sender_user_id,
      text: transcript || VOICE_LABEL,
      createdAt: dto.created_at,
      isMine: true,
      deleted: false,
      failed: false,
      kind: "voice",
      mediaObjectId: upload.media_object_id,
      voiceDurationMs: opts.take.durationMs,
      voiceWaveform: opts.take.waveform,
      mediaKey: payload.k,
      mime: payload.mime,
      transcript,
      delivered: dto.delivered ?? false,
      read: dto.read ?? false,
    };
    rememberPreview(me, peer, msg);
    return msg;
  });
}
