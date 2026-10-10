/**
 * A deleted account shows no presence. Run: `npx tsx src/format.presence.selftest.ts`.
 */
import { visiblePresence, type Presence } from "./format";

function check(condition: boolean, message: string): void {
  if (!condition) throw new Error(message);
}

const online: Presence = { online: true, lastSeenAt: "2026-10-10T12:00:00.000Z" };

check(visiblePresence(true, online) === undefined, "a deleted account drops a stale online");
check(visiblePresence(true, undefined) === undefined, "a deleted account with nothing stays nothing");
check(visiblePresence(false, online)?.online === true, "a live contact keeps online");
check(visiblePresence(false, undefined) === undefined, "nothing known stays nothing");

console.log("format presence selftest ok");
