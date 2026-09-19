import { useId } from "react";

/**
 * "typing" with three small dots riding a wave: replaces presence in the thread
 * header and the preview in the chat list, like iOS.
 */
export function TypingLabel() {
  return (
    <span className="typing-label">
      typing
      <i className="typing-dots" aria-hidden="true">
        <i />
        <i />
        <i />
      </i>
    </span>
  );
}

/**
 * The peer's message taking shape: an incoming bubble with three ink dots that
 * swell and rise in turn. A goo filter lets a swelling dot pull a liquid bridge
 * from its neighbours — the same ink as the voice recorder's droplet. Matches
 * iOS `TypingIndicatorBubble`.
 */
export function TypingBubble({ name, leaving }: { name: string; leaving: boolean }) {
  /* useId() output isn't a valid url() fragment in every browser; keep it plain. */
  const goo = `ink-${useId().replace(/[^a-zA-Z0-9_-]/g, "")}`;
  return (
    <div
      className="bubble in first last typing-bubble"
      data-leaving={leaving ? "" : undefined}
      role="status"
      aria-label={`${name} is typing`}
    >
      <svg className="typing-ink" viewBox="0 0 40 20" aria-hidden="true">
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
    </div>
  );
}
