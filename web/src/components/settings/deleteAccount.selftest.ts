/**
 * DELETE /auth/account: which answer the screen shows, and a wrong password does not sign
 * the browser out. Run: `npx tsx src/components/settings/deleteAccount.selftest.ts`.
 */
import { api, ApiError } from "../../api/client";
import { DELETE_ACCOUNT_COPY, mapDeleteAccountAnswer } from "./deleteAccount";

function check(condition: boolean, message: string): void {
  if (!condition) throw new Error(message);
}

/* --- the copy, byte for byte (§3.3, curly quotes and the ellipsis) -------------------------- */

check(DELETE_ACCOUNT_COPY.rowTitle === "Delete Account", "C1");
check(DELETE_ACCOUNT_COPY.rowSubtitle === "Deletes your account and erases it from every device.", "C2");
check(DELETE_ACCOUNT_COPY.title === "Delete your account?", "C3");
check(
  DELETE_ACCOUNT_COPY.consequences[0] ===
    "Your messages are replaced with \u201cMessage deleted\u201d for everyone.",
  "C4 message, curly quotes",
);
check(
  DELETE_ACCOUNT_COPY.consequences[1] ===
    "Contacts who let you clear chats for them lose those chats. Everyone else keeps their own messages.",
  "C4 chats",
);
check(
  DELETE_ACCOUNT_COPY.consequences[2] ===
    "Your contacts, Saved Messages, photos, files and call history are deleted.",
  "C4 contacts",
);
check(
  DELETE_ACCOUNT_COPY.consequences[3] ===
    "Your username and share code are released, so someone else can take them.",
  "C4 username",
);
check(
  DELETE_ACCOUNT_COPY.consequences[4] === "Every device signed in to this account is signed out and erased.",
  "C4 devices",
);
check(DELETE_ACCOUNT_COPY.consequences.length === 5, "C4 is five lines");
check(DELETE_ACCOUNT_COPY.confirm === "This can't be undone. Enter your password to confirm.", "C5");
check(DELETE_ACCOUNT_COPY.passwordLabel === "Password", "C5a label");
check(DELETE_ACCOUNT_COPY.passwordPlaceholder === "Your account password", "C5a placeholder");
check(DELETE_ACCOUNT_COPY.submit === "Delete Account" && DELETE_ACCOUNT_COPY.cancel === "Cancel", "C6");
check(DELETE_ACCOUNT_COPY.submitting === "Deleting\u2026", "C6 ellipsis");
check(DELETE_ACCOUNT_COPY.wrongPassword === "That password isn't right.", "C7");
check(DELETE_ACCOUNT_COPY.rateLimited === "Too many tries. Try again later.", "C8");
check(
  DELETE_ACCOUNT_COPY.unreachable === "Couldn't reach the server. Your account wasn't deleted.",
  "C9",
);

/* --- the answer map (§3.2) ------------------------------------------------------------------ */

const wrong = new ApiError("INVALID_CREDENTIALS", "Wrong password.", 401, undefined, true);
check(mapDeleteAccountAnswer(null) === "deleted", "204 wipes as the account deleted");
check(mapDeleteAccountAnswer(wrong) === "wrong-password", "a wrong password stays on the screen");
check(!wrong.isAuthFailure && wrong.keepsSession, "that 401 does not count as a dead session");
check(
  mapDeleteAccountAnswer(new ApiError("INVALID_CREDENTIALS", "Wrong password.", 401)) === "wrong-password",
  "the map keys off the code, not the flag",
);

const removed = new ApiError(
  "DEVICE_REMOVED",
  "This device was removed from your account.",
  401,
  { error: { code: "DEVICE_REMOVED", message: "This device was removed from your account." } },
);
check(removed.isDeviceRemoved && !removed.isAccountDeleted && removed.reason === null, "no reason is a removal");
check(removed.isAuthFailure, "a removal is still a 401");
check(mapDeleteAccountAnswer(removed) === "wiped", "this call wipes a removal with no reason as account deleted");

const deleted = new ApiError("DEVICE_REMOVED", "This account was deleted.", 401, {
  error: { code: "DEVICE_REMOVED", message: "This account was deleted.", reason: "account_deleted" },
});
check(deleted.isDeviceRemoved && deleted.isAccountDeleted && deleted.reason === "account_deleted", "reason is read");
check(mapDeleteAccountAnswer(deleted) === "wiped", "account_deleted on this call wipes as account deleted");

const otherReason = new ApiError("DEVICE_REMOVED", "This device was removed from your account.", 401, {
  error: { code: "DEVICE_REMOVED", message: "This device was removed from your account.", reason: "other" },
});
check(!otherReason.isAccountDeleted && otherReason.reason === "other", "another reason is not account_deleted");
check(mapDeleteAccountAnswer(otherReason) === "wiped", "any reason on this call still wipes as account deleted");

const signedOut = new ApiError("UNAUTHORIZED", "This session was signed out.", 401);
check(signedOut.isAuthFailure && !signedOut.isDeviceRemoved && mapDeleteAccountAnswer(signedOut) === "signed-out", "a normal 401 still signs out");

check(mapDeleteAccountAnswer(new ApiError("RATE_LIMITED", "Slow down.", 429)) === "rate-limited", "429 is C8");
check(mapDeleteAccountAnswer(new ApiError("http", "down", 500)) === "unreachable", "500 is C9");
check(mapDeleteAccountAnswer(new ApiError("http", "down", 503)) === "unreachable", "503 is C9");
check(mapDeleteAccountAnswer(new ApiError("http", "timeout", 408)) === "unreachable", "408 is C9");
check(mapDeleteAccountAnswer(new ApiError("transport", "offline", 0)) === "unreachable", "status 0 is C9");
check(mapDeleteAccountAnswer(new ApiError("timeout", "", 0)) === "unreachable", "a timeout is C9");
check(mapDeleteAccountAnswer(new ApiError("http", "no", 400)) === "unreachable", "another failure leaves the account");
check(mapDeleteAccountAnswer(new TypeError("offline")) === "unreachable", "a transport throw is C9");
check(new ApiError("NOT_FOUND", "gone", 404).reason === null, "an error without a reason field is null");

/* --- the call itself: the flag is only on this wrong password ------------------------------- */

Object.defineProperty(globalThis, "window", { value: globalThis, configurable: true });
Object.defineProperty(globalThis, "location", {
  value: { protocol: "https:", host: "chat.example" },
  configurable: true,
});

const PASSWORD = "not-a-real-password";
let answer: () => Response = () => new Response(null, { status: 204 });
const seen: { url: string; method: string; body: string }[] = [];
globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
  seen.push({ url: String(input), method: init?.method ?? "GET", body: String(init?.body ?? "") });
  return answer();
}) as typeof fetch;

seen.length = 0;
await api.deleteAccount("token", PASSWORD);
check(seen.length === 1, "one request");
check(seen[0].url.endsWith("/auth/account") && seen[0].method === "DELETE", `DELETE /auth/account: ${seen[0].method} ${seen[0].url}`);
check(seen[0].body === JSON.stringify({ password: PASSWORD }), "the body is the password and nothing else");

answer = () =>
  new Response(JSON.stringify({ error: { code: "INVALID_CREDENTIALS", message: "Wrong password." } }), {
    status: 401,
    headers: { "Content-Type": "application/json" },
  });
try {
  await api.deleteAccount("token", PASSWORD);
  check(false, "a wrong password should throw");
} catch (err) {
  check(err instanceof ApiError, "the wrong password is an ApiError");
  const error = err as ApiError;
  check(error.keepsSession && !error.isAuthFailure, "this 401 does not sign the browser out");
  check(!error.isDeviceRemoved && !error.isAccountDeleted, "a wrong password is not a removal");
  check(mapDeleteAccountAnswer(error) === "wrong-password", "the screen shows C7");
  check(!error.message.includes(PASSWORD), "the error does not echo the password");
}

answer = () =>
  new Response(JSON.stringify({ error: { code: "INVALID_CREDENTIALS", message: "Wrong password." } }), {
    status: 401,
  });
try {
  await api.logout("token");
  check(false, "logout's 401 should throw");
} catch (err) {
  const error = err as ApiError;
  check(error.isAuthFailure && !error.keepsSession, "the same code on another call still signs out");
}

answer = () =>
  new Response(
    JSON.stringify({
      error: { code: "DEVICE_REMOVED", message: "This account was deleted.", reason: "account_deleted" },
    }),
    { status: 401 },
  );
try {
  await api.deleteAccount("token", PASSWORD);
  check(false, "a deleted account should throw");
} catch (err) {
  const error = err as ApiError;
  check(error.isAuthFailure && error.isDeviceRemoved && error.isAccountDeleted, "the reason is on the error");
  check(!error.keepsSession, "a removal is not the kept-session 401");
  check(mapDeleteAccountAnswer(error) === "wiped", "the screen wipes as account deleted");
}

answer = () => {
  throw new TypeError("offline");
};
try {
  await api.deleteAccount("token", PASSWORD);
  check(false, "offline should throw");
} catch (err) {
  const error = err as ApiError;
  check(error.status === 0 && mapDeleteAccountAnswer(error) === "unreachable", "offline is C9 and not a sign-out");
  check(!error.message.includes(PASSWORD), "a network error does not include the password");
}

console.log("delete account selftest ok");
