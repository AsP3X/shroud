/*
 * Typing and recording signals between clients. The server relays
 * `{type: "typing", peer_user_id, is_typing}` and
 * `{type: "recording", peer_user_id, is_recording}` to the peer's devices only,
 * once it has checked the two are contacts (server routes/ws.rs). These timings
 * are the contract with iOS `MessagingController` — change them together:
 *
 *  - a sender says `true` when typing starts, and again at most every
 *    KEEPALIVE_MS while it goes on;
 *  - it says `false` after IDLE_MS without a keystroke, on send, when the draft
 *    empties, and on leaving the chat;
 *  - recording uses the same keepalive and receiver expiry, but has no idle
 *    timeout: it stays on until the sender stops, sends, or leaves the chat;
 *  - a receiver drops the indicator after EXPIRE_MS without a fresh `true`, so a
 *    sender that vanishes (tab closed, connection lost) can't leave it stuck. A
 *    message from that peer clears it at once.
 */

export const TYPING_KEEPALIVE_MS = 3_000;
export const TYPING_IDLE_MS = 3_000;
export const TYPING_EXPIRE_MS = 6_000;

export type PeerActivity = "typing" | "recording";

export type TypingSender = {
  /** A change in the composer for `peerId`; `composing` is false once the draft is empty. */
  input: (peerId: string, composing: boolean) => void;
  /** Says `false` if we last said `true`. */
  stop: () => void;
};

export function createTypingSender(send: (peerId: string, typing: boolean) => void): TypingSender {
  /** The peer we last told we were typing, until we say we've stopped. */
  let peer: string | null = null;
  let lastSent = 0;
  let idle = 0;

  function stop() {
    window.clearTimeout(idle);
    if (peer) send(peer, false);
    peer = null;
    lastSent = 0;
  }

  return {
    input(peerId, composing) {
      if (peer && peer.toLowerCase() !== peerId.toLowerCase()) stop();
      if (!composing) {
        stop();
        return;
      }
      peer = peerId;
      const now = Date.now();
      if (now - lastSent >= TYPING_KEEPALIVE_MS) {
        send(peerId, true);
        lastSent = now;
      }
      window.clearTimeout(idle);
      idle = window.setTimeout(stop, TYPING_IDLE_MS);
    },
    stop,
  };
}

export type RecordingSender = {
  /** Starts or stops the recording signal for `peerId`. */
  set: (peerId: string, recording: boolean) => void;
  /** Says `false` if we last said `true`. */
  stop: () => void;
};

export function createRecordingSender(
  send: (peerId: string, recording: boolean) => void,
): RecordingSender {
  let peer: string | null = null;
  let keepalive = 0;

  function stop() {
    window.clearInterval(keepalive);
    keepalive = 0;
    if (peer) send(peer, false);
    peer = null;
  }

  return {
    set(peerId, recording) {
      if (!recording) {
        if (peer && peer.toLowerCase() === peerId.toLowerCase()) stop();
        return;
      }
      if (peer && peer.toLowerCase() !== peerId.toLowerCase()) stop();
      const already = Boolean(peer && peer.toLowerCase() === peerId.toLowerCase());
      peer = peerId;
      if (!already) send(peerId, true);
      window.clearInterval(keepalive);
      keepalive = window.setInterval(() => send(peerId, true), TYPING_KEEPALIVE_MS);
    },
    stop,
  };
}
