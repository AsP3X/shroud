import { useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { ChevronDown, ChevronUp, Search, X } from "lucide-react";
import { Avatar } from "./Avatar";
import { animateHeight, useMenuFit } from "./ContextMenu";
import { emojisOf, QUICK_REACTIONS, reactionChips, type Reaction } from "../reactions";
import { searchReactions } from "../reactionSearch";

/**
 * Chips at the foot of a reacted bubble: each person's emoji in one chip with their face (Telegram
 * 1:1 shows faces, not counts). Each emoji is its own button — ours are taken back, theirs added to
 * ours (one we have already stays as it is). `meta` — the bubble's time and ticks — sits at the
 * end of the last chip row.
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
  /** "user:emoji" pairs the message already had when its row appeared; only others pop in. */
  settled?: ReadonlySet<string>;
}) {
  const me = myId.toLowerCase();
  const chips = reactionChips(reactions, me);
  if (chips.length === 0) return null;
  const mine = emojisOf(me, reactions);
  return (
    <div className="reaction-strip">
      {chips.map((chip) => {
        const names = chip.userIds.map((id) => (id === me ? "you" : peerName)).join(" and ");
        return (
          <span
            key={chip.userIds.join("+")}
            className={`reaction-chip${chip.includesMe ? " is-mine" : ""}`}
            role="group"
            aria-label={`Reactions from ${names}`}
          >
            {chip.emojis.map((emoji) => {
              const ours = mine.includes(emoji);
              // On the other person's chip an emoji we have already does nothing.
              const inert = !chip.includesMe && ours;
              const action = chip.includesMe
                ? "Remove your reaction"
                : ours
                  ? "You reacted with this too"
                  : "Add the same reaction";
              return (
                <button
                  key={emoji}
                  type="button"
                  className={`reaction-emoji${settled?.has(`${chip.userIds[0]}:${emoji}`) ? "" : " is-new"}`}
                  aria-pressed={ours}
                  aria-disabled={inert || undefined}
                  aria-label={`${emoji}, ${names}. ${action}`}
                  onClick={(event) => {
                    event.stopPropagation();
                    if (!inert) onToggle(emoji);
                  }}
                >
                  {emoji}
                </button>
              );
            })}
            <span className="reaction-faces" aria-hidden="true">
              {chip.userIds.slice(0, 3).map((id) => (
                <Avatar key={id} name={id === me ? myName : peerName} seed={id} size="xs" />
              ))}
            </span>
          </span>
        );
      })}
      {meta ? <span className="reaction-strip-meta">{meta}</span> : null}
    </div>
  );
}

/**
 * The reaction row over a message's menu; "more" grows it in place into Telegram's standard
 * set, with a search box over it and a way back to the row. Our reactions are ringed, and
 * picking one again takes it back.
 *
 * Every change of height is the menu's animation (`useMenuFit`): the picker tells the menu
 * before and after it changes, and the menu glides from one box to the other, re-fitted on
 * screen. The grid is as tall as the rows it shows (up to five and a half), so a search that
 * leaves one row shrinks the menu around it, smoothly, and the actions under it slide up. A
 * letter typed over the grid goes into the search box; Escape clears it, and closes the menu
 * when it is empty.
 */
export function ReactionPicker({
  selected,
  onPick,
}: {
  selected: readonly string[];
  onPick: (emoji: string) => void;
}) {
  const fit = useMenuFit();
  const [all, setAll] = useState(false);
  const [query, setQueryState] = useState("");
  const root = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const more = useRef<HTMLButtonElement>(null);
  const grid = useRef<HTMLDivElement>(null);
  /* A pointer click counts only if it started here: the finger that held the message down to
     open this menu must never send a reaction when it lifts. Keyboard clicks (detail 0) always
     count. */
  const pressedHere = useRef(false);
  /* Every change that alters the picker's height — growing, shrinking, a search leaving fewer
     rows — announces itself to the menu first and animates the grid's own height after. */
  const pendingFit = useRef(false);
  const gridBefore = useRef<number | null>(null);
  const toggled = useRef(false);
  function resizing(update: () => void) {
    fit?.willResize();
    gridBefore.current = grid.current?.offsetHeight ?? null;
    pendingFit.current = true;
    update();
  }
  const setQuery = (next: string | ((current: string) => string)) => resizing(() => setQueryState(next));
  function expand(next: boolean) {
    toggled.current = true;
    resizing(() => {
      setAll(next);
      if (!next) setQueryState("");
    });
  }
  useLayoutEffect(() => {
    if (!pendingFit.current) return;
    pendingFit.current = false;
    fit?.didResize();
    const from = gridBefore.current;
    gridBefore.current = null;
    if (grid.current && from !== null) animateHeight(grid.current, from);
    if (!toggled.current) return;
    toggled.current = false;
    if (all) {
      // A mouse or trackpad user can type at once; a finger would only get the keyboard.
      const fine = typeof window.matchMedia === "function" && window.matchMedia("(pointer: fine)").matches;
      const target = fine ? input.current : root.current?.querySelector<HTMLButtonElement>(".reaction-pick");
      target?.focus({ preventScroll: true });
    } else {
      more.current?.focus({ preventScroll: true });
    }
  }, [all, query, fit]);

  const list = all ? searchReactions(query) : QUICK_REACTIONS;
  const trimmed = query.trim();
  const pick = (emoji: string) => (event: { detail: number }) => {
    if (event.detail !== 0 && !pressedHere.current) return;
    onPick(emoji);
  };
  return (
    <div
      ref={root}
      className={`reaction-picker${all ? " is-expanded" : ""}`}
      role="group"
      aria-label="React"
      onPointerDown={() => {
        pressedHere.current = true;
      }}
      onKeyDown={(event) => {
        if (!all) return;
        if (event.key === "Escape") {
          // The menu leaves this Escape to us while the box has text (see ContextMenu).
          if (event.target === input.current && query) {
            event.preventDefault();
            setQuery("");
          }
          return;
        }
        // Type to search: a letter typed over the grid goes into the box.
        if (
          event.target !== input.current &&
          /^\S$/.test(event.key) &&
          !event.ctrlKey &&
          !event.metaKey &&
          !event.altKey
        ) {
          event.preventDefault();
          setQuery((current) => current + event.key);
          input.current?.focus({ preventScroll: true });
        }
      }}
    >
      {all ? (
        <>
          <div className="reaction-search">
            <Search size={14} aria-hidden="true" />
            <input
              ref={input}
              type="search"
              value={query}
              placeholder="Search reactions"
              aria-label="Search reactions"
              autoComplete="off"
              autoCorrect="off"
              autoCapitalize="off"
              spellCheck={false}
              enterKeyHint="search"
              onChange={(event) => setQuery(event.target.value)}
            />
            {query ? (
              <button
                type="button"
                className="reaction-search-clear"
                aria-label="Clear search"
                onClick={() => {
                  setQuery("");
                  input.current?.focus({ preventScroll: true });
                }}
              >
                <X size={12} aria-hidden="true" />
              </button>
            ) : null}
          </div>
          <button
            type="button"
            role="menuitem"
            className="reaction-more is-open"
            aria-label="Fewer reactions"
            aria-expanded={true}
            onClick={(event) => {
              if (event.detail !== 0 && !pressedHere.current) return;
              expand(false);
            }}
          >
            <ChevronUp size={14} aria-hidden="true" />
          </button>
        </>
      ) : null}
      <div ref={grid} className={`reaction-picker-list${all ? " is-grid" : ""}`} role="none">
        {list.map((emoji) => (
          <button
            key={emoji}
            type="button"
            role="menuitemcheckbox"
            className={`reaction-pick${selected.includes(emoji) ? " is-selected" : ""}`}
            aria-checked={selected.includes(emoji)}
            aria-label={emoji}
            onClick={pick(emoji)}
          >
            {emoji}
          </button>
        ))}
        {all ? null : (
          <button
            ref={more}
            type="button"
            role="menuitem"
            className="reaction-more"
            aria-label="More reactions"
            aria-expanded={false}
            onClick={(event) => {
              if (event.detail !== 0 && !pressedHere.current) return;
              expand(true);
            }}
          >
            <ChevronDown size={14} aria-hidden="true" />
          </button>
        )}
        {all && list.length === 0 ? (
          <p className="reaction-empty" role="status">
            No reactions match “{trimmed}”
          </p>
        ) : null}
      </div>
    </div>
  );
}
