import { wsUrl } from "./config";

export type RealtimeEvent = { type: string; raw: Record<string, unknown> };

export function connectRealtime(opts: {
  token: string;
  onEvent: (event: RealtimeEvent) => void;
  onFatalAuth?: () => void;
}): () => void {
  let socket: WebSocket | null = null;
  let closed = false;
  let attempt = 0;
  let reconnectTimer = 0;

  function onVisible() {
    if (!document.hidden && !closed && !socket) open();
  }

  function open() {
    if (closed || socket) return;
    window.clearTimeout(reconnectTimer);
    const ws = new WebSocket(wsUrl());
    socket = ws;
    ws.onopen = () => {
      ws.send(JSON.stringify({ type: "auth", token: opts.token }));
    };
    ws.onmessage = (ev) => {
      if (typeof ev.data !== "string") return;
      let raw: Record<string, unknown>;
      try {
        raw = JSON.parse(ev.data) as Record<string, unknown>;
      } catch {
        return;
      }
      const type = typeof raw.type === "string" ? raw.type : "";
      if (type === "auth.ok") {
        attempt = 0;
        opts.onEvent({ type, raw });
        return;
      }
      if (type === "auth.error") {
        closed = true;
        ws.close();
        opts.onFatalAuth?.();
        return;
      }
      if (type) opts.onEvent({ type, raw });
    };
    ws.onclose = () => {
      if (socket === ws) socket = null;
      if (closed) return;
      const delay = Math.min(30_000, 1000 * 2 ** Math.min(attempt, 8));
      attempt += 1;
      reconnectTimer = window.setTimeout(open, delay);
    };
    ws.onerror = () => {
      ws.close();
    };
  }

  document.addEventListener("visibilitychange", onVisible);
  open();
  return () => {
    closed = true;
    window.clearTimeout(reconnectTimer);
    document.removeEventListener("visibilitychange", onVisible);
    socket?.close();
    socket = null;
  };
}
