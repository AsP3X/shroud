import { useEffect, useRef, useState } from "react";
import { paceDelay, rollRuns } from "../rolling";

/**
 * A readout whose characters roll as they change, like iOS `rollingDigits`
 * (`.contentTransition(.numericText())` on a snappy spring): each character slot animates on its own,
 * so "0:41" → "0:42" moves only the last digit — upwards, or downwards with `countsDown`.
 * `animated={false}` shows a change at once (while scrubbing); under reduced motion the characters
 * cross-fade. Styled by its parent (size, colour, `tabular-nums`); it wraps only at spaces, and a
 * parent's `text-overflow: ellipsis` still applies. For a value that can change many times a second,
 * pass it through `usePaced`.
 */
export function RollingText({
  text,
  countsDown = false,
  animated = true,
}: {
  text: string;
  countsDown?: boolean;
  animated?: boolean;
}) {
  return (
    <span className={countsDown ? "rolling rolling-down" : "rolling"}>
      <span className="sr-only">{text}</span>
      <span aria-hidden="true">
        {rollRuns(text).map((run) =>
          "gap" in run ? (
            <span key={`gap${run.key}`}>{run.gap}</span>
          ) : (
            <span key={`word${run.key}`} className="roll-word">
              {run.slots.map((slot) => (
                <RollSlot key={slot.key} char={slot.char} animated={animated} />
              ))}
            </span>
          ),
        )}
      </span>
    </span>
  );
}

/** One character position: a new character rises in while the one it replaced leaves over it. */
function RollSlot({ char, animated }: { char: string; animated: boolean }) {
  // `rolls` is decided when the character changes: a slot that changed while `animated` was off must
  // not roll in later, when it turns back on.
  const [state, setState] = useState({ char, leaving: null as string | null, rolls: false, turn: 0 });
  if (state.char !== char) {
    // Adjusting state while rendering (React re-runs this before committing): one turn per change.
    setState({ char, leaving: animated ? state.char : null, rolls: animated, turn: state.turn + 1 });
  }
  const { turn } = state;
  return (
    <span className="roll-slot">
      {state.leaving != null ? (
        <span
          key={`out${turn}`}
          className="roll-out"
          onAnimationEnd={() => setState((now) => (now.turn === turn ? { ...now, leaving: null } : now))}
        >
          {state.leaving}
        </span>
      ) : null}
      <span key={`in${turn}`} className={state.rolls ? "roll-in" : undefined}>
        {state.char}
      </span>
    </span>
  );
}

/**
 * `value`, changing at most once per `READOUT_PACE_MS` and always ending on the latest, like iOS
 * `PacedRollingText`: a `RollingText` fed every tick of a download would roll back to back. Pass
 * `pacing = false` once the stream ends (a transfer finished) so the final value lands at once. A value
 * appearing (from null) lands at once too, as a new view does on iOS and Android.
 */
export function usePaced<T>(value: T, pacing = true): T {
  const [shown, setShown] = useState(value);
  const shownAt = useRef<number | null>(null);
  useEffect(() => {
    if (Object.is(value, shown)) return;
    const show = () => {
      shownAt.current = performance.now();
      setShown(value);
    };
    const wait = paceDelay(shownAt.current, performance.now(), pacing && shown != null);
    if (wait === 0) {
      show();
      return;
    }
    const timer = window.setTimeout(show, wait);
    return () => window.clearTimeout(timer);
  }, [value, pacing, shown]);
  return shown;
}
