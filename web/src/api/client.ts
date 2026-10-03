import { apiBase } from "../config";
import { normalizeUsername, usernameHashB64 } from "../crypto/username";
import type { Invite } from "../invite";

const UUID_RE =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export class ApiError extends Error {
  readonly code: string;
  readonly status: number;
  /** The error response's JSON, for answers that carry data (a reaction's `409`). */
  readonly body: unknown;
  constructor(code: string, message: string, status: number, body?: unknown) {
    super(message);
    this.code = code;
    this.status = status;
    this.body = body;
  }
  get isAuthFailure(): boolean {
    return this.status === 401;
  }
  /** A 401 because this device was removed from the account's Devices list: wipe, don't just sign out. */
  get isDeviceRemoved(): boolean {
    return this.status === 401 && this.code === "DEVICE_REMOVED";
  }
}

export type Session = {
  token: string;
  user: { id: string; username: string; share_code: string };
  /** `sealed_name`: see `crypto/deviceName.ts`; only the account's devices can open it. */
  device: { id: string; sealed_name?: string | null };
};

export type Conversation = {
  id: string;
  peer: { id: string; username?: string; /** The account was deleted. This is not a name. */ deleted?: boolean };
  created_at: string;
  last_message_at: string | null;
  /** The chat's latest reaction change (see `reactions.ts`); absent from older servers. */
  reaction_seq?: number;
  /** The other side's reactions to our messages we have not marked seen (the heart badge). */
  unseen_reactions?: number;
  /** Their messages after our read marker (capped at 999); absent from older servers. */
  unread_count?: number;
  /** Set while we have the chat muted; `until` null means until we unmute it. */
  mute?: ChatMute | null;
};

export type ChatMute = { until: string | null };

/** What this device asks the server to push (see `server/.../routes/notifications.rs`). */
export type NotificationSettings = {
  enabled: boolean;
  show_sender: boolean;
  reactions: boolean;
  contact_requests: boolean;
  sound: string;
  badge: boolean;
  badge_includes_muted: boolean;
};

export type TestPushOutcome = {
  channel: "apns" | "web" | null;
  status: "sent" | "not_registered" | "not_configured" | "misconfigured" | "rejected" | "failed";
  detail?: string;
};

export type Contact = {
  user_id: string;
  created_at: string;
  /** The contact's username, sealed to this account. Absent until they have published it. */
  sealed_name?: string | null;
  /** Filled on this device after the seal opens. The server does not send it. */
  username?: string;
};

export type ContactRequest = {
  id: string;
  from_user_id: string;
  to_user_id: string;
  status: string;
  created_at: string;
  user?: { id: string } | null;
};

export type Device = {
  id: string;
  /** Sealed by the account's devices (`crypto/deviceName.ts`); absent until one names it. */
  sealed_name?: string | null;
  created_at: string;
  last_seen_at?: string | null;
  is_current: boolean;
};

export type BlockItem = {
  user_id: string;
  /** A name this device already knew. Absent from the server. */
  username?: string;
  created_at: string;
};

export type PrivacySettings = {
  allow_peer_chat_delete: boolean;
  /** Contacts see when you read their messages, and you see theirs (both ways, server-enforced). */
  send_read_receipts: boolean;
  /** Typing and voice-recording indicators go out and come in. */
  send_typing: boolean;
  /** Contacts see "online" / "last seen", and you see theirs. */
  share_presence: boolean;
  /**
   * Unused. A username is not a way to find an account. Older settings objects still
   * carry the field.
   */
  discoverable_by_username?: boolean;
};

export type UserCard = {
  id: string;
  share_code?: string;
};

export type CallModality = "voice" | "video";

/** `ringing`, `active`, then how it ended (see docs/calls.md for what each side shows). */
export type CallStatus = "ringing" | "active" | "rejected" | "missed" | "cancelled" | "ended";

/** A call as the server tells it (docs/calls.md). It carries no username. */
export type CallInfo = {
  id: string;
  caller_user_id: string;
  caller_device_id: string;
  caller_username?: string | null;
  caller_deleted?: boolean;
  callee_user_id: string;
  /** The callee device that answered; absent until then. */
  callee_device_id?: string | null;
  callee_username?: string | null;
  callee_deleted?: boolean;
  modality: CallModality;
  status: CallStatus;
  ended_reason?: string | null;
  /** 2: nothing about the media is negotiated before the answer. */
  protocol: number;
  created_at: string;
  answered_at?: string | null;
  ended_at?: string | null;
  /**
   * Only in `GET /calls/{id}` and heartbeat answers to one of the two devices in a live call:
   * the other device's latest sealed `media_state`, so a camera switch missed in a socket gap
   * is caught up.
   */
  peer_media_state?: { from_device_id: string; payload: string } | null;
};

/** One STUN or TURN server; TURN logins are minted per user and expire (12 h). */
export type IceServer = {
  urls: string | string[];
  username?: string;
  credential?: string;
};

export type CallSignalType = "sdp_offer" | "sdp_answer" | "ice_candidate" | "renegotiate" | "media_state";

async function request<T>(
  path: string,
  init: RequestInit & { token?: string | null } = {},
): Promise<T> {
  const { token, ...fetchInit } = init;
  const headers = new Headers(fetchInit.headers);
  headers.set("Accept", "application/json");
  if (fetchInit.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  if (token) headers.set("Authorization", `Bearer ${token}`);

  let res: Response;
  try {
    res = await fetch(`${apiBase()}${path}`, { ...fetchInit, headers });
  } catch (err) {
    throw new ApiError("transport", err instanceof Error ? err.message : "Network error", 0);
  }

  if (!res.ok) {
    let code = "http";
    let message = res.statusText || `HTTP ${res.status}`;
    let body: unknown;
    try {
      body = await res.json();
      const envelope = body as { error?: { code?: string; message?: string } };
      if (envelope.error?.code) code = envelope.error.code;
      if (envelope.error?.message) message = envelope.error.message;
    } catch {
      /* envelope optional */
    }
    throw new ApiError(code, message, res.status, body);
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  if (!text) return undefined as T;
  try {
    return JSON.parse(text) as T;
  } catch {
    throw new ApiError("decoding", "Could not read the server response.", res.status);
  }
}

/** Bytes moved so far, and the expected total when the server (or caller) knows it. */
export type TransferProgress = (loaded: number, total: number | null) => void;

async function requestBytes(
  path: string,
  token: string,
  onProgress?: TransferProgress,
  expectedBytes?: number,
  signal?: AbortSignal,
): Promise<Uint8Array> {
  const headers = new Headers({ Accept: "*/*", Authorization: `Bearer ${token}` });
  let res: Response;
  try {
    res = await fetch(`${apiBase()}${path}`, { headers, signal });
  } catch (err) {
    if (signal?.aborted) throw err;
    throw new ApiError("transport", err instanceof Error ? err.message : "Network error", 0);
  }
  if (!res.ok) {
    let code = "http";
    let message = res.statusText || `HTTP ${res.status}`;
    try {
      const body = (await res.json()) as { error?: { code?: string; message?: string } };
      if (body.error?.code) code = body.error.code;
      if (body.error?.message) message = body.error.message;
    } catch {
      /* envelope optional */
    }
    throw new ApiError(code, message, res.status);
  }
  if (!onProgress || !res.body) return new Uint8Array(await res.arrayBuffer());
  // Read in chunks so a photo can show how far along it is.
  const declared = Number(res.headers.get("Content-Length"));
  const total = declared > 0 ? declared : (expectedBytes ?? null);
  const reader = res.body.getReader();
  const chunks: Uint8Array[] = [];
  let loaded = 0;
  onProgress(0, total);
  for (;;) {
    if (signal?.aborted) {
      await reader.cancel();
      throw signal.reason ?? new DOMException("Aborted", "AbortError");
    }
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value);
    loaded += value.byteLength;
    onProgress(loaded, total);
  }
  const out = new Uint8Array(loaded);
  let offset = 0;
  for (const chunk of chunks) {
    out.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return out;
}

/** `fetch` can't report upload progress, so photo uploads go through XHR. */
function putBytesWithProgress(
  path: string,
  token: string,
  data: Uint8Array,
  onProgress: TransferProgress,
): Promise<void> {
  const copy = new Uint8Array(data.byteLength);
  copy.set(data);
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("PUT", `${apiBase()}${path}`);
    xhr.setRequestHeader("Accept", "application/json");
    xhr.setRequestHeader("Authorization", `Bearer ${token}`);
    xhr.setRequestHeader("Content-Type", "application/octet-stream");
    xhr.upload.onprogress = (event) => {
      onProgress(event.loaded, event.lengthComputable ? event.total : copy.byteLength);
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve();
        return;
      }
      let code = "http";
      let message = xhr.statusText || `HTTP ${xhr.status}`;
      try {
        const body = JSON.parse(xhr.responseText) as { error?: { code?: string; message?: string } };
        if (body.error?.code) code = body.error.code;
        if (body.error?.message) message = body.error.message;
      } catch {
        /* envelope optional */
      }
      reject(new ApiError(code, message, xhr.status));
    };
    xhr.onerror = () => reject(new ApiError("transport", "Network error", 0));
    xhr.onabort = () => reject(new ApiError("transport", "Upload cancelled", 0));
    onProgress(0, copy.byteLength);
    xhr.send(new Blob([copy], { type: "application/octet-stream" }));
  });
}

async function putBytes(path: string, token: string, data: Uint8Array): Promise<void> {
  const copy = new Uint8Array(data.byteLength);
  copy.set(data);
  const headers = new Headers({
    Accept: "application/json",
    Authorization: `Bearer ${token}`,
    "Content-Type": "application/octet-stream",
  });
  let res: Response;
  try {
    res = await fetch(`${apiBase()}${path}`, {
      method: "PUT",
      headers,
      body: new Blob([copy], { type: "application/octet-stream" }),
    });
  } catch (err) {
    throw new ApiError("transport", err instanceof Error ? err.message : "Network error", 0);
  }
  if (!res.ok) {
    let code = "http";
    let message = res.statusText || `HTTP ${res.status}`;
    try {
      const body = (await res.json()) as { error?: { code?: string; message?: string } };
      if (body.error?.code) code = body.error.code;
      if (body.error?.message) message = body.error.message;
    } catch {
      /* envelope optional */
    }
    throw new ApiError(code, message, res.status);
  }
}

/** The name stays on this device. The register and login answers do not include it. */
function withLocalUsername(session: Session, username: string): Session {
  return { ...session, user: { ...session.user, username } };
}

export const api = {
  health: () => request<{ status: string }>("/health/live"),
  /** No device name here: it is sealed once the phrase is known (`deviceNaming.ts`). The username is hashed on this device. */
  register: async (username: string, password: string) => {
    const name = normalizeUsername(username);
    const session = await request<Session>("/auth/register", {
      method: "POST",
      body: JSON.stringify({ username_hash: usernameHashB64(name), password }),
    });
    return withLocalUsername(session, name);
  },
  login: async (username: string, password: string, deviceId?: string | null) => {
    const name = normalizeUsername(username);
    const session = await request<Session>("/auth/login", {
      method: "POST",
      body: JSON.stringify({
        username_hash: usernameHashB64(name),
        password,
        ...(deviceId && UUID_RE.test(deviceId) ? { device_id: deviceId } : {}),
      }),
    });
    return withLocalUsername(session, name);
  },
  me: (token: string) => request<{ user: Session["user"]; device: Session["device"] }>("/auth/me", { token }),
  logout: (token: string) => request<void>("/auth/logout", { method: "POST", token }),
  /**
   * No session: a locked page cannot read its token, only the token's SHA-256 (see
   * `deviceRemoval.ts`). `removed` is true only when the session with that hash belongs to a
   * removed device or a deleted account.
   */
  sessionStatus: (tokenHash: string) =>
    request<{ removed: boolean }>("/auth/session-status", {
      method: "POST",
      body: JSON.stringify({ token_hash: tokenHash }),
    }),
  /** New PIN: the server keeps the pepper and the auth-key verifier (see crypto/vault.ts). */
  createPinGuard: (token: string, verifier: string) =>
    request<{ guard_id: string; pepper: string; max_attempts: number }>("/pin-guard", {
      method: "POST",
      token,
      body: JSON.stringify({ verifier }),
    }),
  /** No session: the token is sealed in the vault this unlocks. 403 wrong PIN, 410 guard gone. */
  unlockPinGuard: (guardId: string, authKey: string) =>
    request<{ pepper: string }>("/pin-guard/unlock", {
      method: "POST",
      body: JSON.stringify({ guard_id: guardId, auth_key: authKey }),
    }),
  /**
   * No session: the lock screen cannot read the token. Deletes the pepper so a copied
   * profile cannot keep guessing this PIN. 204 when the guard is already gone.
   */
  abandonPinGuard: (guardId: string) =>
    request<void>("/pin-guard/abandon", {
      method: "POST",
      body: JSON.stringify({ guard_id: guardId }),
    }),
  conversations: (token: string) =>
    request<{ conversations: Conversation[] }>("/conversations", { token }),
  contacts: (token: string) => request<{ contacts: Contact[] }>("/contacts", { token }),
  contactRequests: (token: string, box: "incoming" | "outgoing" = "incoming") =>
    request<{ requests: ContactRequest[] }>(
      `/contacts/requests?box=${box}&status=pending`,
      { token },
    ),
  lookupUser: async (token: string, invite: Invite) => {
    if (invite.kind === "username") {
      throw new ApiError(
        "NOT_FOUND",
        "Add someone with their QR code or share code.",
        404,
      );
    }
    if (invite.kind === "userId") {
      return request<UserCard>(`/users/${invite.value}`, { token });
    }
    return request<UserCard>(`/users/by-code/${encodeURIComponent(invite.value)}`, { token });
  },
  /** This account's username, sealed to a mutual contact. The server cannot read it. */
  putContactName: (token: string, userId: string, sealed: string) =>
    request<void>(`/contacts/${encodeURIComponent(userId.toLowerCase())}/sealed-name`, {
      method: "PUT",
      token,
      body: JSON.stringify({ sealed }),
    }),
  createContactRequest: (token: string, userId: string) =>
    request<ContactRequest>("/contacts/requests", {
      method: "POST",
      token,
      body: JSON.stringify({ user_id: userId }),
    }),
  acceptRequest: (token: string, id: string) =>
    request<ContactRequest>(`/contacts/requests/${id}/accept`, {
      method: "POST",
      token,
      body: "{}",
    }),
  rejectRequest: (token: string, id: string) =>
    request<ContactRequest>(`/contacts/requests/${id}/reject`, {
      method: "POST",
      token,
      body: "{}",
    }),
  /** `everyone` is the sender's call only; the server rejects it from anyone else. */
  deleteMessage: (token: string, messageId: string, scope: "me" | "everyone") =>
    request<void>(`/messages/${encodeURIComponent(messageId)}?scope=${scope}`, {
      method: "DELETE",
      token,
    }),
  /** Delivery ack for a peer's message: lower-case id, no body (iOS `MessagesService.swift:157-162`). */
  markDelivered: (token: string, messageId: string) =>
    request<void>(`/messages/${encodeURIComponent(messageId.toLowerCase())}/delivered`, {
      method: "POST",
      token,
    }),
  devices: (token: string) => request<{ devices: Device[] }>("/devices", { token }),
  revokeDevice: (token: string, deviceId: string) =>
    request<void>(`/devices/${deviceId}`, { method: "DELETE", token }),
  /** Stores a device's sealed name; the server never sees it in the clear. */
  putDeviceName: (token: string, deviceId: string, sealedName: string) =>
    request<void>(`/devices/${encodeURIComponent(deviceId.toLowerCase())}/name`, {
      method: "PUT",
      token,
      body: JSON.stringify({ sealed_name: sealedName }),
    }),
  blocks: (token: string) => request<{ blocks: BlockItem[] }>("/blocks", { token }),
  unblock: (token: string, userId: string) =>
    request<void>(`/blocks/${userId}`, { method: "DELETE", token }),
  privacySettings: (token: string) => request<PrivacySettings>("/privacy/settings", { token }),
  /** A new share code; QR codes and links with the old one stop working. */
  rotateShareCode: (token: string) =>
    request<{ share_code: string }>("/users/me/share-code", { method: "POST", token }),
  /** Partial update: fields left out stay as they are. */
  updatePrivacySettings: (token: string, change: Partial<PrivacySettings>) =>
    request<PrivacySettings>("/privacy/settings", {
      method: "PUT",
      token,
      body: JSON.stringify(change),
    }),
  putBundle: (token: string, body: unknown) =>
    request<void>("/keys/bundle", { method: "PUT", token, body: JSON.stringify(body) }),
  keysStatus: (token: string) =>
    request<{
      device_id: string;
      has_identity: boolean;
      signed_pre_key_id: number | null;
      otpk_count: number;
    }>("/keys/status", { token }),
  presence: (token: string, userId: string) =>
    request<{ user_id: string; online: boolean; last_seen_at?: string | null }>(
      `/presence/${userId}`,
      { token },
    ),
  peerIdentity: (token: string, userId: string) =>
    request<{ user_id: string; device_id: string; registration_id: number; identity_key: string }>(
      `/keys/identity/${userId}`,
      { token },
    ),
  listMessages: (token: string, peerUserId: string, extra: Record<string, string> = {}) => {
    const q = new URLSearchParams({ peer_user_id: peerUserId, limit: "100", ...extra });
    return request<{
      conversation_id: string | null;
      messages: WireMessage[];
      has_more: boolean;
      /** Highest reaction seq in the chat as the page was read (see `reactions.ts`). */
      reaction_seq?: number | null;
    }>(`/messages?${q.toString()}`, { token });
  },
  /**
   * Our whole set, built on our record at `baseSeq` (0: none). `409 REACTION_CHANGED` (its
   * `current` on the ApiError's body) when our other device wrote it first.
   */
  putReaction: (token: string, messageId: string, ciphertext: string, baseSeq: number, added: boolean) =>
    request<WireReaction>(`/messages/${encodeURIComponent(messageId.toLowerCase())}/reaction`, {
      method: "PUT",
      token,
      body: JSON.stringify({ ciphertext, base_seq: baseSeq, added }),
    }),
  /** Undefined (`204`) when there was no reaction to remove; `409` as for `putReaction`. */
  deleteReaction: (token: string, messageId: string, baseSeq: number) =>
    request<WireReaction | undefined>(
      `/messages/${encodeURIComponent(messageId.toLowerCase())}/reaction?base_seq=${baseSeq}`,
      { method: "DELETE", token },
    ),
  /** This device's push settings (defaults until it saves some). */
  notificationSettings: (token: string) =>
    request<NotificationSettings>("/notifications/settings", { token }),
  /** Only the fields given change; returns what the server stored. */
  updateNotificationSettings: (token: string, patch: Partial<NotificationSettings>) =>
    request<NotificationSettings>("/notifications/settings", {
      method: "PUT",
      token,
      body: JSON.stringify(patch),
    }),
  /** Silences a chat on every device of the account; `seconds` null = until unmuted. */
  muteChat: (token: string, peerUserId: string, seconds: number | null) =>
    request<{ peer_user_id: string; mute: ChatMute }>(
      `/conversations/${encodeURIComponent(peerUserId.toLowerCase())}/mute`,
      { method: "PUT", token, body: JSON.stringify({ seconds }) },
    ),
  unmuteChat: (token: string, peerUserId: string) =>
    request<void>(`/conversations/${encodeURIComponent(peerUserId.toLowerCase())}/mute`, {
      method: "DELETE",
      token,
    }),
  /** We looked at the chat: its unread count clears everywhere and the peer gets receipts. */
  markChatRead: (token: string, peerUserId: string) =>
    request<{ read_at: string | null; unread_count: number; receipts: number }>(
      `/conversations/${encodeURIComponent(peerUserId.toLowerCase())}/read`,
      { method: "POST", token, body: "{}" },
    ),
  /** The VAPID key browsers subscribe with. */
  webPushKey: (token: string) => request<{ public_key: string }>("/push/web/key", { token }),
  putWebPushSubscription: (token: string, subscription: PushSubscriptionJSON) =>
    request<void>("/push/web/subscription", {
      method: "PUT",
      token,
      body: JSON.stringify(subscription),
    }),
  deleteWebPushSubscription: (token: string) =>
    request<void>("/push/web/subscription", { method: "DELETE", token }),
  /** A notification through the push service, even with the app open. */
  testPush: (token: string) => request<TestPushOutcome>("/push/test", { method: "POST", token }),
  /** Settings the server operator sets for clients (the reaction limit). */
  clientConfig: (token: string) =>
    request<{ reactions: { max_per_user: number } }>("/config", { token }),
  /** Reactions to our messages in this chat are seen up to `upToSeq` (clamped by the server). */
  markReactionsSeen: (token: string, peerUserId: string, upToSeq: number) =>
    request<{ seen_seq: number }>(
      `/conversations/${encodeURIComponent(peerUserId.toLowerCase())}/reactions/seen`,
      { method: "POST", token, body: JSON.stringify({ up_to_seq: upToSeq }) },
    ),
  reactionChanges: (token: string, peerUserId: string, afterSeq: number) =>
    request<{ reactions: WireReaction[]; next_seq: number; has_more: boolean }>(
      `/conversations/${encodeURIComponent(peerUserId.toLowerCase())}/reactions?after_seq=${afterSeq}`,
      { token },
    ),
  sendMessage: (
    token: string,
    body: {
      peer_user_id: string;
      client_message_id: string;
      content_type: string;
      ciphertext: string;
      media_object_id?: string;
    },
  ) => request<WireMessage>("/messages", { method: "POST", token, body: JSON.stringify(body) }),
  getMediaContent: (
    token: string,
    mediaId: string,
    onProgress?: TransferProgress,
    expectedBytes?: number,
    signal?: AbortSignal,
  ) => requestBytes(`/media/${mediaId.toLowerCase()}/content`, token, onProgress, expectedBytes, signal),
  createMediaUpload: (token: string, sizeBytes: number, contentType = "application/octet-stream") =>
    request<{ media_object_id: string; upload_url: string; object_key: string; expires_at: string }>(
      "/media/uploads",
      {
        method: "POST",
        token,
        body: JSON.stringify({ size_bytes: sizeBytes, content_type: contentType }),
      },
    ),
  putMediaContent: (token: string, mediaId: string, data: Uint8Array, onProgress?: TransferProgress) =>
    onProgress
      ? putBytesWithProgress(`/media/${mediaId.toLowerCase()}/content`, token, data, onProgress)
      : putBytes(`/media/${mediaId.toLowerCase()}/content`, token, data),
  /** STUN/TURN for one call; TURN logins expire, so each call asks again. */
  iceServers: (token: string) => request<{ ice_servers: IceServer[] }>("/calls/ice-servers", { token }),
  /** Rings the peer. `409 CALL_BUSY`: they are in a call; `409 CALL_IN_PROGRESS`: we are. */
  createCall: (token: string, peerUserId: string, modality: CallModality) =>
    request<CallInfo>("/calls", {
      method: "POST",
      token,
      body: JSON.stringify({ peer_user_id: peerUserId, modality, protocol: 2 }),
    }),
  /** Newest first; `before` is a call's `created_at`. */
  listCalls: (token: string, opts: { limit?: number; before?: string } = {}) => {
    const q = new URLSearchParams();
    if (opts.limit) q.set("limit", String(opts.limit));
    if (opts.before) q.set("before", opts.before);
    const query = q.toString();
    return request<{ calls: CallInfo[] }>(`/calls${query ? `?${query}` : ""}`, { token });
  },
  getCall: (token: string, callId: string) =>
    request<CallInfo>(`/calls/${encodeURIComponent(callId.toLowerCase())}`, { token }),
  /** This device answers; `VALIDATION_ERROR` once the call stopped ringing. */
  acceptCall: (token: string, callId: string) =>
    request<CallInfo>(`/calls/${encodeURIComponent(callId.toLowerCase())}/accept`, {
      method: "POST",
      token,
      body: "{}",
    }),
  rejectCall: (token: string, callId: string) =>
    request<CallInfo>(`/calls/${encodeURIComponent(callId.toLowerCase())}/reject`, { method: "POST", token }),
  /** `keepalive` lets it leave with a closing page. */
  hangupCall: (token: string, callId: string, opts: { keepalive?: boolean } = {}) =>
    request<CallInfo>(`/calls/${encodeURIComponent(callId.toLowerCase())}/hangup`, {
      method: "POST",
      token,
      keepalive: opts.keepalive,
    }),
  /** A sealed signal (see calls/crypto.ts), relayed to the other device in the call only. */
  sendCallSignal: (token: string, callId: string, signalType: CallSignalType, payload: string) =>
    request<void>(`/calls/${encodeURIComponent(callId.toLowerCase())}/signal`, {
      method: "POST",
      token,
      body: JSON.stringify({ signal_type: signalType, payload }),
    }),
  /** Every 10 s while in a call; the answer's status may say it ended. */
  callHeartbeat: (token: string, callId: string) =>
    request<CallInfo>(`/calls/${encodeURIComponent(callId.toLowerCase())}/heartbeat`, { method: "POST", token }),
};

export type WireMessage = {
  id: string;
  conversation_id: string;
  sender_user_id: string;
  sender_device_id: string;
  client_message_id: string;
  content_type: string;
  ciphertext: string | null;
  media_object_id?: string | null;
  deleted_for_everyone: boolean;
  created_at: string;
  delivered?: boolean | null;
  read?: boolean | null;
  /** History pages only: live sealed reactions (omitted when there are none). */
  reactions?: WireReaction[];
};

/** One user's sealed reaction on one message; `ciphertext` null once taken back. */
export type WireReaction = {
  message_id: string;
  user_id: string;
  ciphertext: string | null;
  seq: number;
  updated_at: string;
};
