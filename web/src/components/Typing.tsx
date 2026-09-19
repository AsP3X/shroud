import { useId } from "react";
import type { PeerActivity } from "../typing";

/** What a screen reader says: the meter that makes "recording" mean a voice note is silent. */
function spoken(word: PeerActivity): string {
  return word === "recording" ? "recording a voice message" : "typing";
}

/* Rows and the header style every nested span (block, secondary colour), so
   everything inside the label is text or <i>. */

/** Level-meter bars, animated by `rec-bar-*` in index.css (in step with iOS `RecordingWave`). */
function MeterBars({ count, className }: { count: number; className: string }) {
  return (
    <i className={className} aria-hidden="true">
      {Array.from({ length: count }, (_, index) => (
        <i key={index} />
      ))}
    </i>
  );
}

/**
 * "typing" with three small dots riding a wave, or "recording" with a small
 * level meter: replaces presence in the thread header and the preview in the
 * chat list, like iOS. Keyed on the word, so a switch fades the new one in.
 */
export function TypingLabel({ word = "typing" }: { word?: PeerActivity }) {
  return (
    <span className="typing-label" key={word}>
      {word}
      {word === "recording" ? (
        <>
          <i className="sr-only"> a voice message</i>
          <MeterBars count={3} className="rec-bars" />
        </>
      ) : (
        <i className="typing-dots" aria-hidden="true">
          <i />
          <i />
          <i />
        </i>
      )}
    </span>
  );
}

/**
 * The peer's message taking shape: an incoming bubble with three ink dots that
 * swell and rise in turn. A goo filter lets a swelling dot pull a liquid bridge
 * from its neighbours — the same ink as the voice recorder's droplet. While the
 * peer records a voice note, the bubble shows the recorder's own look instead:
 * its blinking red dot beside a live level meter. A switch between the two only
 * swaps what is inside the bubble. Matches iOS `TypingIndicatorBubble`.
 */
export function TypingBubble({
  name,
  leaving,
  word = "typing",
}: {
  name: string;
  leaving: boolean;
  word?: PeerActivity;
}) {
  /* useId() output isn't a valid url() fragment in every browser; keep it plain. */
  const goo = `ink-${useId().replace(/[^a-zA-Z0-9_-]/g, "")}`;
  return (
    <div
      className="bubble in first last typing-bubble"
      data-leaving={leaving ? "" : undefined}
      role="status"
      aria-label={`${name} is ${spoken(word)}`}
    >
      {word === "recording" ? (
        <span className="rec-ink" key="recording" aria-hidden="true">
          <i className="rec-dot" />
          <MeterBars count={5} className="rec-bars rec-bars-lg" />
        </span>
      ) : (
        <svg className="typing-ink" key="typing" viewBox="0 0 40 20" aria-hidden="true">
          <defs>
            <filter id={goo} x="-20%" y="-50%" width="140%" height="200%" colorInterpolationFilters="sRGB">
              <feGaussianBlur in="SourceGraphic" stdDeviation="2" />
              <feColorMatrix values="1 0 0 0 0  0 1 0 0 0  0 0 1 0 0  0 0 0 20 -8" />
            </filter>
          </defs>
          <g filter={`url(#${goo})`}>
            <circle cx="9" cy="11" r="3.6" />
            <circle cx="20" cy="11" r="3.6" />
            <circle cx="31" cy="11" r="3.6" />
          </g>
        </svg>
      )}
    </div>
  );
}
