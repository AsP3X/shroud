import { api, ApiError, type Session } from "./api/client";
import { validateMnemonic } from "./crypto/bip39";
import { b64ToBytes, bytesEqual } from "./crypto/bytes";
import { openDeviceName, type DeviceLabel } from "./crypto/deviceName";
import { historyKeyFromMnemonic, identityKeyFromMnemonic } from "./crypto/identity";

/** Mirrors `MAX_DEVICES_PER_USER` on the server. */
export const DEVICE_LIMIT = 5;

/**
 * One of the account's devices, as a login that found every slot signed in lists them. The name
 * is sealed with the phrase (`crypto/deviceName.ts`); it opens only once the phrase checked out.
 */
export type LimitDevice = {
  id: string;
  sealed_name: string | null;
  created_at: string;
  last_seen_at: string | null;
};

/** What the user typed on the credentials step, kept in memory so the retry sends the very same login. */
export type LoginAttempt = { username: string; password: string; deviceId: string | null };

export type LoginOutcome =
  | { kind: "session"; session: Session }
  /**
   * Every slot is signed in. `devices` are least recently active first, never empty;
   * `identityKey` is the account's published key the phrase must derive.
   */
  | { kind: "full"; devices: LimitDevice[]; identityKey: string }
  | { kind: "error"; error: unknown };

function limitDevice(raw: unknown): LimitDevice | null {
  if (!raw || typeof raw !== "object") return null;
  const { id, sealed_name, created_at, last_seen_at } = raw as Record<string, unknown>;
  if (typeof id !== "string" || !id || typeof created_at !== "string") return null;
  return {
    id,
    sealed_name: typeof sealed_name === "string" && sealed_name ? sealed_name : null,
    created_at,
    last_seen_at: typeof last_seen_at === "string" ? last_seen_at : null,
  };
}

/**
 * The account's devices a `409 DEVICE_LIMIT` offers for sign-out (least recently active first)
 * and its published identity key, or null for any other error. A server that sends only
 * `oldest_device` gives a list of that one. Without a device or the key (an older server, or an
 * account no device has published keys for yet) the phrase can't be checked first, so the 409
 * stays the inline error.
 */
export function deviceLimitOf(err: unknown): { devices: LimitDevice[]; identityKey: string } | null {
  if (!(err instanceof ApiError) || err.status !== 409 || err.code !== "DEVICE_LIMIT") return null;
  const body = err.body as { oldest_device?: unknown; devices?: unknown; identity_key?: unknown } | null | undefined;
  const identityKey = body?.identity_key;
  if (typeof identityKey !== "string" || !identityKey) return null;
  const listed = Array.isArray(body?.devices)
    ? body.devices.map(limitDevice).filter((device): device is LimitDevice => device !== null)
    : [];
  const oldest = limitDevice(body?.oldest_device);
  const devices = listed.length > 0 ? listed : oldest ? [oldest] : [];
  return devices.length > 0 ? { devices, identityKey } : null;
}

/**
 * One login. `replaceDeviceId` is the device the user agreed to log out; when it is gone already
 * and the account is full again, the answer is `full` with the account's devices now — never a
 * sign-out of a device the user didn't see.
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

/**
 * Each device's name and kind, opened with the checked phrase's history key the way
 * Settings › Devices opens them; null for a device never named or a name that won't open.
 * Kept in memory only.
 */
export function openLimitNames(devices: LimitDevice[], words: string[]): Record<string, DeviceLabel | null> {
  const historyKey = historyKeyFromMnemonic(words);
  return Object.fromEntries(
    devices.map((device) => [device.id, openDeviceName(historyKey, device.id, device.sealed_name)]),
  );
}

/**
 * A login that found every slot signed in, waiting on the phrase step. Never persisted.
 * `selectedId` is the device to log out: the least recently active one unless the user chose another.
 */
export type PendingLogin = {
  attempt: LoginAttempt;
  devices: LimitDevice[];
  identityKey: string;
  selectedId: string;
};

/**
 * Null when no login waits. `words` are set once the phrase checked out: the dialog is open
 * then, `labels` hold the names they opened, and the phrase step finishes with those words.
 * `picking`: "Choose a device to log out" shows in its place.
 */
export type DeviceLimitState = {
  pending: PendingLogin;
  words: string[] | null;
  labels: Record<string, DeviceLabel | null>;
  picking: boolean;
  busy: boolean;
} | null;

export function selectedDevice(pending: PendingLogin): LimitDevice {
  return pending.devices.find((device) => device.id === pending.selectedId) ?? pending.devices[0];
}

/** "Log out your oldest device?" while the least recently active one is selected. */
export function isOldestSelected(pending: PendingLogin): boolean {
  return pending.selectedId === pending.devices[0].id;
}

export type ReplaceResult =
  | { kind: "session"; session: Session; words: string[] }
  /** The chosen device was gone and the account full again: ask again about the devices now. */
  | { kind: "full-again"; devices: LimitDevice[] }
  /** The new 409 carries another identity key than the phrase was checked against. */
  | { kind: "wrong-phrase"; devices: LimitDevice[]; identityKey: string }
  | { kind: "error"; error: unknown };

/** Confirm: the same login again, naming the device the user saw selected. */
export async function replaceSelectedDevice(pending: PendingLogin, words: string[]): Promise<ReplaceResult> {
  const outcome = await attemptLogin(pending.attempt, selectedDevice(pending).id);
  switch (outcome.kind) {
    case "session":
      return { kind: "session", session: outcome.session, words };
    case "full":
      return sameKey(outcome.identityKey, pending.identityKey)
        ? { kind: "full-again", devices: outcome.devices }
        : { kind: "wrong-phrase", devices: outcome.devices, identityKey: outcome.identityKey };
    case "error":
      return outcome;
  }
}

export type DeviceLimitEvent =
  /** The credentials step's login. */
  | { type: "login"; attempt: LoginAttempt; outcome: LoginOutcome }
  /** The phrase derived the account's key. */
  | { type: "phrase"; words: string[] }
  /** "Choose Another Device". */
  | { type: "pick" }
  /** A row of the picker: select it and go back to the question. */
  | { type: "select"; id: string }
  /** The picker's Cancel: back to the question, selection unchanged. */
  | { type: "unpick" }
  | { type: "confirm" }
  | { type: "cancel" }
  | { type: "replaced"; result: ReplaceResult }
  /** Back to the credentials step. */
  | { type: "reset" };

function waiting(attempt: LoginAttempt, devices: LimitDevice[], identityKey: string): NonNullable<DeviceLimitState> {
  return {
    pending: { attempt, devices, identityKey, selectedId: devices[0].id },
    words: null,
    labels: {},
    picking: false,
    busy: false,
  };
}

/**
 * The wait across the credentials step, the phrase, the user's answer and the retry. A full
 * account sends the login on to the phrase step without a session; a phrase that checks out opens
 * the dialog with the names it opens; Cancel closes it except while the retry runs. After the
 * retry a session ends the wait; a full account again lists its devices now, keeping the choice
 * if that device is still there (idle again); anything else closes the dialog but keeps the login
 * waiting on the phrase step.
 */
export function nextDeviceLimitState(state: DeviceLimitState, event: DeviceLimitEvent): DeviceLimitState {
  switch (event.type) {
    case "login":
      return event.outcome.kind === "full"
        ? waiting(event.attempt, event.outcome.devices, event.outcome.identityKey)
        : null;
    case "phrase":
      return state
        ? {
            ...state,
            words: event.words,
            labels: openLimitNames(state.pending.devices, event.words),
            picking: false,
            busy: false,
          }
        : null;
    case "pick":
      return state?.words && !state.busy && state.pending.devices.length > 1 ? { ...state, picking: true } : state;
    case "select":
      return state?.picking && state.pending.devices.some((device) => device.id === event.id)
        ? { ...state, pending: { ...state.pending, selectedId: event.id }, picking: false }
        : state;
    case "unpick":
      return state?.picking ? { ...state, picking: false } : state;
    case "confirm":
      return state?.words && !state.picking ? { ...state, busy: true } : state;
    case "cancel":
      return state && !state.busy ? { ...state, words: null, picking: false } : state;
    case "replaced": {
      const { result } = event;
      if (!state || result.kind === "session") return null;
      if (result.kind === "full-again") {
        const { devices } = result;
        const kept = devices.some((device) => device.id === state.pending.selectedId);
        return {
          pending: { ...state.pending, devices, selectedId: kept ? state.pending.selectedId : devices[0].id },
          words: state.words,
          labels: state.words ? openLimitNames(devices, state.words) : {},
          picking: false,
          busy: false,
        };
      }
      if (result.kind === "wrong-phrase") return waiting(state.pending.attempt, result.devices, result.identityKey);
      return { ...state, words: null, picking: false, busy: false };
    }
    case "reset":
      return null;
  }
}
