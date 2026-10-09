/**
 * A login that finds every device slot signed in: reading the 409, the phrase that has to
 * check out before anything is asked, the names it opens, choosing another device, the retry
 * that names the chosen one, the account's devices after the chosen one went, Cancel, and
 * other errors.
 * Run: `npx tsx src/deviceLimit.selftest.ts`.
 */
import { ApiError } from "./api/client";
import { bytesToB64 } from "./crypto/bytes";
import { USERNAME_ARGON2_ALICE, usernameHashB64 } from "./crypto/username";
import { deviceDisplayName, sealDeviceName } from "./crypto/deviceName";
import { historyKeyFromMnemonic, identityKeyFromMnemonic } from "./crypto/identity";
import {
  attemptLogin,
  deviceLimitOf,
  isOldestSelected,
  nextDeviceLimitState,
  openLimitNames,
  phraseOpensAccount,
  replaceSelectedDevice,
  selectedDevice,
  type DeviceLimitState,
  type LoginAttempt,
  type LoginOutcome,
} from "./deviceLimit";

function check(condition: boolean, message: string): void {
  if (!condition) throw new Error(message);
}

Object.defineProperty(globalThis, "window", { value: globalThis, configurable: true });

const PHRASE = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ");
const OTHER = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong".split(" ");
const KEY = bytesToB64(identityKeyFromMnemonic(PHRASE));
const OTHER_KEY = bytesToB64(identityKeyFromMnemonic(OTHER));
const HISTORY = historyKeyFromMnemonic(PHRASE);

const IPHONE_ID = "0b6f3c1e-4a51-4d5e-9c7a-2f1e8d3b6a90";
const LAPTOP_ID = "7d2a9e44-1c3b-4f6a-8e5d-0a9b8c7d6e5f";
const UNNAMED_ID = "3e4f5a6b-7c8d-4e9f-8a0b-1c2d3e4f5a6b";
const FOREIGN_ID = "9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d";
/** The app's own seal, as Settings › Devices writes it. */
const IPHONE = {
  id: IPHONE_ID,
  sealed_name: sealDeviceName(HISTORY, IPHONE_ID, { name: "Niklas’s iPhone", kind: "iphone" }),
  created_at: "2025-03-12T09:30:00Z",
  last_seen_at: "2026-09-30T18:02:00Z",
};
const LAPTOP = {
  id: LAPTOP_ID,
  sealed_name: sealDeviceName(HISTORY, LAPTOP_ID, { name: "Work laptop", kind: "web", custom: true }),
  created_at: "2025-06-01T12:00:00Z",
  last_seen_at: "2026-10-02T08:00:00Z",
};
const UNNAMED = { id: UNNAMED_ID, created_at: "2025-07-01T12:00:00Z" };
/** Sealed with another account's phrase: it doesn't open. */
const FOREIGN = {
  id: FOREIGN_ID,
  sealed_name: sealDeviceName(historyKeyFromMnemonic(OTHER), FOREIGN_ID, { name: "Not yours", kind: "android" }),
  created_at: "2025-08-01T12:00:00Z",
  last_seen_at: "2026-10-05T08:00:00Z",
};
const DEVICES = [IPHONE, LAPTOP, UNNAMED, FOREIGN];

const ANCHOR = "5c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f";
const SESSION = {
  token: "t",
  user: { id: "u", username: "ignored", share_code: "s" },
  device: { id: "d" },
};

const limitBody = (
  devices: object[] | null,
  { identityKey = KEY as string | null, oldest = devices?.[0] as object | undefined } = {},
) => ({
  error: {
    code: "DEVICE_LIMIT",
    message: "This account already has the maximum number of devices (5). Remove a device and try again.",
  },
  ...(oldest ? { oldest_device: oldest } : {}),
  ...(devices ? { devices } : {}),
  ...(identityKey !== null ? { identity_key: identityKey } : {}),
});
const limitError = (devices: object[] | null, options: Parameters<typeof limitBody>[1] = {}) =>
  new ApiError("DEVICE_LIMIT", "full", 409, limitBody(devices, options));

// Reading the 409.
{
  const limit = deviceLimitOf(limitError(DEVICES));
  check(limit?.devices.map((device) => device.id).join() === DEVICES.map((device) => device.id).join(), "every device, in server order");
  check(limit?.devices[0].sealed_name === IPHONE.sealed_name, "the sealed name is read");
  check(limit?.devices[0].created_at === IPHONE.created_at, "the link date is read");
  check(limit?.devices[0].last_seen_at === IPHONE.last_seen_at, "the last activity is read");
  check(limit?.devices[2].sealed_name === null && limit.devices[2].last_seen_at === null, "a device never named nor active");
  check(limit?.identityKey === KEY, "the account's identity key is read");
  const old = deviceLimitOf(limitError(null, { oldest: IPHONE }));
  check(old?.devices.length === 1 && old.devices[0].id === IPHONE_ID, "an older server's oldest_device is a list of one");
  const emptyList = deviceLimitOf(limitError([], { oldest: IPHONE }));
  check(emptyList?.devices.length === 1, "an empty list falls back to oldest_device");
  check(
    deviceLimitOf(limitError([IPHONE, { id: "" }, { id: 7, created_at: "x" }, LAPTOP]))?.devices.length === 2,
    "malformed entries are dropped",
  );
  check(
    deviceLimitOf(limitError(DEVICES, { oldest: { ...UNNAMED, sealed_name: "" } }))?.devices.length === DEVICES.length,
    "the list wins over oldest_device",
  );
  check(deviceLimitOf(limitError(null, { oldest: undefined })) === null, "no device at all keeps the inline error");
  check(
    deviceLimitOf(limitError(DEVICES, { identityKey: null })) === null,
    "an account without a published key keeps the inline error: the phrase can't be checked",
  );
  check(deviceLimitOf(limitError(DEVICES, { identityKey: "" })) === null, "an empty identity key is none");
  check(deviceLimitOf(new ApiError("CONFLICT", "x", 409, limitBody(DEVICES))) === null, "another 409 is not the limit");
  check(deviceLimitOf(new ApiError("DEVICE_LIMIT", "x", 400, limitBody(DEVICES))) === null, "the limit comes as a 409 only");
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

// The names, opened with the phrase's history key.
{
  const devices = deviceLimitOf(limitError(DEVICES))!.devices;
  const labels = openLimitNames(devices, PHRASE);
  check(labels[IPHONE_ID]?.name === "Niklas’s iPhone" && labels[IPHONE_ID]?.kind === "iphone", "the iPhone's name and kind open");
  check(labels[LAPTOP_ID]?.name === "Work laptop" && labels[LAPTOP_ID]?.kind === "web", "a typed name opens");
  check(labels[UNNAMED_ID] === null, "a device never named has no label");
  check(labels[FOREIGN_ID] === null, "a name sealed with another phrase doesn't open");
  check(deviceDisplayName(labels[UNNAMED_ID]) === "Unnamed device", "no label reads Unnamed device");
  check(deviceDisplayName(labels[FOREIGN_ID]) === "Unnamed device", "an unopenable name reads Unnamed device");
  check(openLimitNames(devices, OTHER)[IPHONE_ID] === null, "another phrase opens no names");
  check(
    openLimitNames([{ ...devices[0], id: LAPTOP_ID }], PHRASE)[LAPTOP_ID] === null,
    "a name moved onto another device doesn't open",
  );
}

/* --- the server ------------------------------------------------------------------------------ */

type Answer = { status: number; body: unknown };
const answers: Answer[] = [];
const sent: Record<string, unknown>[] = [];

globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
  const url = String(input);
  if (url === "/api/v1/auth/username-kdf") {
    return new Response(
      JSON.stringify({
        algorithm: "argon2id",
        version: 19,
        salt: "ABEiM0RVZneImaq7zN3u/w==",
        memory_kib: 65536,
        iterations: 8,
        parallelism: 1,
        output_bytes: 32,
      }),
      { status: 200, headers: { "Content-Type": "application/json" } },
    );
  }
  check(url === "/api/v1/auth/login", `only the login is asked (${url})`);
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
answers.push({ status: 409, body: limitBody(DEVICES) });
const first = await attemptLogin(attempt);
check(first.kind === "full" && first.devices.length === 4 && first.identityKey === KEY, "a full account is read");
check(sent[0].username_hash === USERNAME_ARGON2_ALICE, "login sends the slow hash");
check(sent[0].legacy_username_hash === usernameHashB64("alice"), "the first login still sends the old hash");
check(sent[0].device_id === ANCHOR, "the first login sends this browser's device anchor");
check(!("replace_device_id" in sent[0]), "the first login names no device to log out");
let state: DeviceLimitState = nextDeviceLimitState(null, { type: "login", attempt, outcome: first });
check(state !== null && state.words === null, "the login waits on the phrase step with the dialog closed");
check(state?.pending.attempt === attempt, "the login is kept to retry");
check(state?.pending.selectedId === IPHONE_ID, "the least recently active device is selected");
check(Object.keys(state?.labels ?? {}).length === 0, "no names before the phrase");

// Without a published key the 409 stays the credentials step's error.
answers.push({ status: 409, body: limitBody(DEVICES, { identityKey: null }) });
const noKey = await attemptLogin(attempt);
check(noKey.kind === "error" && noKey.error instanceof ApiError && noKey.error.code === "DEVICE_LIMIT", "no key, inline error");
check(nextDeviceLimitState(null, { type: "login", attempt, outcome: noKey }) === null, "no key, nothing waits");

// A wrong phrase: no dialog, no request.
{
  const before = sent.length;
  check(phraseOpensAccount(OTHER, state!.pending.identityKey) === null, "the wrong phrase does not check out");
  check(sent.length === before, "a wrong phrase sends nothing");
}

// The right phrase opens the dialog with the names it opens.
const words = phraseOpensAccount(PHRASE, state!.pending.identityKey)!;
state = nextDeviceLimitState(state, { type: "phrase", words });
check(state?.words === words && !state.busy && !state.picking, "the right phrase opens the dialog, idle");
check(state?.labels[IPHONE_ID]?.name === "Niklas’s iPhone", "the oldest device shows its name");
check(isOldestSelected(state!.pending), "the oldest device is asked about: \"Log out your oldest device?\"");

// Cancel closes it and the login keeps waiting; Back drops it.
{
  const cancelled = nextDeviceLimitState(state, { type: "cancel" });
  check(cancelled !== null && cancelled.words === null, "Cancel closes the dialog and stays on the phrase step");
  check(cancelled?.pending === state?.pending, "Cancel keeps the login waiting");
  check(nextDeviceLimitState(cancelled, { type: "confirm" }) === cancelled, "nothing to confirm with the dialog closed");
  check(nextDeviceLimitState(cancelled, { type: "pick" }) === cancelled, "no picker with the dialog closed");
  check(nextDeviceLimitState(state, { type: "reset" }) === null, "Back to the credentials step drops it");
}

// The picker: Cancel keeps the selection, a row selects and goes back.
{
  const picking = nextDeviceLimitState(state, { type: "pick" });
  check(picking?.picking === true, "Choose Another Device opens the picker");
  check(nextDeviceLimitState(picking, { type: "confirm" }) === picking, "no confirming from the picker");
  const unpicked = nextDeviceLimitState(picking, { type: "unpick" });
  check(unpicked?.picking === false && unpicked.pending.selectedId === IPHONE_ID, "the picker's Cancel keeps the selection");
  check(nextDeviceLimitState(picking, { type: "select", id: "nope" }) === picking, "an unknown id selects nothing");
  const chosen = nextDeviceLimitState(picking, { type: "select", id: LAPTOP_ID });
  check(chosen?.picking === false && chosen.pending.selectedId === LAPTOP_ID, "a row selects and goes back to the question");
  check(selectedDevice(chosen!.pending).id === LAPTOP_ID, "the dialog shows the chosen device");
  check(!isOldestSelected(chosen!.pending), "another device is asked about: \"Log out this device?\"");
  check(nextDeviceLimitState(chosen, { type: "select", id: IPHONE_ID }) === chosen, "rows select only from the picker");
  state = chosen;
}
{
  const alone = deviceLimitOf(limitError(null, { oldest: IPHONE }))!;
  const outcome: LoginOutcome = { kind: "full", ...alone };
  const one = nextDeviceLimitState(nextDeviceLimitState(null, { type: "login", attempt, outcome }), { type: "phrase", words });
  check(nextDeviceLimitState(one, { type: "pick" }) === one, "a single device has nothing to choose from");
}

// Confirm: busy, Cancel and the picker do nothing meanwhile.
state = nextDeviceLimitState(state, { type: "confirm" });
check(state?.busy === true, "confirming makes the dialog busy");
check(nextDeviceLimitState(state, { type: "cancel" }) === state, "Cancel does nothing while busy");
check(nextDeviceLimitState(state, { type: "pick" }) === state, "Choose Another Device does nothing while busy");
const pending = state!.pending;

// The retry names the chosen device and hands the checked words on to the phrase step.
answers.push({ status: 200, body: SESSION });
const signedIn = await replaceSelectedDevice(pending, words);
check(signedIn.kind === "session", "the retry signs in");
check(signedIn.kind === "session" && signedIn.words === words, "the phrase step finishes with the same words");
check(signedIn.kind === "session" && signedIn.session.user.username === "alice", "the typed username is kept locally");
const retry = sent[sent.length - 1];
check(retry.replace_device_id === LAPTOP_ID, "the retry names the device the user chose");
check(retry.device_id === ANCHOR, "the retry keeps the device anchor");
check(retry.username_hash === sent[0].username_hash, "the retry sends the same username");
check(retry.password === "hunter22", "the retry sends the same password");
check(nextDeviceLimitState(state, { type: "replaced", result: signedIn }) === null, "signing in ends the wait");

// Full again, the chosen device still there (another one went and a new one came): kept.
answers.push({ status: 409, body: limitBody([LAPTOP, UNNAMED, FOREIGN]) });
const kept = await replaceSelectedDevice(pending, words);
check(kept.kind === "full-again" && kept.devices.length === 3, "the account's devices now are listed");
{
  const next = nextDeviceLimitState(state, { type: "replaced", result: kept });
  check(next?.pending.selectedId === LAPTOP_ID && !next.busy && next.words === words, "the choice is kept, idle again");
  check(next?.pending.devices[0].id === LAPTOP_ID && isOldestSelected(next.pending), "it is the oldest now");
  check(next?.labels[LAPTOP_ID]?.name === "Work laptop", "the new list's names open with the same phrase");
  check(next?.labels[IPHONE_ID] === undefined, "a device no longer listed has no label");
}

// The chosen device went: the selection resets to the oldest now, nobody signed out unseen.
answers.push({ status: 409, body: limitBody([UNNAMED, IPHONE, FOREIGN]) });
const gone = await replaceSelectedDevice(pending, words);
{
  const next = nextDeviceLimitState(state, { type: "replaced", result: gone });
  check(next?.pending.selectedId === UNNAMED_ID, "the new oldest device is selected");
  check(next !== null && deviceDisplayName(next.labels[UNNAMED_ID]) === "Unnamed device", "and shows as Unnamed device");
  answers.push({ status: 200, body: SESSION });
  await replaceSelectedDevice(next!.pending, words);
  check(sent[sent.length - 1].replace_device_id === UNNAMED_ID, "confirming again names the new selection");
}

// The account's key changed under the checked phrase: the wrong-phrase error, no dialog.
answers.push({ status: 409, body: limitBody(DEVICES, { identityKey: OTHER_KEY }) });
const rekeyed = await replaceSelectedDevice(pending, words);
check(rekeyed.kind === "wrong-phrase", "another identity key is the wrong phrase");
{
  const next = nextDeviceLimitState(state, { type: "replaced", result: rekeyed });
  check(next !== null && next.words === null && next.pending.identityKey === OTHER_KEY, "the dialog closes; the phrase is checked against the new key");
  check(next?.pending.selectedId === IPHONE_ID && Object.keys(next.labels).length === 0, "the names and the choice start over");
}

// Any other error closes the dialog; the login keeps waiting on the phrase step.
answers.push({ status: 401, body: { error: { code: "INVALID_CREDENTIALS", message: "Wrong username or password." } } });
const wrongPassword = await replaceSelectedDevice(pending, words);
check(
  wrongPassword.kind === "error" && wrongPassword.error instanceof ApiError && wrongPassword.error.message === "Wrong username or password.",
  "a wrong password is the inline error",
);
{
  const next = nextDeviceLimitState(state, { type: "replaced", result: wrongPassword });
  check(next !== null && next.words === null && !next.busy && next.pending.selectedId === LAPTOP_ID, "the dialog closes, the login still waits");
}
answers.push({ status: 409, body: limitBody(null, { oldest: undefined }) });
const oldServer = await replaceSelectedDevice(pending, words);
check(oldServer.kind === "error", "a 409 without a device is the inline error");
const offline = await replaceSelectedDevice(pending, words);
check(
  offline.kind === "error" && offline.error instanceof ApiError && offline.error.code === "transport",
  "no connection is the inline error",
);

// A browser without an anchor sends no device_id on the retry either.
answers.push({ status: 200, body: SESSION });
await replaceSelectedDevice({ ...pending, attempt: { ...attempt, deviceId: null } }, words);
check(!("device_id" in sent[sent.length - 1]), "no anchor, no device_id");

// A normal login waits on nothing.
{
  const outcome: LoginOutcome = { kind: "session", session: SESSION };
  check(nextDeviceLimitState(null, { type: "login", attempt, outcome }) === null, "a free slot opens nothing");
  check(nextDeviceLimitState(null, { type: "phrase", words }) === null, "a phrase with nothing waiting opens nothing");
}

console.log("deviceLimit selftest passed");
