import { apiBase } from "../config";

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
        device_id: deviceId ?? undefined,
      }),
    }),
  me: (token: string) => request<{ user: Session["user"]; device: Session["device"] }>("/auth/me", { token }),
  logout: (token: string) => request<void>("/auth/logout", { method: "POST", token }),
  conversations: (token: string) =>
    request<{ conversations: Conversation[] }>("/conversations", { token }),
};
