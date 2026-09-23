import { useState, type ReactNode } from "react";
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
}: {
  reactions: Reaction[] | undefined;
  myId: string;
  myName: string;
  peerName: string;
  onToggle: (emoji: string) => void;
  meta?: ReactNode;
}) {
  const me = myId.toLowerCase();
  const chips = reactionChips(reactions, me);
  if (chips.length === 0) return null;
  return (
    <div className="reaction-strip">
      {chips.map((chip) => {
        const names = chip.userIds.map((id) => (id === me ? "you" : peerName));
        return (
          <button
            key={chip.emoji}
            type="button"
            className={`reaction-chip${chip.includesMe ? " is-mine" : ""}`}
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
  const list = all ? ALL_REACTIONS : QUICK_REACTIONS;
  return (
    <div className={`reaction-picker${all ? " is-expanded" : ""}`} role="group" aria-label="React">
      {list.map((emoji) => (
        <button
          key={emoji}
          type="button"
          className={`reaction-pick${emoji === selected ? " is-selected" : ""}`}
          aria-pressed={emoji === selected}
          aria-label={emoji}
          onClick={() => onPick(emoji)}
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
          onClick={() => setAll(true)}
        >
          <ChevronDown size={14} aria-hidden="true" />
        </button>
      )}
    </div>
  );
}
