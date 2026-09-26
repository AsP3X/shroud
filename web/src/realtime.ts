import { wsUrl } from "./config";

export type RealtimeEvent = { type: string; raw: Record<string, unknown> };

/**
 * `auth.error` ends the session except when this account already has as many sockets as the
 * server allows. That one is "try again later", not "this browser was signed out".
 */
export function sessionEndedByAuthError(raw: Record<string, unknown>): boolean {
  const error = raw.error;
  if (!error || typeof error !== "object") return true;
  const code = (error as { code?: unknown }).code;
  return code !== "RATE_LIMITED";
}

/** The user is looking at this tab. A background tab does not count: its timers stall. */
export function pageInForeground(): boolean {
  return (
    typeof document !== "undefined" &&
    !document.hidden &&
    typeof document.hasFocus === "function" &&
    document.hasFocus()
  );
}

/**
 * A hidden tab closes its socket so a push can arrive, unless a call still needs signaling.
 * A visible tab stays connected even when the window is not the focused one: a file dialog
 * and a second window blur the page without freezing it.
 */
export function socketAttention(hidden: boolean, keep: boolean): "park" | "stay" {
  return hidden && !keep ? "park" : "stay";
}

export type Realtime = {
  /** Best effort: dropped unless the socket is open and authenticated (typing and the like). */
  send: (message: Record<string, unknown>) => void;
  close: () => void;
};

export function connectRealtime(opts: {
  token: string;
  onEvent: (event: RealtimeEvent) => void;
  onFatalAuth?: () => void;
  /** Keep the socket while the tab is hidden (a call still needs its signaling). */
  keepWhenHidden?: () => boolean;
}): Realtime {
  let socket: WebSocket | null = null;
  /** The current socket has seen `auth.ok`; frames before that would be rejected. */
  let ready = false;
  let closed = false;
  /** Hidden on purpose, so `onclose` does not reconnect until the tab is in front again. */
  let parked = false;
  let attempt = 0;
  let reconnectTimer = 0;

  function sendFocus() {
    if (!ready || socket?.readyState !== WebSocket.OPEN) return;
    socket.send(JSON.stringify({ type: "focus", focused: pageInForeground() }));
  }

  /** The tab is not in front and nothing needs the socket: close it so pushes are sent. */
  function park() {
    sendFocus();
    parked = true;
    ready = false;
    window.clearTimeout(reconnectTimer);
    const ws = socket;
    socket = null;
    ws?.close();
  }

  function onAttention() {
    if (closed) return;
    const hidden = typeof document !== "undefined" && document.hidden;
    if (socketAttention(hidden, opts.keepWhenHidden?.() ?? false) === "park") {
      park();
      return;
    }
    parked = false;
    if (!socket) open();
    else sendFocus();
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
        ready = true;
        sendFocus();
        opts.onEvent({ type, raw });
        if (
          socketAttention(
            typeof document !== "undefined" && document.hidden,
            opts.keepWhenHidden?.() ?? false,
          ) === "park"
        ) {
          park();
        }
        return;
      }
      if (type === "auth.error") {
        // Too many sockets: the session is still good. The server closes; onclose reconnects.
        if (!sessionEndedByAuthError(raw)) return;
        closed = true;
        ws.close();
        opts.onFatalAuth?.();
        return;
      }
      if (type) opts.onEvent({ type, raw });
    };
    ws.onclose = () => {
      // A newer socket, or one we closed on purpose, must not start its own reconnect.
      const current = socket === ws;
      if (current) {
        socket = null;
        ready = false;
      }
      if (closed || parked || !current) return;
      const delay = Math.min(30_000, 1000 * 2 ** Math.min(attempt, 8));
      attempt += 1;
      reconnectTimer = window.setTimeout(open, delay);
    };
    ws.onerror = () => {
      ws.close();
    };
  }

  document.addEventListener("visibilitychange", onAttention);
  window.addEventListener("focus", onAttention);
  window.addEventListener("blur", onAttention);
  // A tab that is already hidden has no one watching. Stay disconnected until it is shown,
  // so the server pushes instead of talking to a frozen page.
  const hiddenAtStart = typeof document !== "undefined" && document.hidden;
  if (socketAttention(hiddenAtStart, opts.keepWhenHidden?.() ?? false) === "park") {
    parked = true;
  } else {
    open();
  }
  return {
    send(message) {
      if (!ready || socket?.readyState !== WebSocket.OPEN) return;
      socket.send(JSON.stringify(message));
    },
    close() {
      closed = true;
      parked = false;
      ready = false;
      window.clearTimeout(reconnectTimer);
      document.removeEventListener("visibilitychange", onAttention);
      window.removeEventListener("focus", onAttention);
      window.removeEventListener("blur", onAttention);
      socket?.close();
      socket = null;
    },
  };
}
