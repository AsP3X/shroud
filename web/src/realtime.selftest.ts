/**
 * Which socket auth failures sign the browser out. Run: `npx tsx src/realtime.selftest.ts`.
 */
import {
  deviceRemovedByAuthError,
  fatalAuth,
  pageInForeground,
  sessionEndedByAuthError,
  socketAttention,
} from "./realtime";

function check(cond: boolean, message: string): void {
  if (!cond) throw new Error(message);
}

check(
  sessionEndedByAuthError({ error: { code: "UNAUTHORIZED", message: "This session was signed out." } }),
  "a revoked session ends it",
);
check(
  !sessionEndedByAuthError({ error: { code: "RATE_LIMITED", message: "Too many WebSocket connections for this account." } }),
  "too many sockets does not end it",
);
check(sessionEndedByAuthError({}), "an auth error with no code still ends it");
{
  const removed = { error: { code: "DEVICE_REMOVED", message: "This device was removed from your account." } };
  check(sessionEndedByAuthError(removed), "a removed device ends the session");
  check(deviceRemovedByAuthError(removed), "and says the device was removed");
  check(
    !deviceRemovedByAuthError({ error: { code: "UNAUTHORIZED", message: "This session was signed out." } }),
    "a plain sign-out is not a removal",
  );
  check(!deviceRemovedByAuthError({}), "no code is not a removal");
}
{
  const limited = { error: { code: "RATE_LIMITED", message: "Too many WebSocket connections for this account." } };
  check(fatalAuth(limited) === null, "too many sockets still does not end the session");
  const deleted = {
    error: { code: "DEVICE_REMOVED", message: "This account was deleted.", reason: "account_deleted" },
  };
  const deletedAuth = fatalAuth(deleted);
  check(
    deletedAuth?.deviceRemoved === true && deletedAuth.reason === "account_deleted",
    "a deleted account passes account_deleted",
  );
  const removedAuth = fatalAuth({
    error: { code: "DEVICE_REMOVED", message: "This device was removed from your account." },
  });
  check(removedAuth?.deviceRemoved === true && removedAuth.reason === null, "a removal without a reason stays one");
  const signedOut = fatalAuth({ error: { code: "UNAUTHORIZED", message: "This session was signed out." } });
  check(signedOut?.deviceRemoved === false && signedOut.reason === null, "a plain sign-out carries no removal reason");
  const otherReason = fatalAuth({
    error: { code: "DEVICE_REMOVED", message: "This device was removed from your account.", reason: "other" },
  });
  check(
    otherReason?.deviceRemoved === true && otherReason.reason === "other",
    "a reason other than account_deleted is passed through and is not rewritten",
  );
}
check(pageInForeground() === false, "without a document the page is not in front");
check(socketAttention(true, false) === "park", "a hidden tab closes so a push can arrive");
check(socketAttention(true, true) === "stay", "a call keeps its socket while the tab is hidden");
check(socketAttention(false, false) === "stay", "a visible tab stays connected");

console.log("realtime selftest ok");
