/*
 * The scripted web peer of the Android engine e2e (00-plan §2.3 W2-INT, §6.3).
 *
 * One web account per process, run by the web client's own crypto, API and call modules
 * (web/src/crypto/*, web/src/api/client.ts, reply.ts, reactions.ts, files.ts, messaging.ts' sendFile
 * and messageFromMediaPayload, calls/controller.ts) with
 * in-memory stores instead of the browser's: what this peer seals and opens is byte for byte what
 * the real web client seals and opens. EngineE2eTest (androidTest, on the emulator) drives it over a
 * small HTTP control API on the host's loopback (the emulator reaches it as http://10.0.2.2:<port>).
 *
 * The account is either a new one (POST /account) or a second device of an existing account
 * (POST /login with that account's test password and phrase: the e2e's "other device" of the
 * Android account). A WebSocket (POST /socket) carries what the web client's socket carries: typing
 * both ways, presence (a connected, focused socket is online), and the `call.*` events that drive
 * the web CallController, which runs on fake media (fakeMedia.ts, from the web selftest). engine-e2e.sh
 * starts several peers, one account each (the web vault is one per page, so one per process).
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
// `fake` (default) speaks the selftest SDP. `chrome` is a real RTCPeerConnection, for a device
// WebRTC engine. The fake path stays the one engine-e2e.sh uses.
const MEDIA = args.get("media") ?? "fake";

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
const { parseMediaPayload, isFilePayload } = await import("../../../web/src/crypto/mediaPayload");
const { sealFileBlob, openFileBlob } = await import("../../../web/src/crypto/fileBlob");
const { sanitizeFileName, fileTypeOf, fileExtension, contentMatches, CONTENT_CHECK_BYTES } = await import("../../../web/src/files");
const { sendFile: webSendFile, messageFromMediaPayload } = await import("../../../web/src/messaging");
const { openReaction, saveReaction } = await import("../../../web/src/reactions");
const { openDeviceName } = await import("../../../web/src/crypto/deviceName");
const { CallController } = await import("../../../web/src/calls/controller");
const { FakePeer, FakeStream, FakeTrack } = await import("./fakeMedia");
const { ChromeMedia, ChromePeer } = await import("./chromeMedia");
const chrome =
  MEDIA === "chrome"
    ? new ChromeMedia(`http://127.0.0.1:${PORT}/health`, `${process.env.SHROUD_E2E_STATE ?? "/tmp"}/chrome-${PORT}`)
    : null;

type Material = ReturnType<typeof establish>;
type Account = { token: string; userId: string; username: string; deviceId: string; shareCode: string; material: Material };
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
  openVault(session.user.id, material);
  account = {
    token: session.token,
    userId: session.user.id.toLowerCase(),
    username: session.user.username,
    deviceId: session.device.id.toLowerCase(),
    shareCode: session.user.share_code,
    material,
  };
  return account;
}

/** The vault opens with any PIN wrap; the peer never locks it. */
function openVault(userId: string, material: Material) {
  createVault(
    userId,
    {
      secrets: { authKey: randomBytes(32), material: randomBytes(32), salt: randomBytes(16), iter: 1, length: 6 },
      pepper: randomBytes(32),
      guardId: randomUUID(),
    },
    material.historyKey,
  );
}

/**
 * A second device of an existing account (the e2e's "other device" of the Android account): logs in
 * with its test password and derives the same identity from its test phrase. Publishes no bundle:
 * the account's identity is already on the server.
 */
async function login(body: { username: string; password: string; words: string[] }): Promise<Account> {
  const session = await api.login(body.username, body.password);
  const material = establish(body.words, session.user.id);
  openVault(session.user.id, material);
  account = {
    token: session.token,
    userId: session.user.id.toLowerCase(),
    username: session.user.username,
    deviceId: session.device.id.toLowerCase(),
    shareCode: session.user.share_code,
    material,
  };
  return account;
}

/**
 * A new identity for this account (a phrase reset): new keys on the server, a fresh vault and no
 * ratchet sessions or cached plaintext. The Android side must notice the change and ask to trust it.
 */
async function rekey() {
  const me = need();
  socketOff();
  const words = generateMnemonic();
  const material = establish(words, me.userId);
  await api.putBundle(me.token, putBundleRequest(material));
  (globalThis as Record<string, unknown>).localStorage = new MemoryStorage();
  (globalThis as Record<string, unknown>).sessionStorage = new MemoryStorage();
  opened.clear();
  openVault(me.userId, material);
  me.material = material;
  return { rekeyed: true };
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

type ReplyBody = { id: string; senderUserId: string; kind: "text" | "image" | "video" | "voice" | "file"; snippet: string };
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

/**
 * A shared file sent the way the web client sends one (docs/file-sharing.md): SHRF1-sealed by
 * `crypto/fileBlob.ts`, uploaded and enveloped by `messaging.ts`'s `sendFile`. The bytes are
 * `text` (UTF-8), `b64`, or `patternBytes` of `i % 251`, optionally after a `prefix`. `name` is
 * cleaned first unless `raw` — a hostile sender that skips the cleaning, for the receiver's own.
 */
async function sendFileMessage(body: {
  peer: string;
  name: string;
  raw?: boolean;
  text?: string;
  b64?: string;
  prefix?: string;
  patternBytes?: number;
  caption?: string;
  replyTo?: ReplyBody;
}) {
  const me = need();
  const peer = body.peer.toLowerCase();
  const head = body.prefix ? utf8(body.prefix) : new Uint8Array(0);
  const rest = body.text != null
    ? utf8(body.text)
    : body.b64 != null
      ? b64ToBytes(body.b64)
      : Uint8Array.from({ length: body.patternBytes ?? 0 }, (_, i) => i % 251);
  const bytes = new Uint8Array(head.byteLength + rest.byteLength);
  bytes.set(head, 0);
  bytes.set(rest, head.byteLength);
  const name = body.raw ? body.name : sanitizeFileName(body.name);
  const key = randomBytes(32);
  const blob = await sealFileBlob(new Blob([bytes]), key);
  const reply = body.replyTo
    ? { id: body.replyTo.id.toLowerCase(), senderUserId: body.replyTo.senderUserId.toLowerCase(), kind: body.replyTo.kind, snippet: body.replyTo.snippet }
    : null;
  const sent = await webSendFile({
    token: me.token,
    me: me.userId,
    peerUserId: peer,
    material: me.material,
    sealed: { blob, key },
    name,
    size: bytes.byteLength,
    caption: body.caption ?? null,
    replyTo: reply,
  });
  return { id: sent.id.toLowerCase(), sha256: sha256(bytes), size: bytes.byteLength, name };
}

/** What the web reads from a received `t: "file"` payload: the bubble's fields, the opened blob, the checks. */
async function readFile(payload: NonNullable<ReturnType<typeof parseMediaPayload>>, base: { id: string; sender: string; createdAt: string; isMine: boolean }, mediaObjectId: string | null) {
  const me = need();
  const shown = messageFromMediaPayload(
    { id: base.id, senderUserId: base.sender, createdAt: base.createdAt, isMine: base.isMine, deleted: false, failed: false, delivered: false, read: false },
    payload,
    mediaObjectId,
  ) as unknown as Record<string, unknown>;
  const cleaned = sanitizeFileName(payload.n ?? "");
  const type = fileTypeOf(cleaned);
  const file: Record<string, unknown> = {
    kind: shown.kind,
    rawName: payload.n ?? null,
    fileName: shown.fileName,
    cleanedName: cleaned,
    shownMime: shown.mime,
    tableMime: type?.mime ?? null,
    warning: type?.warning ?? null,
    caption: shown.caption ?? null,
  };
  if (mediaObjectId && payload.s != null) {
    const sealed = await api.getMediaContent(me.token, mediaObjectId);
    const opened = await openFileBlob(new Blob([sealed]), b64ToBytes(payload.k), payload.s, type?.mime ?? "application/octet-stream");
    const bytes = new Uint8Array(await opened.arrayBuffer());
    file.sealedBytes = sealed.byteLength;
    file.sha256 = sha256(bytes);
    file.bytes = bytes.byteLength;
    file.contentMatches = contentMatches(fileExtension(cleaned), bytes.subarray(0, CONTENT_CHECK_BYTES));
  }
  return file;
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
      delivered: Boolean((dto as { delivered?: boolean }).delivered),
      read: Boolean((dto as { read?: boolean }).read),
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
          // A file before the MIME-sniffing kinds, as the web client reads it.
          if (payload && isFilePayload(payload)) {
            entry.media = { t: payload.t, mime: payload.mime, size: payload.s ?? null, reply: payload.re ?? null };
            entry.file = await readFile(
              payload,
              { id: dto.id.toLowerCase(), sender: dto.sender_user_id.toLowerCase(), createdAt: dto.created_at, isMine },
              dto.media_object_id ?? null,
            );
            entry.reactions = [];
            out.push(entry);
            continue;
          }
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

/* ---- Socket: typing, presence, call events ------------------------------------------------------- */

type SeenEvent = { seq: number; type: string; raw: Record<string, unknown> };
const events: SeenEvent[] = [];
let eventSeq = 0;
let socket: WebSocket | null = null;
let socketReady = false;

/** `ws(s)://…/api/v1/ws`, as the web's `wsUrl()` builds it from the API base. */
function wsUrl(): string {
  return `${API.replace(/^http/, "ws").replace(/\/$/, "")}/ws`;
}

/**
 * Connects this account's socket as the web client does (`realtime.ts`): auth, then `focus: true`
 * — a visible tab — so the server shows us online. Every event is kept for GET /events, and the
 * `call.*` events (and `auth.ok`, a reconnect may have missed some) go to the call controller.
 */
async function socketOn() {
  const me = need();
  if (socket) return { connected: socketReady };
  const ws = new WebSocket(wsUrl());
  socket = ws;
  socketReady = false;
  const ready = new Promise<boolean>((resolve) => {
    ws.onopen = () => ws.send(JSON.stringify({ type: "auth", token: me.token }));
    ws.onmessage = (ev) => {
      if (typeof ev.data !== "string") return;
      let raw: Record<string, unknown>;
      try {
        raw = JSON.parse(ev.data) as Record<string, unknown>;
      } catch {
        return;
      }
      const type = typeof raw.type === "string" ? raw.type : "";
      if (!type) return;
      if (type === "auth.ok") {
        socketReady = true;
        ws.send(JSON.stringify({ type: "focus", focused: true }));
        resolve(true);
      }
      if (type === "auth.error") resolve(false);
      events.push({ seq: ++eventSeq, type, raw });
      if (events.length > 2_000) events.splice(0, events.length - 2_000);
      if (type === "auth.ok" || type.startsWith("call.")) calls.handle({ type, raw });
    };
    ws.onclose = () => {
      if (socket === ws) {
        socket = null;
        socketReady = false;
      }
      resolve(false);
    };
    ws.onerror = () => ws.close();
  });
  const connected = await Promise.race([ready, new Promise<boolean>((r) => setTimeout(() => r(false), 10_000))]);
  if (!connected) throw new HttpError(502, "the socket did not authenticate");
  return { connected };
}

function socketOff() {
  const ws = socket;
  socket = null;
  socketReady = false;
  ws?.close();
  return { connected: false };
}

function sendFrame(frame: Record<string, unknown>) {
  if (!socket || !socketReady) throw new HttpError(409, "no socket: POST /socket first");
  socket.send(JSON.stringify(frame));
}

/** What the socket brought since `since`, optionally of one type. */
function seenEvents(since: number, type: string | null) {
  return { last: eventSeq, events: events.filter((e) => e.seq > since && (!type || e.type === type)) };
}

/* ---- Reads, receipts, privacy, mutes, devices ------------------------------------------------- */

async function markRead(body: { peer: string }) {
  return api.markChatRead(need().token, body.peer.toLowerCase());
}

async function markDelivered(body: { messageId: string }) {
  await api.markDelivered(need().token, body.messageId.toLowerCase());
  return { delivered: true };
}

async function setPrivacy(body: Record<string, unknown>) {
  return api.updatePrivacySettings(need().token, body);
}

async function conversations() {
  const list = await api.conversations(need().token);
  return {
    conversations: (Array.isArray(list) ? list : (list as { conversations: unknown[] }).conversations).map((c) => {
      const conversation = c as { id: string; peer: { id: string; username: string }; unread_count?: number; mute?: { until: string | null } | null };
      return {
        id: conversation.id.toLowerCase(),
        peer: conversation.peer.id.toLowerCase(),
        username: conversation.peer.username,
        unread: conversation.unread_count ?? 0,
        muted: conversation.mute != null,
        mutedUntil: conversation.mute?.until ?? null,
      };
    }),
  };
}

/** This account's devices, their sealed names opened with the account's history key (the web's Devices list). */
async function devices() {
  const me = need();
  const { devices: list } = await api.devices(me.token);
  return {
    devices: list.map((d) => {
      const label = openDeviceName(me.material.historyKey, d.id.toLowerCase(), d.sealed_name);
      return { id: d.id.toLowerCase(), current: d.is_current, name: label?.name ?? null, kind: label?.kind ?? null };
    }),
  };
}

async function revoke(body: { deviceId: string }) {
  await api.revokeDevice(need().token, body.deviceId.toLowerCase());
  return { revoked: true };
}

/* ---- Calls: the web CallController on fake media ---------------------------------------------- */

type View = { phase: string; callId: string | null; role: string; modality: string; peer: { id: string }; connectedAt: number | null; micOn: boolean; remoteMic: boolean };
let callView: View | null = null;
/** Every phase the call screen went through, in order (one entry per change). */
const callPhases: string[] = [];

const calls = new CallController({
  unsupported: () => null,
  getUserMedia: async (constraints: MediaStreamConstraints) => {
    if (chrome) return (await chrome.userMedia(constraints as unknown as Record<string, unknown>)) as unknown as MediaStream;
    const tracks = [new FakeTrack("audio")];
    if (constraints.video) tracks.push(new FakeTrack("video", "user"));
    return new FakeStream(tracks) as unknown as MediaStream;
  },
  cameras: async () => ["cam-front"],
  createPeer: (config: RTCConfiguration) =>
    chrome
      ? (chrome.createPeer(config as unknown as Record<string, unknown>) as unknown as RTCPeerConnection)
      : (new FakePeer("web", config) as unknown as RTCPeerConnection),
  createStream: (tracks: MediaStreamTrack[]) =>
    chrome
      ? ({
          getTracks: () => tracks,
          getAudioTracks: () => tracks.filter((track) => track.kind === "audio"),
          getVideoTracks: () => tracks.filter((track) => track.kind === "video"),
          addTrack: (track: MediaStreamTrack) => {
            if (!tracks.includes(track)) tracks.push(track);
          },
        } as unknown as MediaStream)
      : (new FakeStream(tracks as unknown as InstanceType<typeof FakeTrack>[]) as unknown as MediaStream),
  ...(chrome
    ? {
        remoteFingerprint: async (pc: RTCPeerConnection) =>
          pc instanceof ChromePeer ? chrome.fingerprint(pc) : null,
      }
    : {}),
  now: () => Date.now(),
  setTimeout: (run: () => void, ms: number) => setTimeout(run, ms) as unknown as number,
  clearTimeout: (id: number) => clearTimeout(id as unknown as NodeJS.Timeout),
  setInterval: (run: () => void, ms: number) => setInterval(run, ms) as unknown as number,
  clearInterval: (id: number) => clearInterval(id as unknown as NodeJS.Timeout),
  publish: (view: unknown) => {
    callView = (view as View | null) ?? null;
    const phase = callView ? callView.phase : "idle";
    if (callPhases[callPhases.length - 1] !== phase) callPhases.push(phase);
  },
  tone: () => undefined,
  playAudio: async () => true,
  keepAwake: () => undefined,
  holdAutoLock: () => () => undefined,
  interruptVoice: () => undefined,
  notifyRing: () => undefined,
  tellTabs: () => undefined,
});

/** The account calls with its own identity and API, as `calls/service.ts` configures it. */
function configureCalls(me: Account) {
  const { token } = me;
  calls.configure({
    userId: me.userId,
    deviceId: me.deviceId,
    identity: () => ({ privateKey: me.material.agreementPrivate.slice(), publicKey: me.material.agreementPublic.slice() }),
    peerKey: (userId: string) => peerIdentityPublic(token, userId),
    peerName: () => null,
    api: {
      iceServers: async () => (await api.iceServers(token)).ice_servers ?? [],
      createCall: (peerUserId, modality) => api.createCall(token, peerUserId, modality),
      getCall: (callId) => api.getCall(token, callId),
      acceptCall: (callId) => api.acceptCall(token, callId),
      rejectCall: (callId) => api.rejectCall(token, callId),
      hangupCall: (callId, keepalive) => api.hangupCall(token, callId, { keepalive }),
      sendSignal: (callId, signalType, payload) => api.sendCallSignal(token, callId, signalType, payload),
      heartbeat: (callId) => api.callHeartbeat(token, callId),
    },
  });
}

/** A new account starts with no call on screen and no phase history. */
function resetCalls() {
  calls.release();
  callView = null;
  callPhases.length = 0;
}

function callState() {
  const view = callView;
  return {
    phase: view ? view.phase : "idle",
    callId: view?.callId?.toLowerCase() ?? null,
    role: view?.role ?? null,
    modality: view?.modality ?? null,
    peer: view?.peer?.id?.toLowerCase() ?? null,
    connected: view?.connectedAt != null,
    remoteMic: view?.remoteMic ?? null,
    canShare: Boolean((view as { canShare?: boolean } | null)?.canShare),
    ice: chrome?.iceState ?? null,
    phases: [...callPhases],
  };
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
    resetCalls();
    const created = await createAccount();
    configureCalls(created);
    return { userId: created.userId, username: created.username, shareCode: created.shareCode, deviceId: created.deviceId };
  },
  "POST /login": async (b) => {
    resetCalls();
    const signedIn = await login(b as Parameters<typeof login>[0]);
    configureCalls(signedIn);
    return { userId: signedIn.userId, username: signedIn.username, deviceId: signedIn.deviceId };
  },
  "POST /rekey": () => rekey(),
  "POST /contacts/accept": () => acceptRequests(),
  "POST /contacts/request": (b) => requestContact(b as { userId: string }),
  "POST /text": (b) => sendText(b as Parameters<typeof sendText>[0]),
  "POST /photo": (b) => sendPhoto(b as Parameters<typeof sendPhoto>[0]),
  "POST /file": (b) => sendFileMessage(b as Parameters<typeof sendFileMessage>[0]),
  "POST /react": (b) => react(b as Parameters<typeof react>[0]),
  "POST /delete": (b) => deleteForEveryone(b as Parameters<typeof deleteForEveryone>[0]),
  "POST /read": (b) => markRead(b as { peer: string }),
  "POST /delivered": (b) => markDelivered(b as { messageId: string }),
  "POST /privacy": (b) => setPrivacy(b),
  "GET /conversations": () => conversations(),
  "GET /presence": async (_, url) => {
    const user = url.searchParams.get("user");
    if (!user) throw new HttpError(400, "user required");
    const presence = await api.presence(need().token, user.toLowerCase());
    return { userId: presence.user_id, online: presence.online };
  },
  "GET /messages": (_, url) => readMessages(url.searchParams.get("peer") ?? ""),
  "GET /devices": () => devices(),
  "GET /whoami": () => {
    const me = need();
    return { userId: me.userId, username: me.username, deviceId: me.deviceId };
  },
  "POST /revoke": (b) => revoke(b as { deviceId: string }),
  "POST /socket": async (b) => ((b as { on?: boolean }).on === false ? socketOff() : socketOn()),
  "POST /typing": async (b) => {
    const body = b as { peer: string; typing: boolean };
    sendFrame({ type: "typing", peer_user_id: body.peer.toLowerCase(), is_typing: body.typing });
    return { sent: true };
  },
  "GET /events": async (_, url) => seenEvents(Number(url.searchParams.get("since") ?? "0"), url.searchParams.get("type")),
  "POST /call/start": async (b) => {
    const body = b as { peer: string; username?: string; modality?: "voice" | "video" };
    calls.start({ id: body.peer.toLowerCase(), username: body.username ?? "android" }, body.modality ?? "voice");
    return callState();
  },
  "POST /call/accept": async () => {
    calls.accept();
    return callState();
  },
  "POST /call/decline": async () => {
    calls.decline();
    return callState();
  },
  "POST /call/hangup": async () => {
    calls.hangup();
    return callState();
  },
  "GET /call": async () => callState(),
  "POST /logout": async () => {
    calls.release();
    socketOff();
    if (account) await api.logout(account.token).catch(() => undefined);
    account = null;
    return { loggedOut: true };
  },
  "GET /health": async () => ({ ok: true, hasAccount: account != null, media: MEDIA }),
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
}).listen(PORT, "127.0.0.1", () => {
  console.log(`peer: listening on 127.0.0.1:${PORT}, API ${API}`);
  if (chrome) {
    void chrome.start().then(
      () => console.log("peer: chrome ready"),
      () => {
        console.log("peer: chrome failed");
        process.exit(1);
      },
    );
  }
});
