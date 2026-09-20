import { useCallback, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";

/**
 * Swipe a message row left to reply — the same gesture, thresholds and rubber banding as the
 * iOS app (which took them from Telegram). Touch and pen only: with a mouse the row shows a
 * hover button instead.
 *
 * The row is moved by writing `--swipe` straight onto the element rather than through React
 * state, so dragging never re-renders a bubble (a photo row would judder if it did). Only
 * starting and ending the gesture touch state, to mount and unmount the icon.
 */

/** Incoming rows arm at 45px, outgoing at 60px (they start further from the edge). */
const THRESHOLD_IN = 45;
const THRESHOLD_OUT = 60;
const BAND_RANGE = 100;
const BAND_COEFFICIENT = 0.4;
const MAX_TRAVEL = 180;
/** Movement before the gesture decides between "scroll" and "reply". */
const SLOP = 3;

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
}: {
  enabled: boolean;
  isMine: boolean;
  onReply: () => void;
}) {
  const rowRef = useRef<HTMLDivElement | null>(null);
  const [swiping, setSwiping] = useState(false);
  const state = useRef({ id: -1, x: 0, y: 0, decided: "" as "" | "yes" | "no", armed: false });
  const threshold = isMine ? THRESHOLD_OUT : THRESHOLD_IN;

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
      setSwiping(false);
    },
    [],
  );

  const onPointerDown = useCallback(
    (event: ReactPointerEvent<HTMLDivElement>) => {
      if (!enabled) return;
      if (event.pointerType === "mouse") return;
      state.current = {
        id: event.pointerId,
        x: event.clientX,
        y: event.clientY,
        decided: "",
        armed: false,
      };
    },
    [enabled],
  );

  const onPointerMove = useCallback(
    (event: ReactPointerEvent<HTMLDivElement>) => {
      const current = state.current;
      if (current.id !== event.pointerId) return;
      const dx = event.clientX - current.x;
      const dy = event.clientY - current.y;

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
    [paint, threshold],
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
    handlers: { onPointerDown, onPointerMove, onPointerUp, onPointerCancel },
  };
}
