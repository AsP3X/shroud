import { apiBase } from "../config";
import type { Invite } from "../invite";

const UUID_RE =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export class ApiError extends Error {
  readonly code: string;
  readonly status: number;
  constructor(code: string, message: string, status: number) {
    super(message);
    this.code = code;
    this.status = status;
  }
  get isAuthFailure(): boolean {
    return this.status === 401;
  }
}

export type Session = {
  token: string;
  user: { id: string; username: string; share_code: string };
  device: { id: string; name: string | null };
};

export type Conversation = {
  id: string;
  peer: { id: string; username: string };
  created_at: string;
  last_message_at: string | null;
};

export type Contact = {
  user_id: string;
  username: string;
  created_at: string;
};

export type ContactRequest = {
  id: string;
  from_user_id: string;
  to_user_id: string;
  status: string;
  created_at: string;
  user?: { id: string; username: string } | null;
};

export type Device = {
  id: string;
  name?: string | null;
  created_at: string;
  last_seen_at?: string | null;
  is_current: boolean;
};

export type BlockItem = {
  user_id: string;
  username: string;
  created_at: string;
};

export type PrivacySettings = {
  allow_peer_chat_delete: boolean;
};

export type UserCard = {
  id: string;
  username: string;
  share_code?: string;
};

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
    try {
      const body = (await res.json()) as { error?: { code?: string; message?: string } };
      if (body.error?.code) code = body.error.code;
      if (body.error?.message) message = body.error.message;
    } catch {
      /* envelope optional */
    }
    throw new ApiError(code, message, res.status);
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
): Promise<Uint8Array> {
  const headers = new Headers({ Accept: "*/*", Authorization: `Bearer ${token}` });
  let res: Response;
  try {
    res = await fetch(`${apiBase()}${path}`, { headers });
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
  if (!onProgress || !res.body) return new Uint8Array(await res.arrayBuffer());
  // Read in chunks so a photo can show how far along it is.
  const declared = Number(res.headers.get("Content-Length"));
  const total = declared > 0 ? declared : (expectedBytes ?? null);
  const reader = res.body.getReader();
  const chunks: Uint8Array[] = [];
  let loaded = 0;
  onProgress(0, total);
  for (;;) {
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

export const api = {
  health: () => request<{ status: string }>("/health/live"),
  register: (username: string, password: string, deviceName: string) =>
    request<Session>("/auth/register", {
      method: "POST",
      body: JSON.stringify({ username, password, device_name: deviceName }),
    }),
  login: (username: string, password: string, deviceName: string, deviceId?: string | null) =>
    request<Session>("/auth/login", {
      method: "POST",
      body: JSON.stringify({
        username,
        password,
        device_name: deviceName,
        ...(deviceId && UUID_RE.test(deviceId) ? { device_id: deviceId } : {}),
      }),
    }),
  me: (token: string) => request<{ user: Session["user"]; device: Session["device"] }>("/auth/me", { token }),
  logout: (token: string) => request<void>("/auth/logout", { method: "POST", token }),
  conversations: (token: string) =>
    request<{ conversations: Conversation[] }>("/conversations", { token }),
  contacts: (token: string) => request<{ contacts: Contact[] }>("/contacts", { token }),
  contactRequests: (token: string, box: "incoming" | "outgoing" = "incoming") =>
    request<{ requests: ContactRequest[] }>(
      `/contacts/requests?box=${box}&status=pending`,
      { token },
    ),
  lookupUser: async (token: string, invite: Invite) => {
    if (invite.kind === "userId") {
      return request<UserCard>(`/users/${invite.value}`, { token });
    }
    if (invite.kind === "shareCode") {
      try {
        return await request<UserCard>(`/users/by-code/${encodeURIComponent(invite.value)}`, {
          token,
        });
      } catch (err) {
        const asName = invite.value.toLowerCase();
        if (
          err instanceof ApiError &&
          err.status === 404 &&
          /^[a-z0-9_]{3,32}$/.test(asName)
        ) {
          return request<UserCard>(`/users/by-username/${encodeURIComponent(asName)}`, { token });
        }
        throw err;
      }
    }
    return request<UserCard>(`/users/by-username/${encodeURIComponent(invite.value)}`, { token });
  },
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
  devices: (token: string) => request<{ devices: Device[] }>("/devices", { token }),
  revokeDevice: (token: string, deviceId: string) =>
    request<void>(`/devices/${deviceId}`, { method: "DELETE", token }),
  blocks: (token: string) => request<{ blocks: BlockItem[] }>("/blocks", { token }),
  unblock: (token: string, userId: string) =>
    request<void>(`/blocks/${userId}`, { method: "DELETE", token }),
  privacySettings: (token: string) => request<PrivacySettings>("/privacy/settings", { token }),
  updatePrivacySettings: (token: string, allowPeerChatDelete: boolean) =>
    request<PrivacySettings>("/privacy/settings", {
      method: "PUT",
      token,
      body: JSON.stringify({ allow_peer_chat_delete: allowPeerChatDelete }),
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
    }>(`/messages?${q.toString()}`, { token });
  },
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
  ) => requestBytes(`/media/${mediaId.toLowerCase()}/content`, token, onProgress, expectedBytes),
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
};
