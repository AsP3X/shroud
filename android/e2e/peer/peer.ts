/*
 * The scripted web peer of the Android engine e2e (00-plan §2.3 W2-INT, §6.3).
 *
 * One web account, run by the web client's own crypto and API modules (web/src/crypto/*,
 * web/src/api/client.ts, reply.ts, reactions.ts) with in-memory stores instead of the browser's:
 * what this peer seals and opens is byte for byte what the real web client seals and opens.
 * EngineE2eTest (androidTest, on the emulator) drives it over a small HTTP control API on the
 * host's loopback (the emulator reaches it as http://10.0.2.2:<port>).
 *
 * Run through android/e2e/engine-e2e.sh, which bundles this file with the web's esbuild (so the
 * web's extension-less TypeScript imports resolve) and starts it:
 *   node peer.mjs --api http://127.0.0.1:8080/api/v1 --port 8099
 *
 * Never logs keys, tokens or plaintext: the control API returns decrypted content to the test,
 * which is the point, but the console only says which route ran.
 */
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { createHash, randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";

/* ---- Browser stand-ins the web modules expect ------------------------------------------------ */

const args = new Map<string, string>();
for (let i = 2; i < process.argv.length - 1; i += 2) args.set(process.argv[i].replace(/^--/, ""), process.argv[i + 1]);
const API = args.get("api") ?? "http://127.0.0.1:8080/api/v1";
const PORT = Number(args.get("port") ?? "8099");
const PHOTO = args.get("photo") ?? "";

class MemoryStorage {
  private items = new Map<string, string>();
  get length() {
    return this.items.size;
  }
  key(index: number) {
    return [...this.items.keys()][index] ?? null;
  }
  getItem(key: string) {
    return this.items.get(key) ?? null;
  }
  setItem(key: string, value: string) {
    this.items.set(key, String(value));
  }
  removeItem(key: string) {
    this.items.delete(key);
  }
  clear() {
    this.items.clear();
  }
}
const g = globalThis as Record<string, unknown>;
g.localStorage = new MemoryStorage();
g.sessionStorage = new MemoryStorage();
g.window = { __SHROUD_CONFIG__: { apiBase: API }, location: { protocol: "http:", host: "127.0.0.1" } };

/* ---- The web client's modules ------------------------------------------------------------------ */

const { api } = await import("../../../web/src/api/client");
const { generateMnemonic } = await import("../../../web/src/crypto/bip39");
const { establish, putBundleRequest } = await import("../../../web/src/crypto/identity");
const { createVault } = await import("../../../web/src/crypto/vault");
const { sealMessage, openMessage, envelopeToWireB64, wireB64ToEnvelope } = await import("../../../web/src/crypto/messageCrypto");
const { peerIdentityForSending, peerIdentityPublic } = await import("../../../web/src/crypto/peerIdentity");
const { aesGcmOpen, sealFile } = await import("../../../web/src/crypto/aes");
const { b64ToBytes, bytesToB64, randomBytes, utf8, utf8decode } = await import("../../../web/src/crypto/bytes");
const { textWire, parseTextPayload } = await import("../../../web/src/reply");
const { parseMediaPayload } = await import("../../../web/src/crypto/mediaPayload");
const { openReaction, saveReaction } = await import("../../../web/src/reactions");

type Material = ReturnType<typeof establish>;
type Account = { token: string; userId: string; username: string; material: Material };
let account: Account | null = null;

function need(): Account {
  if (!account) throw new HttpError(409, "no account yet: POST /account first");
  return account;
}

class HttpError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

/* ---- Account ----------------------------------------------------------------------------------- */

async function createAccount(): Promise<Account> {
  const username = `webpeer_${randomUUID().replace(/-/g, "").slice(0, 12)}`;
  const session = await api.register(username, `Web peer passphrase ${randomUUID()}`);
  const words = generateMnemonic();
  const material = establish(words, session.user.id);
  await api.putBundle(session.token, putBundleRequest(material));
  // The vault opens with any PIN wrap; the peer never locks it.
  createVault(
    session.user.id,
    {
      secrets: { authKey: randomBytes(32), material: randomBytes(32), salt: randomBytes(16), iter: 1, length: 6 },
      pepper: randomBytes(32),
      guardId: randomUUID(),
    },
    material.historyKey,
  );
  account = { token: session.token, userId: session.user.id.toLowerCase(), username: session.user.username, material };
  return account;
}

/* ---- Sending ----------------------------------------------------------------------------------- */

async function seal(peer: string, plaintext: Uint8Array): Promise<string> {
  const me = need();
  const peerPub = await peerIdentityForSending(me.token, peer);
  const envelope = await sealMessage({
    plaintext,
    peerUserId: peer,
    ourUserId: me.userId,
    ourPrivate: me.material.agreementPrivate,
    ourIdentityPublic: me.material.agreementPublic,
    peerIdentityPublic: peerPub,
  });
  return envelopeToWireB64(envelope);
}

type ReplyBody = { id: string; senderUserId: string; kind: "text" | "image" | "video" | "voice"; snippet: string };
type PreviewBody = { url: string; title?: string; siteName?: string; summary?: string };

async function sendText(body: { peer: string; text: string; replyTo?: ReplyBody; linkPreview?: PreviewBody }) {
  const me = need();
  const peer = body.peer.toLowerCase();
  const reply = body.replyTo
    ? { id: body.replyTo.id.toLowerCase(), senderUserId: body.replyTo.senderUserId.toLowerCase(), kind: body.replyTo.kind, snippet: body.replyTo.snippet }
    : null;
  const preview = body.linkPreview
    ? {
        url: body.linkPreview.url,
        siteName: body.linkPreview.siteName ?? null,
        title: body.linkPreview.title ?? null,
        summary: body.linkPreview.summary ?? null,
        thumbnail: null,
        imageWidth: null,
        imageHeight: null,
        isVideo: false,
        showsAboveText: false,
      }
    : null;
  const { wire } = textWire(body.text, reply, preview);
  const dto = await api.sendMessage(me.token, {
    peer_user_id: peer,
    client_message_id: randomUUID(),
    content_type: "text",
    ciphertext: await seal(peer, utf8(wire)),
  });
  return { id: dto.id.toLowerCase() };
}

async function sendPhoto(body: { peer: string; caption?: string }) {
  const me = need();
  if (!PHOTO) throw new HttpError(500, "start the peer with --photo <jpeg>");
  const peer = body.peer.toLowerCase();
  const bytes = new Uint8Array(readFileSync(PHOTO));
  const { key, sealed } = await sealFile(bytes);
  const upload = await api.createMediaUpload(me.token, sealed.byteLength);
  await api.putMediaContent(me.token, upload.media_object_id, sealed);
  const payload = {
    t: "image",
    mime: "image/jpeg",
    w: 64,
    h: 48,
    k: bytesToB64(key),
    s: bytes.byteLength,
    ...(body.caption ? { c: body.caption } : {}),
  };
  const dto = await api.sendMessage(me.token, {
    peer_user_id: peer,
    client_message_id: randomUUID(),
    content_type: "media",
    ciphertext: await seal(peer, utf8(JSON.stringify(payload))),
    media_object_id: upload.media_object_id,
  });
  return { id: dto.id.toLowerCase(), sha256: sha256(bytes), size: bytes.byteLength };
}

async function react(body: { peer: string; messageId: string; emojis: string[] }) {
  const me = need();
  const peerPub = await peerIdentityForSending(me.token, body.peer.toLowerCase());
  // Our record as the server has it: the base the save builds on.
  const page = await api.listMessages(me.token, body.peer.toLowerCase());
  const wire = page.messages
    .find((m) => m.id.toLowerCase() === body.messageId.toLowerCase())
    ?.reactions?.find((r) => r.user_id.toLowerCase() === me.userId);
  const base = wire ? await openReaction(wire, me.userId, me.material, async () => peerPub) : null;
  const result = await saveReaction({
    token: me.token,
    me: me.userId,
    messageId: body.messageId.toLowerCase(),
    emojis: body.emojis,
    base,
    material: me.material,
    peerIdentityPublic: peerPub,
  });
  if ("changed" in result) throw new HttpError(409, "REACTION_CHANGED");
  return { saved: true };
}

async function deleteForEveryone(body: { messageId: string }) {
  await api.deleteMessage(need().token, body.messageId.toLowerCase(), "everyone");
  return { deleted: true };
}

async function acceptRequests() {
  const me = need();
  const { requests } = await api.contactRequests(me.token, "incoming");
  for (const request of requests) await api.acceptRequest(me.token, request.id);
  return { accepted: requests.length };
}

async function requestContact(body: { userId: string }) {
  await api.createContactRequest(need().token, body.userId.toLowerCase());
  return { requested: true };
}

/* ---- Reading ----------------------------------------------------------------------------------- */

function sha256(bytes: Uint8Array): string {
  return createHash("sha256").update(bytes).digest("hex");
}

/** Plaintext by message id, as the web's plaintext cache keeps it: a ratchet message opens once. */
const opened = new Map<string, string>();

/** Every message of the chat with [peer], opened as the web client opens it; media downloaded and decrypted. */
async function readMessages(peer: string) {
  const me = need();
  const page = await api.listMessages(me.token, peer.toLowerCase());
  const peerKey = (userId: string) => peerIdentityPublic(me.token, userId);
  const out = [];
  // Oldest first: the ratchet steps in order.
  const messages = [...page.messages].sort((a, b) => a.created_at.localeCompare(b.created_at));
  for (const dto of messages) {
    const isMine = dto.sender_user_id.toLowerCase() === me.userId;
    const entry: Record<string, unknown> = {
      id: dto.id.toLowerCase(),
      sender: dto.sender_user_id.toLowerCase(),
      contentType: dto.content_type,
      deleted: dto.deleted_for_everyone,
    };
    if (!dto.deleted_for_everyone && dto.ciphertext) {
      try {
        const plain = opened.get(dto.id.toLowerCase()) ?? utf8decode(
          await openMessage({
            envelopeData: wireB64ToEnvelope(dto.ciphertext),
            peerUserId: peer.toLowerCase(),
            ourUserId: me.userId,
            ourPrivate: me.material.agreementPrivate,
            ourIdentityPublic: me.material.agreementPublic,
            senderIdentityPublic: isMine ? me.material.agreementPublic : await peerKey(dto.sender_user_id),
            asSender: isMine,
            sentAt: Date.parse(dto.created_at),
          }),
        );
        opened.set(dto.id.toLowerCase(), plain);
        if (dto.content_type === "media") {
          const payload = parseMediaPayload(plain);
          entry.media = payload
            ? { t: payload.t, mime: payload.mime, w: payload.w, h: payload.h, caption: payload.c ?? null, duration: payload.d ?? null, size: payload.s ?? null, hasWaveform: Boolean(payload.wf), reply: payload.re ?? null }
            : null;
          if (payload && dto.media_object_id) {
            const sealed = await api.getMediaContent(me.token, dto.media_object_id);
            const bytes = await aesGcmOpen(b64ToBytes(payload.k), sealed);
            (entry.media as Record<string, unknown>).sha256 = sha256(bytes);
            (entry.media as Record<string, unknown>).bytes = bytes.byteLength;
          }
        } else if (dto.content_type === "annotation") {
          entry.annotation = plain;
        } else {
          const parsed = parseTextPayload(plain);
          entry.text = parsed.text;
          entry.replyTo = parsed.replyTo;
          entry.linkPreview = parsed.linkPreview
            ? { url: parsed.linkPreview.url, title: parsed.linkPreview.title, siteName: parsed.linkPreview.siteName, summary: parsed.linkPreview.summary }
            : null;
        }
      } catch (err) {
        entry.error = err instanceof Error ? err.message : String(err);
      }
    }
    const reactions = [];
    for (const wire of dto.reactions ?? []) {
      const reaction = await openReaction(wire, me.userId, me.material, peerKey);
      if (reaction.emojis.length) reactions.push({ userId: reaction.userId, emojis: reaction.emojis });
    }
    entry.reactions = reactions;
    out.push(entry);
  }
  return { messages: out };
}

/* ---- Control server ---------------------------------------------------------------------------- */

async function body(req: IncomingMessage): Promise<Record<string, unknown>> {
  const chunks: Buffer[] = [];
  for await (const chunk of req) chunks.push(chunk as Buffer);
  const raw = Buffer.concat(chunks).toString("utf8");
  return raw ? (JSON.parse(raw) as Record<string, unknown>) : {};
}

function reply(res: ServerResponse, status: number, value: unknown) {
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(value));
}

const routes: Record<string, (b: Record<string, unknown>, url: URL) => Promise<unknown>> = {
  "POST /account": async () => {
    const created = await createAccount();
    return { userId: created.userId, username: created.username };
  },
  "POST /contacts/accept": () => acceptRequests(),
  "POST /contacts/request": (b) => requestContact(b as { userId: string }),
  "POST /text": (b) => sendText(b as Parameters<typeof sendText>[0]),
  "POST /photo": (b) => sendPhoto(b as Parameters<typeof sendPhoto>[0]),
  "POST /react": (b) => react(b as Parameters<typeof react>[0]),
  "POST /delete": (b) => deleteForEveryone(b as Parameters<typeof deleteForEveryone>[0]),
  "GET /messages": (_, url) => readMessages(url.searchParams.get("peer") ?? ""),
  "POST /logout": async () => {
    if (account) await api.logout(account.token).catch(() => undefined);
    account = null;
    return { loggedOut: true };
  },
  "GET /health": async () => ({ ok: true, hasAccount: account != null }),
};

createServer(async (req, res) => {
  const url = new URL(req.url ?? "/", "http://peer");
  const route = routes[`${req.method} ${url.pathname}`];
  if (!route) return reply(res, 404, { error: "no such route" });
  try {
    const result = await route(await body(req), url);
    console.log(`peer: ${req.method} ${url.pathname} ok`);
    reply(res, 200, result);
  } catch (err) {
    const status = err instanceof HttpError ? err.status : 500;
    const message = err instanceof Error ? `${err.name}: ${err.message}` : "failed";
    console.log(`peer: ${req.method} ${url.pathname} failed (${status})`);
    reply(res, status, { error: message });
  }
}).listen(PORT, "127.0.0.1", () => console.log(`peer: listening on 127.0.0.1:${PORT}, API ${API}`));
