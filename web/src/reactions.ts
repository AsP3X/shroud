/**
 * Message reactions: the sealed wire payload (shared with iOS `MessageReactionPayload`), and the
 * last-write-wins rules that fold server changes into a thread.
 *
 * A reaction is not a message. The server keeps one sealed record per (message, user) and learns
 * who reacted to which message, never the emoji (`PUT /messages/{id}/reaction`). `r` ties the
 * record to its message so the server cannot move it onto another; a removal keeps its entry with
 * `emoji: null`, so a late, older change cannot bring the reaction back. See docs/architecture.md.
 */
import { api, type WireReaction } from "./api/client";
import { utf8, utf8decode } from "./crypto/bytes";
import { envelopeToWireB64, openMessage, sealIdentityEnvelope, wireB64ToEnvelope } from "./crypto/messageCrypto";
import type { IdentityMaterial } from "./crypto/identity";
import type { ChatMessage } from "./messaging";

export type Reaction = {
  userId: string;
  /** Null when the user took their reaction back. */
  emoji: string | null;
  /** The server's change cursor; the highest per user wins. */
  seq: number;
  /** Our own change the server has not confirmed yet; it keeps the previous `seq`. */
  pending?: boolean;
};

export type ReactionChip = { emoji: string; userIds: string[]; includesMe: boolean };

/** The bar under a message's menu: Telegram's quick seven. */
export const QUICK_REACTIONS = ["❤️", "🔥", "👍", "😢", "🙏", "😮", "👎"];
/** Telegram's standard set, behind the bar's "more" (quick seven first). */
export const ALL_REACTIONS = [
  ...QUICK_REACTIONS,
  "🥰", "👏", "😁", "🤔", "🤯", "😱", "🤬", "🎉", "🤩", "🤮", "💩", "👌", "🕊️", "🤡",
  "🥱", "🥴", "😍", "🐳", "❤️‍🔥", "🌚", "🌭", "💯", "🤣", "⚡", "🍌", "🏆", "💔", "🤨",
  "😐", "🍓", "🍾", "💋", "🖕", "😈", "😴", "😭", "🤓", "👻", "👨‍💻", "👀", "🎃", "🙈",
  "😇", "😨", "🤝", "✍️", "🤗", "🫡", "🎅", "🎄", "☃️", "💅", "🤪", "🗿", "🆒", "💘",
  "🙉", "🦄", "😘", "💊", "🙊", "😎", "👾", "🤷", "😡",
];

const MAX_EMOJI_BYTES = 32;
const segmenter = new Intl.Segmenter(undefined, { granularity: "grapheme" });

/** One grapheme drawn as an emoji: rejects text, digits and several emoji at once. */
export function isSingleEmoji(text: string): boolean {
  if (!text || utf8(text).length > MAX_EMOJI_BYTES) return false;
  if ([...segmenter.segment(text)].length !== 1) return false;
  if (/^\p{Emoji_Presentation}/u.test(text)) return true;
  // "❤" + VS16, keycaps, flags: an emoji-capable base made an emoji by what follows.
  return /^\p{Emoji}/u.test(text) && [...text].length > 1;
}

export function reactionPayload(emoji: string, messageId: string): string {
  return JSON.stringify({ t: "reaction", r: messageId.toLowerCase(), e: [emoji] });
}

/** The emoji in `raw` when it is a reaction to `messageId`, else null. */
export function parseReaction(raw: string, messageId: string): string | null {
  try {
    const value = JSON.parse(raw) as { t?: unknown; r?: unknown; e?: unknown } | null;
    if (!value || value.t !== "reaction" || typeof value.r !== "string") return null;
    if (value.r.toLowerCase() !== messageId.toLowerCase()) return null;
    const first = Array.isArray(value.e) ? value.e[0] : null;
    return typeof first === "string" && isSingleEmoji(first) ? first : null;
  } catch {
    return null;
  }
}

function sortKey(reaction: Reaction): number {
  return reaction.pending ? Number.MAX_SAFE_INTEGER : reaction.seq;
}

function sorted(reactions: Reaction[]): Reaction[] {
  return [...reactions].sort((a, b) => sortKey(a) - sortKey(b) || a.userId.localeCompare(b.userId));
}

/** One change (WebSocket, catch-up). Null when it changes nothing. */
export function applyReaction(reactions: Reaction[], change: Reaction): Reaction[] | null {
  const index = reactions.findIndex((r) => r.userId === change.userId);
  if (index < 0) return sorted([...reactions, change]);
  const current = reactions[index];
  // Our unconfirmed tap is newer than anything the server has sent so far.
  if (current.pending && !change.pending) return null;
  if (!change.pending && change.seq <= current.seq) return null;
  const next = [...reactions];
  next[index] = change;
  return sorted(next);
}

/** `userId`'s entry swapped (or dropped), whatever its seq: our own tap, its ack or its undo. */
export function replacingReaction(reactions: Reaction[], userId: string, entry: Reaction | null): Reaction[] {
  const rest = reactions.filter((r) => r.userId !== userId);
  return sorted(entry ? [...rest, entry] : rest);
}

export function emojiOf(userId: string, reactions: Reaction[] | undefined): string | null {
  return reactions?.find((r) => r.userId === userId)?.emoji ?? null;
}

/** Chips in the order each emoji first appeared. */
export function reactionChips(reactions: Reaction[] | undefined, me: string): ReactionChip[] {
  const chips = new Map<string, string[]>();
  for (const reaction of reactions ?? []) {
    if (!reaction.emoji) continue;
    chips.set(reaction.emoji, [...(chips.get(reaction.emoji) ?? []), reaction.userId]);
  }
  return [...chips].map(([emoji, userIds]) => ({ emoji, userIds, includesMe: userIds.includes(me) }));
}

/** Folds opened changes into a thread. Pure: it runs inside `setThread` updaters. */
export function applyReactionChanges(
  thread: ChatMessage[],
  changes: { messageId: string; reaction: Reaction }[],
): ChatMessage[] {
  if (changes.length === 0) return thread;
  const byMessage = new Map<string, Reaction[]>();
  for (const change of changes) {
    const key = change.messageId.toLowerCase();
    byMessage.set(key, [...(byMessage.get(key) ?? []), change.reaction]);
  }
  let changed = false;
  const next = thread.map((message) => {
    const incoming = byMessage.get(message.id.toLowerCase());
    if (!incoming || message.deleted) return message;
    let reactions = message.reactions ?? [];
    for (const reaction of incoming) reactions = applyReaction(reactions, reaction) ?? reactions;
    if (reactions === message.reactions) return message;
    changed = true;
    return { ...message, reactions };
  });
  return changed ? next : thread;
}

/** Our own entry on one message, replaced. Pure. */
export function withMyReaction(
  thread: ChatMessage[],
  messageId: string,
  me: string,
  entry: Reaction | null,
): ChatMessage[] {
  const key = messageId.toLowerCase();
  return thread.map((message) =>
    message.id.toLowerCase() === key && !message.deleted
      ? { ...message, reactions: replacingReaction(message.reactions ?? [], me, entry) }
      : message,
  );
}

/**
 * Opens one sealed record. A record already held at the same seq is reused; one that does not
 * open counts as no reaction (its seq still wins over older ones).
 */
export async function openReaction(
  wire: WireReaction,
  me: string,
  material: IdentityMaterial,
  peerIdentity: (userId: string) => Promise<Uint8Array>,
  held: Reaction[] = [],
): Promise<Reaction> {
  const userId = wire.user_id.toLowerCase();
  const removed: Reaction = { userId, emoji: null, seq: wire.seq };
  if (!wire.ciphertext) return removed;
  const known = held.find((r) => r.userId === userId && r.seq === wire.seq && !r.pending);
  if (known) return known;
  try {
    const mine = userId === me.toLowerCase();
    const plain = await openMessage({
      envelopeData: wireB64ToEnvelope(wire.ciphertext),
      peerUserId: userId,
      ourUserId: me,
      ourPrivate: material.agreementPrivate,
      ourIdentityPublic: material.agreementPublic,
      senderIdentityPublic: mine ? material.agreementPublic : await peerIdentity(userId),
      asSender: mine,
      sentAt: Date.parse(wire.updated_at),
    });
    return { userId, emoji: parseReaction(utf8decode(plain), wire.message_id), seq: wire.seq };
  } catch {
    return removed;
  }
}

/**
 * Saves our reaction (`null` removes it). Sealed as a v2 envelope, never through the ratchet:
 * the record is overwritten in place, and every device must open it at any time.
 * Resolves to the confirmed entry, or null when there was nothing to remove.
 */
export async function saveReaction(opts: {
  token: string;
  me: string;
  messageId: string;
  emoji: string | null;
  material: IdentityMaterial;
  peerIdentityPublic: Uint8Array;
}): Promise<Reaction | null> {
  const me = opts.me.toLowerCase();
  if (!opts.emoji) {
    const res = await api.deleteReaction(opts.token, opts.messageId);
    return res ? { userId: me, emoji: null, seq: res.seq } : null;
  }
  const envelope = await sealIdentityEnvelope(
    utf8(reactionPayload(opts.emoji, opts.messageId)),
    opts.material.agreementPrivate,
    opts.peerIdentityPublic,
    opts.material.agreementPublic,
  );
  const res = await api.putReaction(opts.token, opts.messageId, envelopeToWireB64(envelope));
  return { userId: me, emoji: opts.emoji, seq: res.seq };
}
