import { useCallback, useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";

/**
 * Swipe a message row left to reply — the same gesture, thresholds and rubber banding as the
 * iOS app (which took them from Telegram). Touch and pen only: with a mouse the row shows a
 * hover button instead.
 *
 * The row is moved by writing `--swipe` straight onto the element rather than through React
 * state, so dragging never re-renders a bubble (a photo row would judder if it did). Only
 * starting and ending the gesture touch state, to mount and unmount the icon.
 *
 * A touch that holds still instead opens the message menu (`onLongPress`). It lives here
 * because both gestures start the same way and rule each other out: moving first makes it a
 * swipe (or a scroll), holding first makes it a press. iOS Safari never fires `contextmenu`
 * for a long press, so without this a phone would have no way into the menu at all.
 */

/** Incoming rows arm at 45px, outgoing at 60px (they start further from the edge). */
const THRESHOLD_IN = 45;
const THRESHOLD_OUT = 60;
const BAND_RANGE = 100;
const BAND_COEFFICIENT = 0.4;
const MAX_TRAVEL = 180;
/** Movement before the gesture decides between "scroll" and "reply". */
const SLOP = 3;
/** Hold this long without moving to open the menu (a touch longer than a tap, as on iOS). */
const LONG_PRESS_MS = 450;

function banded(distance: number, threshold: number): number {
  if (distance <= threshold) return Math.max(0, distance);
  const beyond = distance - threshold;
  const eased = (1 - 1 / ((beyond * BAND_COEFFICIENT) / BAND_RANGE + 1)) * BAND_RANGE;
  return Math.min(MAX_TRAVEL, threshold + eased);
}

export function useSwipeToReply({
  enabled,
  isMine,
  onReply,
  onLongPress,
}: {
  /** False for bubbles that cannot be quoted yet; the long press still works for them. */
  enabled: boolean;
  isMine: boolean;
  onReply: () => void;
  /** A touch held still: open the message menu at that point. */
  onLongPress?: (x: number, y: number) => void;
}) {
  const rowRef = useRef<HTMLDivElement | null>(null);
  const [swiping, setSwiping] = useState(false);
  const state = useRef({ id: -1, x: 0, y: 0, decided: "" as "" | "yes" | "no", armed: false });
  /** True from touch-down until the finger lifts. A contextmenu event can land in between. */
  const holding = useRef(false);
  const pressTimer = useRef(0);
  const threshold = isMine ? THRESHOLD_OUT : THRESHOLD_IN;

  const cancelPress = useCallback(() => {
    window.clearTimeout(pressTimer.current);
    pressTimer.current = 0;
  }, []);
  // A row scrolled away mid-press must not open a menu for a message that is gone.
  useEffect(() => cancelPress, [cancelPress]);

  const paint = useCallback(
    (distance: number, armed: boolean) => {
      const row = rowRef.current;
      if (!row) return;
      row.style.setProperty("--swipe", `${-distance}px`);
      row.style.setProperty("--swipe-progress", `${Math.min(1, distance / threshold)}`);
      row.dataset.swipeArmed = armed ? "true" : "false";
    },
    [threshold],
  );

  const reset = useCallback(
    (animate: boolean) => {
      const row = rowRef.current;
      if (row) {
        row.dataset.swipeSettling = animate ? "true" : "false";
        row.style.setProperty("--swipe", "0px");
        row.style.setProperty("--swipe-progress", "0");
        row.dataset.swipeArmed = "false";
        if (animate) {
          window.setTimeout(() => {
            if (rowRef.current === row) row.dataset.swipeSettling = "false";
          }, 240);
        }
      }
      state.current = { id: -1, x: 0, y: 0, decided: "", armed: false };
      holding.current = false;
      cancelPress();
      setSwiping(false);
    },
    [cancelPress],
  );

  const onPointerDown = useCallback(
    (event: ReactPointerEvent<HTMLDivElement>) => {
      if (event.pointerType === "mouse") return;
      if (!enabled && !onLongPress) return;
      holding.current = true;
      const { clientX: x, clientY: y, pointerId } = event;
      state.current = { id: pointerId, x, y, decided: enabled ? "" : "no", armed: false };
      cancelPress();
      if (!onLongPress) return;
      pressTimer.current = window.setTimeout(() => {
        const current = state.current;
        // Still the same finger, and it never started a swipe or a scroll.
        if (current.id !== pointerId || current.decided === "yes") return;
        current.decided = "no";
        pressTimer.current = 0;
        navigator.vibrate?.(10);
        onLongPress(x, y);
      }, LONG_PRESS_MS);
    },
    [cancelPress, enabled, onLongPress],
  );

  const onPointerMove = useCallback(
    (event: ReactPointerEvent<HTMLDivElement>) => {
      const current = state.current;
      if (current.id !== event.pointerId) return;
      const dx = event.clientX - current.x;
      const dy = event.clientY - current.y;

      // Any real movement means this is not a long press.
      if (Math.abs(dx) > SLOP || Math.abs(dy) > SLOP) cancelPress();

      if (current.decided === "") {
        // Anything that looks vertical — or rightward — belongs to the page, not to us.
        if (dx > SLOP || (Math.abs(dy) > SLOP && Math.abs(dy) > Math.abs(dx) * 2)) {
          current.decided = "no";
          return;
        }
        if (Math.abs(dx) > SLOP && Math.abs(dy) * 2 < Math.abs(dx)) {
          current.decided = "yes";
          try {
            // Keeps the row tracking the finger even if it wanders off the bubble.
            rowRef.current?.setPointerCapture(event.pointerId);
          } catch {
            /* the pointer went away between the two events */
          }
          setSwiping(true);
        } else {
          return;
        }
      }
      if (current.decided !== "yes") return;

      const distance = banded(-dx, threshold);
      const armed = -dx >= threshold;
      if (armed !== current.armed) {
        current.armed = armed;
        // Android's haptic; iOS Safari ignores it, which is why the icon also pops.
        if (armed) navigator.vibrate?.(8);
      }
      paint(distance, armed);
    },
    [cancelPress, paint, threshold],
  );

  const onPointerUp = useCallback(
    (event: ReactPointerEvent<HTMLDivElement>) => {
      const current = state.current;
      if (current.id !== event.pointerId) return;
      const dx = event.clientX - current.x;
      const fired = current.decided === "yes" && -dx >= threshold;
      reset(current.decided === "yes");
      if (fired) onReply();
    },
    [onReply, reset, threshold],
  );

  const onPointerCancel = useCallback(
    (event: ReactPointerEvent<HTMLDivElement>) => {
      if (state.current.id !== event.pointerId) return;
      reset(state.current.decided === "yes");
    },
    [reset],
  );

  return {
    rowRef,
    swiping,
    /** A context-menu event means this hold is no longer a long-press. */
    cancelLongPress: cancelPress,
    /** The finger that started this gesture has not lifted yet. */
    isHolding: () => holding.current,
    handlers: { onPointerDown, onPointerMove, onPointerUp, onPointerCancel },
  };
}
