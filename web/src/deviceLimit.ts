import { api, ApiError, type Session } from "./api/client";
import { validateMnemonic } from "./crypto/bip39";
import { b64ToBytes, bytesEqual } from "./crypto/bytes";
import { identityKeyFromMnemonic } from "./crypto/identity";

/** Mirrors `MAX_DEVICES_PER_USER` on the server. */
export const DEVICE_LIMIT = 5;

/**
 * The account's least recently active device, named by a login that found every slot signed in.
 * No name: it is sealed with the phrase, which a browser that is only logging in doesn't have yet.
 */
export type OldestDevice = { id: string; created_at: string; last_seen_at?: string | null };

/** What the user typed on the credentials step, kept in memory so the retry sends the very same login. */
export type LoginAttempt = { username: string; password: string; deviceId: string | null };

export type LoginOutcome =
  | { kind: "session"; session: Session }
  /** Every slot is signed in. `identityKey` is the account's published key the phrase must derive. */
  | { kind: "full"; device: OldestDevice; identityKey: string }
  | { kind: "error"; error: unknown };

/**
 * The device a `409 DEVICE_LIMIT` offers for sign-out and the account's published identity key,
 * or null for any other error. Without either (an older server, or an account no device has
 * published keys for yet) the phrase can't be checked first, so the 409 stays the inline error.
 */
export function deviceLimitOf(err: unknown): { device: OldestDevice; identityKey: string } | null {
  if (!(err instanceof ApiError) || err.status !== 409 || err.code !== "DEVICE_LIMIT") return null;
  const body = err.body as { oldest_device?: unknown; identity_key?: unknown } | null | undefined;
  const identityKey = body?.identity_key;
  if (typeof identityKey !== "string" || !identityKey) return null;
  const device = body?.oldest_device;
  if (!device || typeof device !== "object") return null;
  const { id, created_at, last_seen_at } = device as Record<string, unknown>;
  if (typeof id !== "string" || !id || typeof created_at !== "string") return null;
  return {
    device: { id, created_at, last_seen_at: typeof last_seen_at === "string" ? last_seen_at : null },
    identityKey,
  };
}

/**
 * One login. `replaceDeviceId` is the device the user agreed to log out; when it is gone already
 * and the account is full again, the answer is `full` with whichever device is oldest now — never
 * a sign-out of a device the user didn't see.
 */
export async function attemptLogin(attempt: LoginAttempt, replaceDeviceId?: string): Promise<LoginOutcome> {
  try {
    const session = await api.login(attempt.username, attempt.password, attempt.deviceId, replaceDeviceId);
    return { kind: "session", session };
  } catch (error) {
    const limit = deviceLimitOf(error);
    return limit ? { kind: "full", ...limit } : { kind: "error", error };
  }
}

function sameKey(a: string, b: string): boolean {
  try {
    return bytesEqual(b64ToBytes(a), b64ToBytes(b));
  } catch {
    return false;
  }
}

/**
 * The phrase step's check while a login waits on a full account: the normalized words when they
 * derive the account's published identity key, else null (the wrong-phrase error). No request:
 * a wrong phrase never reaches the question, so it can't cost a device.
 */
export function phraseOpensAccount(words: string[], identityKey: string): string[] | null {
  try {
    const normalized = validateMnemonic(words);
    const derived = identityKeyFromMnemonic(normalized);
    return bytesEqual(derived, b64ToBytes(identityKey)) ? normalized : null;
  } catch {
    return null;
  }
}

/** A login that found every slot signed in, waiting on the phrase step. Never persisted. */
export type PendingLogin = { attempt: LoginAttempt; device: OldestDevice; identityKey: string };

/**
 * Null when no login waits. `words` are set once the phrase checked out: the "Log out your
 * oldest device?" dialog is open then, and the phrase step finishes with those words.
 */
export type DeviceLimitState = { pending: PendingLogin; words: string[] | null; busy: boolean } | null;

export type ReplaceResult =
  | { kind: "session"; session: Session; words: string[] }
  /** The agreed device was gone and the account full again: ask about this one. */
  | { kind: "swapped"; device: OldestDevice }
  /** The new 409 carries another identity key than the phrase was checked against. */
  | { kind: "wrong-phrase"; device: OldestDevice; identityKey: string }
  | { kind: "error"; error: unknown };

/** Confirm: the same login again, naming the device the user saw. */
export async function replaceOldestDevice(
  pending: PendingLogin,
  words: string[],
): Promise<ReplaceResult> {
  const outcome = await attemptLogin(pending.attempt, pending.device.id);
  switch (outcome.kind) {
    case "session":
      return { kind: "session", session: outcome.session, words };
    case "full":
      return sameKey(outcome.identityKey, pending.identityKey)
        ? { kind: "swapped", device: outcome.device }
        : { kind: "wrong-phrase", device: outcome.device, identityKey: outcome.identityKey };
    case "error":
      return outcome;
  }
}

export type DeviceLimitEvent =
  /** The credentials step's login. */
  | { type: "login"; attempt: LoginAttempt; outcome: LoginOutcome }
  /** The phrase derived the account's key. */
  | { type: "phrase"; words: string[] }
  | { type: "confirm" }
  | { type: "cancel" }
  | { type: "replaced"; result: ReplaceResult }
  /** Back to the credentials step. */
  | { type: "reset" };

/**
 * The wait across the credentials step, the phrase, the user's answer and the retry. A full
 * account sends the login on to the phrase step without a session; a phrase that checks out opens
 * the dialog; Cancel closes it except while the retry runs. After the retry a session ends the
 * wait, a new oldest device swaps in (idle again), and anything else closes the dialog but keeps
 * the login waiting on the phrase step.
 */
export function nextDeviceLimitState(state: DeviceLimitState, event: DeviceLimitEvent): DeviceLimitState {
  switch (event.type) {
    case "login":
      return event.outcome.kind === "full"
        ? {
            pending: { attempt: event.attempt, device: event.outcome.device, identityKey: event.outcome.identityKey },
            words: null,
            busy: false,
          }
        : null;
    case "phrase":
      return state ? { ...state, words: event.words, busy: false } : null;
    case "confirm":
      return state?.words ? { ...state, busy: true } : state;
    case "cancel":
      return state && !state.busy ? { ...state, words: null } : state;
    case "replaced": {
      const { result } = event;
      if (!state || result.kind === "session") return null;
      if (result.kind === "swapped") {
        return { pending: { ...state.pending, device: result.device }, words: state.words, busy: false };
      }
      if (result.kind === "wrong-phrase") {
        return {
          pending: { ...state.pending, device: result.device, identityKey: result.identityKey },
          words: null,
          busy: false,
        };
      }
      return { ...state, words: null, busy: false };
    }
    case "reset":
      return null;
  }
}
