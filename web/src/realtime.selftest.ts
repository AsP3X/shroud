/**
 * Which socket auth failures sign the browser out. Run: `npx tsx src/realtime.selftest.ts`.
 */
import { sessionEndedByAuthError } from "./realtime";

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

console.log("realtime selftest ok");
