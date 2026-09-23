import { useRef, useState, type ReactNode } from "react";
import { ChevronDown } from "lucide-react";
import { Avatar } from "./Avatar";
import { ALL_REACTIONS, QUICK_REACTIONS, reactionChips, type Reaction } from "../reactions";

/**
 * Chips at the foot of a reacted bubble (Telegram 1:1: the emoji and the reactors' faces instead
 * of a count). `meta` — the bubble's time and ticks — sits at the end of the last chip row.
 */
export function ReactionStrip({
  reactions,
  myId,
  myName,
  peerName,
  onToggle,
  meta = null,
  settled,
}: {
  reactions: Reaction[] | undefined;
  myId: string;
  myName: string;
  peerName: string;
  onToggle: (emoji: string) => void;
  meta?: ReactNode;
  /** Emoji the message already had when its row appeared; only the others pop in. */
  settled?: ReadonlySet<string>;
}) {
  const me = myId.toLowerCase();
  const chips = reactionChips(reactions, me);
  if (chips.length === 0) return null;
  return (
    <div className="reaction-strip">
      {chips.map((chip) => {
        const names = chip.userIds.map((id) => (id === me ? "you" : peerName));
        const isNew = !settled?.has(chip.emoji);
        return (
          <button
            key={chip.emoji}
            type="button"
            className={`reaction-chip${chip.includesMe ? " is-mine" : ""}${isNew ? " is-new" : ""}`}
            aria-pressed={chip.includesMe}
            aria-label={`${chip.emoji}, ${names.join(" and ")}. ${
              chip.includesMe ? "Remove your reaction" : "React with the same emoji"
            }`}
            onClick={(event) => {
              event.stopPropagation();
              onToggle(chip.emoji);
            }}
          >
            <span className="reaction-emoji" aria-hidden="true">
              {chip.emoji}
            </span>
            <span className="reaction-faces" aria-hidden="true">
              {chip.userIds.slice(0, 3).map((id) => (
                <Avatar key={id} name={id === me ? myName : peerName} seed={id} size="xs" />
              ))}
            </span>
          </button>
        );
      })}
      {meta ? <span className="reaction-strip-meta">{meta}</span> : null}
    </div>
  );
}

/**
 * The reaction row over a message's menu; "more" grows it in place into Telegram's standard
 * set. Our current reaction is ringed, and picking it again takes it back.
 */
export function ReactionPicker({
  selected,
  onPick,
}: {
  selected: string | null;
  onPick: (emoji: string) => void;
}) {
  const [all, setAll] = useState(false);
  /* A pointer click counts only if it started here: the finger that held the message down to
     open this menu must never send a reaction when it lifts. Keyboard clicks (detail 0) always
     count. */
  const pressedHere = useRef(false);
  const list = all ? ALL_REACTIONS : QUICK_REACTIONS;
  return (
    <div
      className={`reaction-picker${all ? " is-expanded" : ""}`}
      role="group"
      aria-label="React"
      onPointerDown={() => {
        pressedHere.current = true;
      }}
    >
      {list.map((emoji) => (
        <button
          key={emoji}
          type="button"
          role="menuitemradio"
          className={`reaction-pick${emoji === selected ? " is-selected" : ""}`}
          aria-checked={emoji === selected}
          aria-label={emoji}
          onClick={(event) => {
            if (event.detail !== 0 && !pressedHere.current) return;
            onPick(emoji);
          }}
        >
          {emoji}
        </button>
      ))}
      {all ? null : (
        <button
          type="button"
          className="reaction-more"
          aria-label="More reactions"
          aria-expanded={false}
          onClick={(event) => {
            setAll(true);
            // Keep focus in the row: the button it was on is about to go.
            const row = event.currentTarget.parentElement;
            requestAnimationFrame(() => row?.querySelector<HTMLButtonElement>("button")?.focus());
          }}
        >
          <ChevronDown size={14} aria-hidden="true" />
        </button>
      )}
    </div>
  );
}
