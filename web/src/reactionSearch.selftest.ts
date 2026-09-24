import { ALL_REACTIONS } from "./reactions";
import { editDistance, keywordScore, normalizeQuery, REACTION_KEYWORDS, searchReactions } from "./reactionSearch";

/*
 * Reaction search selftest: the vectors `ios/shroudTests/ReactionSearchTests` checks too, so a
 * query finds the same emoji in the same order on both clients. Run bundled:
 * `npx esbuild src/reactionSearch.selftest.ts --bundle --platform=node --format=esm | node --input-type=module`.
 */

function check(ok: boolean, what: string): void {
  if (!ok) throw new Error(what);
}

function same(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((x, i) => x === b[i]);
}

/* --- the table ------------------------------------------------------------ */

check(
  ALL_REACTIONS.every((emoji) => (REACTION_KEYWORDS[emoji]?.length ?? 0) > 0),
  "every reaction has keywords",
);
check(
  Object.keys(REACTION_KEYWORDS).every((emoji) => ALL_REACTIONS.includes(emoji)),
  "no keywords for an emoji outside the set",
);

/* --- normalising ------------------------------------------------------------ */

check(normalizeQuery("  Star-Struck ") === "star struck", "hyphens and case");
check(normalizeQuery("thank\t you") === "thank you", "inner whitespace");

/* --- edit distance ------------------------------------------------------------ */

check(editDistance("thnaks", "thanks", 2) === 1, "an adjacent swap is one edit");
check(editDistance("kitten", "sitting", 3) === 3, "kitten/sitting");
check(editDistance("abc", "abcd", 1) === 1, "one insertion");
check(editDistance("abc", "xyz", 1) === 2, "capped at limit + 1");
check(editDistance("👍", "👍", 1) === 0, "code points, not UTF-16 units");

/* --- one keyword ---------------------------------------------------------------- */

check(keywordScore("fire", "fire") === 100, "exact");
check(keywordScore("fir", "fire") === 90, "prefix");
check(keywordScore("heart", "broken heart") === 80, "word prefix");
check(keywordScore("ok", "look") === 70, "substring");
check(keywordScore("hndrd", "hundred points") === 50, "subsequence");
check(keywordScore("thnaks", "thanks") === 30, "one typo");
check(keywordScore("xx", "look") === 0, "two letters never match by subsequence or typo");
check(keywordScore("zzzz", "zany face") === 0, "no match");

/* --- the picker's queries -------------------------------------------------------- */

check(same(searchReactions(""), ALL_REACTIONS), "empty query is the whole set in order");
check(same(searchReactions("   "), ALL_REACTIONS), "blank query too");
check(searchReactions("fire")[0] === "🔥", "fire");
check(same(searchReactions("fire"), ["🔥", "❤️‍🔥"]), "fire finds the burning heart after the flame");
check(searchReactions("lol")[0] === "🤣", "lol");
check(searchReactions("LOL")[0] === "🤣", "case does not matter");
check(same(searchReactions("thanks").slice(0, 2), ["🙏", "🤝"]), "ties keep the set's order");
check(searchReactions("thnaks")[0] === "🙏", "a typo still finds thanks");
check(searchReactions("hndrd")[0] === "💯", "letters in order find hundred");
check(searchReactions("100")[0] === "💯", "100");
check(searchReactions("heart")[0] === "❤️", "heart is the red heart first");
check(searchReactions("heart").includes("💔"), "…and the broken one");
check(same(searchReactions("red heart"), ["❤️"]), "every word has to match");
check(same(searchReactions("monkey").slice(0, 3), ["🙈", "🙉", "🙊"]), "the three monkeys first");
check(searchReactions("cat")[0] === "😺" && searchReactions("cat").includes("🐱"), "the wider set: cats");
check(searchReactions("check").includes("✅"), "the wider set: check mark");
check(searchReactions("grinning").includes("😀"), "Unicode names are searchable");
check(ALL_REACTIONS.length > 300, "the expanded picker offers a wide set");
check(new Set(ALL_REACTIONS).size === ALL_REACTIONS.length, "no emoji twice");
check(same(searchReactions("star struck").slice(0, 1), ["🤩"]), "a hyphenated name matches spaced");
check(same(searchReactions("star-struck").slice(0, 1), ["🤩"]), "…and hyphenated");
check(same(searchReactions("wow"), ["😮", "🤯", "🤩"]), "wow");
check(same(searchReactions("ok").slice(0, 2), ["👍", "👌"]), "ok");
check(searchReactions("no")[0] === "👎", "no");
check(same(searchReactions("🔥"), ["🔥"]), "pasting the emoji finds it");
check(searchReactions("xyzzy").length === 0, "nothing matches nonsense");
check(same(searchReactions("fire", ["👍", "🔥"]), ["🔥"]), "searches the list it is given");

console.log("reaction search selftest: ok");
