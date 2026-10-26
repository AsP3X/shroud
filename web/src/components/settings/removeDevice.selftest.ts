/**
 * DELETE /devices/{id} (security audit L4): the call carries the password, which answer the
 * confirmation shows, and a wrong password does not sign the browser out.
 * Run: `npx tsx src/components/settings/removeDevice.selftest.ts`.
 */
import { api, ApiError } from "../../api/client";
import { mapRemoveDeviceAnswer, REMOVE_DEVICE_PROMPT } from "./removeDevice";

function check(condition: boolean, message: string): void {
  if (!condition) throw new Error(message);
}

check(REMOVE_DEVICE_PROMPT === "Enter your password to confirm.", "the prompt");

/* --- the answer map ------------------------------------------------------------------------- */

check(mapRemoveDeviceAnswer(null) === "removed", "204 removes");
check(mapRemoveDeviceAnswer(new ApiError("NOT_FOUND", "gone", 404)) === "already-removed", "404 is already gone");
check(
  mapRemoveDeviceAnswer(new ApiError("INVALID_CREDENTIALS", "Wrong password.", 401, undefined, true)) ===
    "wrong-password",
  "a wrong password stays in the dialog",
);
check(
  mapRemoveDeviceAnswer(new ApiError("INVALID_CREDENTIALS", "Wrong password.", 401)) === "wrong-password",
  "the map keys off the code, not the flag",
);
check(mapRemoveDeviceAnswer(new ApiError("RATE_LIMITED", "Slow down.", 429)) === "rate-limited", "429");
check(mapRemoveDeviceAnswer(new ApiError("UNAUTHORIZED", "Signed out.", 401)) === "signed-out", "another 401");
check(
  mapRemoveDeviceAnswer(new ApiError("DEVICE_REMOVED", "This device was removed.", 401)) === "signed-out",
  "this browser removed meanwhile ends the session",
);
check(mapRemoveDeviceAnswer(new ApiError("http", "down", 500)) === "failed", "500");
check(mapRemoveDeviceAnswer(new ApiError("BAD_REQUEST", "no body", 400)) === "failed", "400");
check(mapRemoveDeviceAnswer(new ApiError("transport", "offline", 0)) === "failed", "offline");
check(mapRemoveDeviceAnswer(new TypeError("offline")) === "failed", "a transport throw");

/* --- the call itself ------------------------------------------------------------------------ */

Object.defineProperty(globalThis, "window", { value: globalThis, configurable: true });
Object.defineProperty(globalThis, "location", {
  value: { protocol: "https:", host: "chat.example" },
  configurable: true,
});

const PASSWORD = "not-a-real-password";
const DEVICE = "0f8fad5b-d9cb-469f-a165-70867728950e";
let answer: () => Response = () => new Response(null, { status: 204 });
const seen: { url: string; method: string; body: string; type: string | null }[] = [];
globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
  seen.push({
    url: String(input),
    method: init?.method ?? "GET",
    body: String(init?.body ?? ""),
    type: new Headers(init?.headers).get("Content-Type"),
  });
  return answer();
}) as typeof fetch;

await api.revokeDevice("token", DEVICE, PASSWORD);
check(seen.length === 1, "one request");
check(seen[0].url.endsWith(`/devices/${DEVICE}`) && seen[0].method === "DELETE", `${seen[0].method} ${seen[0].url}`);
check(seen[0].body === JSON.stringify({ password: PASSWORD }), "the body is the password and nothing else");
check(seen[0].type === "application/json", "sent as JSON");

answer = () =>
  new Response(JSON.stringify({ error: { code: "INVALID_CREDENTIALS", message: "Wrong password." } }), {
    status: 401,
    headers: { "Content-Type": "application/json" },
  });
try {
  await api.revokeDevice("token", DEVICE, PASSWORD);
  check(false, "a wrong password should throw");
} catch (err) {
  check(err instanceof ApiError, "the wrong password is an ApiError");
  const error = err as ApiError;
  check(error.keepsSession && !error.isAuthFailure, "this 401 does not sign the browser out");
  check(mapRemoveDeviceAnswer(error) === "wrong-password", "the dialog shows the wrong password");
  check(!error.message.includes(PASSWORD), "the error does not echo the password");
}

answer = () =>
  new Response(JSON.stringify({ error: { code: "UNAUTHORIZED", message: "Signed out." } }), { status: 401 });
try {
  await api.revokeDevice("token", DEVICE, PASSWORD);
  check(false, "a dead session should throw");
} catch (err) {
  const error = err as ApiError;
  check(error.isAuthFailure && !error.keepsSession, "any other 401 still signs out");
}

answer = () =>
  new Response(JSON.stringify({ error: { code: "RATE_LIMITED", message: "Slow down." } }), { status: 429 });
try {
  await api.revokeDevice("token", DEVICE, PASSWORD);
  check(false, "the rate limit should throw");
} catch (err) {
  check(mapRemoveDeviceAnswer(err) === "rate-limited" && !(err as ApiError).isAuthFailure, "429 keeps the session");
}

console.log("remove device selftest ok");
