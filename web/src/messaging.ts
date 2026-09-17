import { api, type WireMessage } from "./api/client";
import { b64ToBytes, utf8, utf8decode } from "./crypto/bytes";
import type { IdentityMaterial } from "./crypto/identity";
import { envelopeToWireB64, openMessage, sealMessage, wireB64ToEnvelope } from "./crypto/messageCrypto";
import { loadPlaintext, savePlaintext } from "./crypto/plaintextCache";

export type ChatMessage = {
  id: string;
  senderUserId: string;
  text: string;
  createdAt: string;
  isMine: boolean;
  deleted: boolean;
  failed: boolean;
  kind: "text" | "media";
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

async function decodeOne(
  dto: WireMessage,
  me: string,
  peerUserId: string,
  token: string,
  material: IdentityMaterial,
): Promise<ChatMessage> {
  const isMine = dto.sender_user_id.toLowerCase() === me.toLowerCase();
  const base = {
    id: dto.id,
    senderUserId: dto.sender_user_id,
    createdAt: dto.created_at,
    isMine,
    deleted: dto.deleted_for_everyone,
    failed: false,
    kind: dto.content_type === "media" ? ("media" as const) : ("text" as const),
  };
  if (dto.deleted_for_everyone) {
    return { ...base, text: "Message deleted" };
  }
  const cached = loadPlaintext(dto.id);
  if (cached != null) {
    return { ...base, text: dto.content_type === "media" ? "Photo" : cached };
  }
  if (!dto.ciphertext) {
    return { ...base, text: dto.content_type === "media" ? "Photo" : "[Unable to decrypt]", failed: true };
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
    const text = dto.content_type === "media" ? "Photo" : utf8decode(plain);
    savePlaintext(dto.id, dto.content_type === "media" ? "[media]" : text);
    return { ...base, text };
  } catch {
    return {
      ...base,
      text: dto.content_type === "media" ? "Photo" : "[Unable to decrypt]",
      failed: true,
    };
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
      out.push(await decodeOne(dto, me, peer, token, material));
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
    return {
      id: dto.id,
      senderUserId: dto.sender_user_id,
      text: opts.text,
      createdAt: dto.created_at,
      isMine: true,
      deleted: false,
      failed: false,
      kind: "text",
    };
  });
}
