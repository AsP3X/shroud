/**
 * A login that finds every device slot signed in: reading the 409, the phrase that has to
 * check out before anything is asked, the retry that names the device to log out, a new oldest
 * device after the first one went, Cancel, and other errors.
 * Run: `npx tsx src/deviceLimit.selftest.ts`.
 */
import { ApiError } from "./api/client";
import { bytesToB64 } from "./crypto/bytes";
import { identityKeyFromMnemonic } from "./crypto/identity";
import {
  attemptLogin,
  deviceLimitOf,
  nextDeviceLimitState,
  phraseOpensAccount,
  replaceOldestDevice,
  type DeviceLimitState,
  type LoginAttempt,
  type LoginOutcome,
  type PendingLogin,
} from "./deviceLimit";

function check(condition: boolean, message: string): void {
  if (!condition) throw new Error(message);
}

Object.defineProperty(globalThis, "window", { value: globalThis, configurable: true });

const OLDEST = {
  id: "0b6f3c1e-4a51-4d5e-9c7a-2f1e8d3b6a90",
  created_at: "2025-03-12T09:30:00Z",
  last_seen_at: "2026-09-30T18:02:00Z",
};
const NEXT = {
  id: "7d2a9e44-1c3b-4f6a-8e5d-0a9b8c7d6e5f",
  created_at: "2025-06-01T12:00:00Z",
};
const ANCHOR = "5c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f";
const SESSION = {
  token: "t",
  user: { id: "u", username: "ignored", share_code: "s" },
  device: { id: "d" },
};

const PHRASE = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ");
const OTHER = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong".split(" ");
const KEY = bytesToB64(identityKeyFromMnemonic(PHRASE));
const OTHER_KEY = bytesToB64(identityKeyFromMnemonic(OTHER));

const limitBody = (device?: object, identityKey: string | null = KEY) => ({
  error: {
    code: "DEVICE_LIMIT",
    message: "This account already has the maximum number of devices (5). Remove a device and try again.",
  },
  ...(device ? { oldest_device: device } : {}),
  ...(identityKey !== null ? { identity_key: identityKey } : {}),
});
const limitError = (device?: object, identityKey: string | null = KEY) =>
  new ApiError("DEVICE_LIMIT", "full", 409, limitBody(device, identityKey));

// Reading the 409.
{
  const limit = deviceLimitOf(limitError(OLDEST));
  check(limit?.device.id === OLDEST.id, "the oldest device's id is read");
  check(limit?.device.created_at === OLDEST.created_at, "its link date is read");
  check(limit?.device.last_seen_at === OLDEST.last_seen_at, "its last activity is read");
  check(limit?.identityKey === KEY, "the account's identity key is read");
  check(deviceLimitOf(limitError(NEXT))?.device.last_seen_at === null, "a device never active has no last activity");
  check(
    deviceLimitOf(limitError({ ...NEXT, last_seen_at: null }))?.device.last_seen_at === null,
    "a null last activity is none",
  );
  check(deviceLimitOf(limitError()) === null, "an older server without oldest_device keeps the inline error");
  check(
    deviceLimitOf(limitError(OLDEST, null)) === null,
    "an account without a published key keeps the inline error: the phrase can't be checked",
  );
  check(deviceLimitOf(limitError(OLDEST, "")) === null, "an empty identity key is none");
  check(deviceLimitOf(limitError({ id: "", created_at: OLDEST.created_at })) === null, "an empty id is no device");
  check(deviceLimitOf(limitError({ id: OLDEST.id })) === null, "a device without a link date is no device");
  check(deviceLimitOf(limitError({ id: 7, created_at: "x" })) === null, "a number is not an id");
  check(
    deviceLimitOf(new ApiError("CONFLICT", "x", 409, limitBody(OLDEST))) === null,
    "another 409 is not the device limit",
  );
  check(
    deviceLimitOf(new ApiError("DEVICE_LIMIT", "x", 400, limitBody(OLDEST))) === null,
    "the limit comes as a 409 only",
  );
  check(deviceLimitOf(new Error("boom")) === null, "a plain error is not the device limit");
  check(deviceLimitOf(null) === null, "nothing is not the device limit");
}

// The phrase, checked against the key the 409 carried.
{
  check(phraseOpensAccount(PHRASE, KEY)?.join(" ") === PHRASE.join(" "), "the right phrase checks out");
  check(
    phraseOpensAccount(PHRASE.map((word) => ` ${word.toUpperCase()} `), KEY)?.join(" ") === PHRASE.join(" "),
    "it is normalized as the phrase step does",
  );
  check(phraseOpensAccount(OTHER, KEY) === null, "another valid phrase is the wrong phrase");
  check(phraseOpensAccount([...PHRASE.slice(0, 11), "abandon"], KEY) === null, "a bad checksum is the wrong phrase");
  check(phraseOpensAccount([...PHRASE.slice(0, 11), "notaword"], KEY) === null, "an unknown word is the wrong phrase");
  check(phraseOpensAccount(PHRASE.slice(0, 11), KEY) === null, "eleven words are the wrong phrase");
  check(phraseOpensAccount(PHRASE, "not base64!") === null, "a garbled key opens nothing");
}

/* --- the server ------------------------------------------------------------------------------ */

type Answer = { status: number; body: unknown };
const answers: Answer[] = [];
const sent: Record<string, unknown>[] = [];

globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
  check(String(input) === "/api/v1/auth/login", `only the login is asked (${String(input)})`);
  sent.push(JSON.parse(String(init?.body)) as Record<string, unknown>);
  const answer = answers.shift();
  if (!answer) throw new TypeError("no answer queued");
  return new Response(JSON.stringify(answer.body), {
    status: answer.status,
    headers: { "Content-Type": "application/json" },
  });
}) as typeof fetch;

const attempt: LoginAttempt = { username: "alice", password: "hunter22", deviceId: ANCHOR };

// The credentials step finds every slot signed in: on to the phrase step, no session, no question.
answers.push({ status: 409, body: limitBody(OLDEST) });
const first = await attemptLogin(attempt);
check(first.kind === "full" && first.device.id === OLDEST.id && first.identityKey === KEY, "a full account is read");
check(sent[0].device_id === ANCHOR, "the first login sends this browser's device anchor");
check(!("replace_device_id" in sent[0]), "the first login names no device to log out");
let state: DeviceLimitState = nextDeviceLimitState(null, { type: "login", attempt, outcome: first });
check(state !== null && state.words === null, "the login waits on the phrase step with the dialog closed");
check(state?.pending.attempt === attempt, "the login is kept to retry");

// Without a published key the 409 stays the credentials step's error.
answers.push({ status: 409, body: limitBody(OLDEST, null) });
const noKey = await attemptLogin(attempt);
check(noKey.kind === "error" && noKey.error instanceof ApiError && noKey.error.code === "DEVICE_LIMIT", "no key, inline error");
check(nextDeviceLimitState(null, { type: "login", attempt, outcome: noKey }) === null, "no key, nothing waits");

// A wrong phrase: no dialog, no request.
{
  const before = sent.length;
  const wrong = phraseOpensAccount(OTHER, state!.pending.identityKey);
  check(wrong === null, "the wrong phrase does not check out");
  check(sent.length === before, "a wrong phrase sends nothing");
}

// The right phrase opens the dialog; Cancel closes it and the login keeps waiting.
const words = phraseOpensAccount(PHRASE, state!.pending.identityKey)!;
state = nextDeviceLimitState(state, { type: "phrase", words });
check(state?.words === words && !state.busy, "the right phrase opens the dialog, idle");
const cancelled = nextDeviceLimitState(state, { type: "cancel" });
check(cancelled !== null && cancelled.words === null, "Cancel closes the dialog and stays on the phrase step");
check(cancelled?.pending === state?.pending, "Cancel keeps the login waiting");
check(nextDeviceLimitState(state, { type: "reset" }) === null, "Back to the credentials step drops it");
check(nextDeviceLimitState(cancelled, { type: "confirm" }) === cancelled, "nothing to confirm with the dialog closed");

// Confirm: busy, and Cancel does nothing meanwhile.
state = nextDeviceLimitState(state, { type: "confirm" });
check(state?.busy === true, "confirming makes the dialog busy");
check(nextDeviceLimitState(state, { type: "cancel" }) === state, "Cancel does nothing while busy");
const pending: PendingLogin = state!.pending;

// The retry names the device the user saw and hands the checked words on to the phrase step.
answers.push({ status: 200, body: SESSION });
const signedIn = await replaceOldestDevice(pending, words);
check(signedIn.kind === "session", "the retry signs in");
check(signedIn.kind === "session" && signedIn.words === words, "the phrase step finishes with the same words");
check(signedIn.kind === "session" && signedIn.session.user.username === "alice", "the typed username is kept locally");
const retry = sent[sent.length - 1];
check(retry.replace_device_id === OLDEST.id, "the retry names the device the user agreed to");
check(retry.device_id === ANCHOR, "the retry keeps the device anchor");
check(retry.username_hash === sent[0].username_hash, "the retry sends the same username");
check(retry.password === "hunter22", "the retry sends the same password");
check(nextDeviceLimitState(state, { type: "replaced", result: signedIn }) === null, "signing in ends the wait");

// That device went meanwhile and the account is full again: a new question, nobody signed out.
answers.push({ status: 409, body: limitBody(NEXT) });
const swapped = await replaceOldestDevice(pending, words);
check(swapped.kind === "swapped" && swapped.device.id === NEXT.id, "the device that is oldest now is offered");
{
  const next = nextDeviceLimitState(state, { type: "replaced", result: swapped });
  check(next?.pending.device.id === NEXT.id && next.words === words && !next.busy, "it swaps in, dialog open, idle");
  answers.push({ status: 200, body: SESSION });
  await replaceOldestDevice(next!.pending, words);
  check(sent[sent.length - 1].replace_device_id === NEXT.id, "confirming again names the new device");
}

// The account's key changed under the checked phrase: the wrong-phrase error, no dialog.
answers.push({ status: 409, body: limitBody(NEXT, OTHER_KEY) });
const rekeyed = await replaceOldestDevice(pending, words);
check(rekeyed.kind === "wrong-phrase", "another identity key is the wrong phrase");
{
  const next = nextDeviceLimitState(state, { type: "replaced", result: rekeyed });
  check(next !== null && next.words === null && next.pending.identityKey === OTHER_KEY, "the dialog closes; the phrase is checked against the new key");
}

// Any other error closes the dialog; the login keeps waiting on the phrase step.
answers.push({ status: 401, body: { error: { code: "INVALID_CREDENTIALS", message: "Wrong username or password." } } });
const wrongPassword = await replaceOldestDevice(pending, words);
check(
  wrongPassword.kind === "error" && wrongPassword.error instanceof ApiError && wrongPassword.error.message === "Wrong username or password.",
  "a wrong password is the inline error",
);
{
  const next = nextDeviceLimitState(state, { type: "replaced", result: wrongPassword });
  check(next !== null && next.words === null && !next.busy, "the dialog closes, the login still waits");
}
answers.push({ status: 409, body: limitBody() });
const oldServer = await replaceOldestDevice(pending, words);
check(oldServer.kind === "error", "a 409 without a device is the inline error");
const offline = await replaceOldestDevice(pending, words);
check(
  offline.kind === "error" && offline.error instanceof ApiError && offline.error.code === "transport",
  "no connection is the inline error",
);

// A browser without an anchor sends no device_id on the retry either.
answers.push({ status: 200, body: SESSION });
await replaceOldestDevice({ ...pending, attempt: { ...attempt, deviceId: null } }, words);
check(!("device_id" in sent[sent.length - 1]), "no anchor, no device_id");

// A normal login waits on nothing.
{
  const outcome: LoginOutcome = { kind: "session", session: SESSION };
  check(nextDeviceLimitState(null, { type: "login", attempt, outcome }) === null, "a free slot opens nothing");
  check(nextDeviceLimitState(null, { type: "phrase", words }) === null, "a phrase with nothing waiting opens nothing");
}

console.log("deviceLimit selftest passed");
