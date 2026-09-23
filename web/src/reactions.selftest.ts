import {
  applyReaction,
  applyReactionChanges,
  isSingleEmoji,
  parseReaction,
  reactionChips,
  reactionPayload,
  replacingReaction,
  type Reaction,
} from "./reactions";
import type { ChatMessage } from "./messaging";

/*
 * Reactions selftest: the sealed payload (the same strings `ios/shroudTests/MessageReactionTests`
 * reads) and the last-write-wins rules. Run bundled:
 * `npx esbuild src/reactions.selftest.ts --bundle --platform=node --format=esm | node --input-type=module`.
 */

const MESSAGE = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
const ME = "aaaaaaaa-0000-4000-8000-000000000001";
const PEER = "bbbbbbbb-0000-4000-8000-000000000002";

function check(ok: boolean, what: string): void {
  if (!ok) throw new Error(what);
}

/* --- wire ---------------------------------------------------------------- */

const wire = reactionPayload("🔥", MESSAGE.toUpperCase());
check(wire === `{"t":"reaction","r":"${MESSAGE}","e":["🔥"]}`, "payload: lowercased id, one emoji");
check(parseReaction(wire, MESSAGE) === "🔥", "payload round-trips");
check(parseReaction(wire, PEER) === null, "a record moved onto another message is dropped");
check(parseReaction(`{"t":"reaction","r":"${MESSAGE}","e":["❤️","👍"]}`, MESSAGE) === "❤️", "first of several");
check(parseReaction(`{"t":"transcript","r":"${MESSAGE}","c":"x"}`, MESSAGE) === null, "not a reaction");
check(parseReaction("not json", MESSAGE) === null, "garbage");

for (const emoji of ["❤️", "🔥", "👍🏽", "❤️‍🔥", "👨‍💻", "🇩🇪", "1️⃣", "🫡"]) {
  check(isSingleEmoji(emoji), `emoji ${emoji}`);
}
for (const text of ["", "a", "1", "ok", "🔥🔥", "❤", " 👍"]) {
  check(!isSingleEmoji(text), `not an emoji: "${text}"`);
}

/* --- merge --------------------------------------------------------------- */

const r = (userId: string, emoji: string | null, seq: number, pending = false): Reaction => ({
  userId,
  emoji,
  seq,
  ...(pending ? { pending } : {}),
});

const replaced = applyReaction([r(PEER, "❤️", 5)], r(PEER, "🔥", 7));
check(replaced?.[0].emoji === "🔥", "newer change wins");
check(applyReaction(replaced!, r(PEER, "👍", 6)) === null, "late older change is ignored");
check(applyReaction(replaced!, r(PEER, "🔥", 7)) === null, "a replay changes nothing");

const removed = applyReaction([r(PEER, "❤️", 5)], r(PEER, null, 9))!;
check(reactionChips(removed, ME).length === 0, "removal hides the chip");
check(applyReaction(removed, r(PEER, "❤️", 5)) === null, "removal cannot be resurrected");

check(applyReaction([r(ME, "👍", 3, true)], r(ME, "😢", 8)) === null, "our pending tap outlives events");
check(replacingReaction([r(ME, "👍", 3, true)], ME, r(ME, "👍", 10))[0].pending === undefined, "ack replaces");

const chips = reactionChips([r(PEER, "❤️", 2), r(ME, "❤️", 3), r(PEER.replace("b", "c"), "🔥", 4)], ME);
check(chips.length === 2 && chips[0].userIds.length === 2 && chips[0].includesMe, "chips group by emoji");

const thread = [{ id: MESSAGE, deleted: false, reactions: [] } as unknown as ChatMessage];
const next = applyReactionChanges(thread, [{ messageId: MESSAGE.toUpperCase(), reaction: r(PEER, "😮", 11) }]);
check(next !== thread && next[0].reactions?.[0].emoji === "😮", "changes fold into the thread");
check(applyReactionChanges(next, [{ messageId: MESSAGE, reaction: r(PEER, "😮", 11) }]) === next, "no-op keeps identity");

console.log("reactions selftest: ok");
