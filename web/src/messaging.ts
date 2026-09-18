import { api, type Conversation, type WireMessage } from "./api/client";
import { aesGcmOpen, sealFile } from "./crypto/aes";
import { b64ToBytes, bytesToB64, utf8, utf8decode } from "./crypto/bytes";
import type { IdentityMaterial } from "./crypto/identity";
import { loadMediaBlob, saveMediaBlob } from "./crypto/mediaCache";
import {
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

export type ChatKind = "text" | "image" | "voice" | "video";

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
  delivered?: boolean;
  read?: boolean;
};

const peerKeyCache = new Map<string, Uint8Array>();
const peerLocks = new Map<string, Promise<unknown>>();

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
      if (knownIds.has(dto.id)) continue;
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
    const transcript = payload.c?.trim() || null;
    return {
      ...base,
      kind: "voice",
      text: transcript || "Voice message",
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
  if (msg.kind === "voice") return msg.transcript?.trim() || "Voice message";
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
  if (dto.deleted_for_everyone) {
    const msg: ChatMessage = { ...base, text: "Message deleted" };
    rememberPreview(me, peerUserId, msg);
    return msg;
  }
  const cached = loadPlaintext(dto.id);
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
    const msg: ChatMessage = {
      ...base,
      text: isMedia ? "Media" : "[Unable to decrypt]",
      failed: true,
    };
    rememberPreview(me, peerUserId, msg);
    return msg;
  }
}

function rememberPreview(me: string, peerUserId: string, msg: ChatMessage): void {
  savePreview(me, peerUserId, {
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
      const res = await api.listMessages(token, peer.id, { limit: "1" });
      const dto = res.messages[0];
      if (!dto) continue;
      const mine = dto.sender_user_id.toLowerCase() === me.toLowerCase();
      const cached = loadPlaintext(dto.id);
      if (cached && cached !== "[media]") {
        const payload = dto.content_type === "media" ? parseMediaPayload(cached) : null;
        rememberPreview(me, peer.id, {
          id: dto.id,
          senderUserId: dto.sender_user_id,
          text: payload
            ? payload.t === "voice"
              ? payload.c?.trim() || "Voice message"
              : payload.t === "video"
                ? payload.c?.trim() || "Video"
                : payload.c?.trim() || "Photo"
            : cached,
          createdAt: dto.created_at,
          isMine: mine,
          deleted: Boolean(dto.deleted_for_everyone),
          failed: false,
          kind: kindFromPayload(payload, dto.content_type === "media"),
          transcript: payload?.t === "voice" ? payload.c?.trim() || null : null,
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
    return out;
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
  };
}): Promise<ChatMessage> {
  const peer = opts.peerUserId.toLowerCase();
  const me = opts.me.toLowerCase();
  return withPeerLock(peer, async () => {
    const { key, sealed } = await sealFile(opts.take.data);
    const upload = await api.createMediaUpload(opts.token, sealed.byteLength);
    await api.putMediaContent(opts.token, upload.media_object_id, sealed);
    const payload: MediaPayload = {
      t: "voice",
      mime: opts.take.mime || "audio/wav",
      w: 0,
      h: 0,
      k: bytesToB64(key),
      d: opts.take.durationMs,
      wf: encodeWaveform(opts.take.waveform),
      s: opts.take.data.byteLength,
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
      text: "Voice message",
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
      delivered: dto.delivered ?? false,
      read: dto.read ?? false,
    };
    rememberPreview(me, peer, msg);
    return msg;
  });
}
