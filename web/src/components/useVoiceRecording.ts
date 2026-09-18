import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type MouseEvent,
  type PointerEvent,
} from "react";
import { stopVoice } from "../voice/playback";
import {
  VOICE_LOCK_PX,
  cancelVoiceRecord,
  finishVoiceRecord,
  getVoiceRecorder,
  startVoiceRecord,
  type RecState,
  type VoiceTake,
} from "../voice/recorder";

/*
 * Voice recording in the web composer. Order of motion, kept strictly
 * sequential so every step reads as the cause of the next:
 *   press   a droplet buds on the mic (it breathes while the mic warms up)
 *   live    the droplet lifts — following the pointer while it is held — and
 *           spreads into the strip's capsule as the strip rises out of the
 *           composer; the readout fades in, then discard/send pop in
 *   close   the controls leave first, then the strip folds back down; a sent
 *           note's bubble enters only once the strip is gone
 * The droplet and strip themselves live in VoiceRecorderBar.tsx.
 */

export type VoicePhase = "idle" | "arming" | "holding" | "opening" | "live" | "closing";
type CloseKind = "send" | "discard";

/** Droplet diameter at rest on the mic. */
export const DROPLET = 38;
/** Safety net if an animation end event never arrives (e.g. the thread is hidden). */
const SETTLE_FALLBACK_MS = 1200;

export const prefersReducedMotion = () =>
  window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;

/** Past the lock distance the droplet only creeps, so it reads as a tether. */
export function visualLift(lift: number): number {
  return lift <= VOICE_LOCK_PX ? lift : VOICE_LOCK_PX + (lift - VOICE_LOCK_PX) * 0.25;
}

function micDenied(err: unknown): boolean {
  return err instanceof DOMException && (err.name === "NotAllowedError" || err.name === "NotFoundError");
}

export function useVoiceRecording({
  canSend,
  onSendVoice,
  onSettled,
  resetKey,
}: {
  canSend: boolean;
  onSendVoice: (take: VoiceTake) => void;
  /** Runs after the strip has folded away, e.g. to hand focus back to the field. */
  onSettled: () => void;
  resetKey: string;
}) {
  const shellRef = useRef<HTMLDivElement>(null);
  const micRef = useRef<HTMLButtonElement>(null);
  const capsuleRef = useRef<HTMLDivElement>(null);
  const sendRef = useRef<HTMLButtonElement>(null);

  const [phase, setPhaseState] = useState<VoicePhase>("idle");
  const phaseRef = useRef<VoicePhase>("idle");
  /** Droplet rest position: its bottom-left corner, measured from the shell's bottom-left. */
  const [bud, setBud] = useState<{ x: number; y: number } | null>(null);
  const [budLeaving, setBudLeaving] = useState(false);
  const [lift, setLift] = useState(0);
  const [closeKind, setCloseKind] = useState<CloseKind>("discard");
  /** What the strip keeps showing while it leaves; the recorder resets underneath it. */
  const [frozen, setFrozen] = useState<RecState | null>(null);
  const [hint, setHint] = useState<string | null>(null);

  const generation = useRef(0);
  const holding = useRef(false);
  const originY = useRef(0);
  const liftRef = useRef(0);
  const viaKeyboard = useRef(false);
  const releaseExit = useRef<(() => void) | null>(null);
  const onSendVoiceRef = useRef(onSendVoice);
  const onSettledRef = useRef(onSettled);
  onSendVoiceRef.current = onSendVoice;
  onSettledRef.current = onSettled;

  const setPhase = useCallback((next: VoicePhase) => {
    phaseRef.current = next;
    setPhaseState(next);
  }, []);

  useEffect(() => {
    return () => {
      generation.current += 1;
      holding.current = false;
      /* A note whose Send was already pressed still goes out. */
      releaseExit.current?.();
      releaseExit.current = null;
      cancelVoiceRecord();
      phaseRef.current = "idle";
      setPhaseState("idle");
      setBud(null);
      setBudLeaving(false);
      setFrozen(null);
      setLift(0);
      setHint(null);
    };
  }, [resetKey]);

  useEffect(() => {
    if (!hint) return;
    const timer = window.setTimeout(() => setHint(null), 4500);
    return () => window.clearTimeout(timer);
  }, [hint]);

  useEffect(() => {
    if (phase === "live" && viaKeyboard.current) sendRef.current?.focus({ preventScroll: true });
  }, [phase]);

  function budOnMic(): { x: number; y: number } | null {
    const shell = shellRef.current?.getBoundingClientRect();
    const mic = micRef.current?.getBoundingClientRect();
    if (!shell || !mic) return null;
    return {
      x: mic.left - shell.left + mic.width / 2 - DROPLET / 2,
      y: shell.bottom - mic.bottom + mic.height / 2 - DROPLET / 2,
    };
  }

  const open = useCallback(() => {
    holding.current = false;
    if (phaseRef.current === "opening" || phaseRef.current === "live" || phaseRef.current === "closing") return;
    if (prefersReducedMotion()) {
      setBud(null);
      setPhase("live");
      return;
    }
    setPhase("opening");
  }, [setPhase]);

  function abortStart(message: string | null) {
    holding.current = false;
    setPhase("idle");
    if (prefersReducedMotion()) {
      setBud(null);
      setBudLeaving(false);
    } else {
      setBudLeaving(true);
    }
    if (message) setHint(message);
  }

  async function begin(held: boolean) {
    if (phaseRef.current !== "idle" || !canSend) return;
    const gen = ++generation.current;
    holding.current = held;
    liftRef.current = 0;
    setLift(0);
    setHint(null);
    setBudLeaving(false);
    setBud(budOnMic());
    setPhase("arming");
    stopVoice();
    let started: boolean;
    try {
      started = await startVoiceRecord();
    } catch (err) {
      if (gen === generation.current) {
        abortStart(
          micDenied(err)
            ? "Microphone access is required for voice messages."
            : "Could not start recording.",
        );
      }
      return;
    }
    // Only a reset leaves "arming" early, and it bumps the generation.
    if (gen !== generation.current) return;
    if (!started) {
      abortStart(null);
      return;
    }
    if (holding.current && liftRef.current < VOICE_LOCK_PX) setPhase("holding");
    else open();
  }

  function onPointerDown(event: PointerEvent<HTMLButtonElement>) {
    if (event.button !== 0 || !canSend || phaseRef.current !== "idle") return;
    // Keeps focus (and a phone's keyboard) where it is.
    event.preventDefault();
    event.currentTarget.setPointerCapture(event.pointerId);
    originY.current = event.clientY;
    viaKeyboard.current = false;
    void begin(true);
  }

  function onPointerMove(event: PointerEvent<HTMLButtonElement>) {
    if (!holding.current) return;
    const dy = Math.max(0, originY.current - event.clientY);
    liftRef.current = dy;
    setLift(dy);
    if (dy >= VOICE_LOCK_PX && phaseRef.current === "holding") open();
  }

  function onPointerUp() {
    if (!holding.current) return;
    holding.current = false;
    if (phaseRef.current === "holding") open();
  }

  /** Enter/Space: pointer presses were already handled on pointerdown. */
  function onClick(event: MouseEvent<HTMLButtonElement>) {
    if (event.detail !== 0) return;
    viaKeyboard.current = true;
    void begin(false);
  }

  /* Capture can be lost (permission prompt, scroll); keep the gesture on the window. */
  useEffect(() => {
    if (phase !== "arming" && phase !== "holding") return;
    const onMove = (event: globalThis.PointerEvent) => {
      if (!holding.current) return;
      const dy = Math.max(0, originY.current - event.clientY);
      liftRef.current = dy;
      setLift(dy);
      if (dy >= VOICE_LOCK_PX && phaseRef.current === "holding") open();
    };
    const onUp = () => {
      if (!holding.current) return;
      holding.current = false;
      if (phaseRef.current === "holding") open();
    };
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
    window.addEventListener("pointercancel", onUp);
    return () => {
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
      window.removeEventListener("pointercancel", onUp);
    };
  }, [phase, open]);

  function close(kind: CloseKind) {
    if (phaseRef.current !== "live" && phaseRef.current !== "opening") return;
    const gen = generation.current;
    setBud(null);
    setFrozen(getVoiceRecorder());
    setCloseKind(kind);
    setPhase("closing");
    const exited = new Promise<void>((resolve) => {
      releaseExit.current = resolve;
    });
    const fallback = window.setTimeout(() => releaseExit.current?.(), SETTLE_FALLBACK_MS);
    void exited.then(() => {
      window.clearTimeout(fallback);
      releaseExit.current = null;
      if (gen !== generation.current) return;
      setFrozen(null);
      setPhase("idle");
      onSettledRef.current();
    });
    if (kind === "discard") {
      cancelVoiceRecord();
      return;
    }
    // Finishing runs alongside the exit; the bubble waits for both.
    void Promise.all([finishVoiceRecord(), exited]).then(
      ([take]) => {
        if (take) onSendVoiceRef.current(take);
        else if (gen === generation.current) setHint("That recording was too short.");
      },
      () => {
        cancelVoiceRecord();
        if (gen === generation.current) setHint("Could not finish the recording.");
      },
    );
  }

  useEffect(() => {
    if (phase !== "live" && phase !== "opening") return;
    const onKey = (event: globalThis.KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.preventDefault();
      close("discard");
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [phase]);

  return {
    phase,
    hint,
    bud,
    budLeaving,
    lift,
    closeKind,
    frozen,
    shellRef,
    micRef,
    capsuleRef,
    sendRef,
    micHandlers: {
      onPointerDown,
      onPointerMove,
      onPointerUp,
      onPointerCancel: onPointerUp,
      onLostPointerCapture: onPointerUp,
      onClick,
    },
    send: () => close("send"),
    discard: () => close("discard"),
    /** The droplet has become the capsule. */
    landed: () => {
      if (phaseRef.current !== "opening") return;
      setBud(null);
      setPhase("live");
    },
    budGone: () => {
      setBud(null);
      setBudLeaving(false);
    },
    stripGone: () => releaseExit.current?.(),
  };
}

export type VoiceRecording = ReturnType<typeof useVoiceRecording>;
