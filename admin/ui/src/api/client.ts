import type { ApiErrorBody } from "./types";

/** A non-2xx answer from /api/admin, with the contract's code (docs/admin-plan.md §3). */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly upstream: "postgres" | "api" | undefined;
  readonly retryAfterSecs: number | undefined;

  constructor(status: number, body: ApiErrorBody, retryAfterSecs?: number) {
    super(body.message);
    this.name = "ApiError";
    this.status = status;
    this.code = body.code;
    this.upstream = body.upstream;
    this.retryAfterSecs = retryAfterSecs;
  }
}

const BASE = "/api/admin";

function csrfToken(): string | undefined {
  const match = document.cookie.match(/(?:^|;\s*)admin_csrf=([^;]*)/);
  return match?.[1];
}

/** In development the page's own `?state=` reaches the fixture server, so a frame state can be
 *  opened by URL (`/users?state=empty`). Production builds never add it. */
function devState(): string | null {
  if (!import.meta.env.DEV) return null;
  return new URLSearchParams(window.location.search).get("state");
}

export async function api<T>(path: string, init: { method?: string; body?: unknown } = {}): Promise<T> {
  const method = init.method ?? "GET";
  const url = new URL(BASE + path, window.location.origin);
  const state = devState();
  if (state) url.searchParams.set("state", state);

  const headers: Record<string, string> = { Accept: "application/json" };
  if (init.body !== undefined) headers["Content-Type"] = "application/json";
  if (method !== "GET") headers["X-Admin-CSRF"] = csrfToken() ?? "";

  const response = await fetch(url, {
    method,
    headers,
    credentials: "same-origin",
    cache: "no-store",
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
  });

  if (response.status === 204) return undefined as T;
  if (response.ok) return (await response.json()) as T;

  let body: ApiErrorBody = { code: "UNKNOWN", message: `The server answered ${response.status}.` };
  try {
    body = (await response.json()) as ApiErrorBody;
  } catch {
    // Not JSON (a proxy page, say): keep the generic message.
  }
  const retryAfter = response.headers.get("Retry-After");
  throw new ApiError(response.status, body, retryAfter ? Number(retryAfter) : undefined);
}
