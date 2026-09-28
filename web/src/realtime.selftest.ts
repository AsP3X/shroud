/**
 * Which socket auth failures sign the browser out. Run: `npx tsx src/realtime.selftest.ts`.
 */
import { deviceRemovedByAuthError, pageInForeground, sessionEndedByAuthError, socketAttention } from "./realtime";

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
check(pageInForeground() === false, "without a document the page is not in front");
check(socketAttention(true, false) === "park", "a hidden tab closes so a push can arrive");
check(socketAttention(true, true) === "stay", "a call keeps its socket while the tab is hidden");
check(socketAttention(false, false) === "stay", "a visible tab stays connected");

console.log("realtime selftest ok");
