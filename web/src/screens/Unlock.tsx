import { useCallback, useEffect, useRef, useState } from "react";
import { Check, Delete, KeyRound, Lock, LockOpen, Shield, ShieldCheck } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { Avatar } from "../components/Avatar";
import {
  clearPin,
  clearSession,
  hasPin,
  loadSession,
  setLocked,
  setPin,
  touchLastActive,
  verifyPin,
} from "../session";

const PIN_LEN = 6;
const KEYS = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "del"] as const;

/* The unlock choreography from `Locked — Unlock Animation` in the Pencil files:
   Verified (badge opens, chips decrypt, dots go green) → Release (rings ripple out, the
   mark lifts, copy and card settle away) → the shell takes over. */
const VERIFIED_MS = 300;
const RELEASE_MS = 340;

type Choreography = "idle" | "verified" | "releasing";

export function Unlock() {
  const navigate = useNavigate();
  const session = loadSession();
  const creating = Boolean(session && !hasPin(session.user.id));
  const [phase, setPhase] = useState<"enter" | "confirm">("enter");
  const [pin, setPinValue] = useState("");
  const [firstPin, setFirstPin] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [shake, setShake] = useState(false);
  const [ready, setReady] = useState(false);
  const [choreography, setChoreography] = useState<Choreography>("idle");
  const [confirmReset, setConfirmReset] = useState(false);
  const inflight = useRef(false);
  const busyRef = useRef(false);
  busyRef.current = busy;

  const fail = useCallback((message: string) => {
    setError(message);
    setShake(true);
    setPinValue("");
    window.setTimeout(() => setShake(false), 420);
  }, []);

  useEffect(() => {
    const id = window.requestAnimationFrame(() => setReady(true));
    return () => window.cancelAnimationFrame(id);
  }, []);

  /* Plays Verified → Release, then hands over to the shell. Under Reduce Motion the CSS
     collapses both steps to instant, so the timers only delay a plain route change. */
  const enterApp = useCallback(async () => {
    setBusy(true);
    setChoreography("verified");
    await new Promise((resolve) => window.setTimeout(resolve, VERIFIED_MS));
    setChoreography("releasing");
    await new Promise((resolve) => window.setTimeout(resolve, RELEASE_MS));
    setLocked(false);
    touchLastActive(true);
    navigate("/app", { replace: true });
  }, [navigate]);

  useEffect(() => {
    const userId = session?.user.id;
    if (!userId || inflight.current) return;

    if (creating && pin.length === PIN_LEN && phase === "enter") {
      const timer = window.setTimeout(() => {
        setFirstPin(pin);
        setPinValue("");
        setPhase("confirm");
        setError(null);
      }, 180);
      return () => window.clearTimeout(timer);
    }

    if (creating && pin.length === PIN_LEN && phase === "confirm") {
      const timer = window.setTimeout(() => {
        void (async () => {
          if (inflight.current) return;
          inflight.current = true;
          try {
            if (pin !== firstPin) {
              setPhase("enter");
              setFirstPin("");
              fail("PINs did not match. Try again.");
              return;
            }
            setBusy(true);
            await setPin(userId, pin);
            await enterApp();
          } catch {
            fail("Could not store the PIN. Open Shroud over HTTPS (or localhost).");
            setBusy(false);
          } finally {
            inflight.current = false;
          }
        })();
      }, 180);
      return () => window.clearTimeout(timer);
    }

    if (creating) return;
    if (pin.length < 4) return;

    const wait = pin.length === PIN_LEN ? 40 : 320;
    const timer = window.setTimeout(() => {
      void (async () => {
        if (inflight.current) return;
        inflight.current = true;
        try {
          const ok = await verifyPin(userId, pin);
          if (ok) {
            await enterApp();
            return;
          }
          if (pin.length >= PIN_LEN) fail("Wrong PIN.");
        } finally {
          inflight.current = false;
        }
      })();
    }, wait);
    return () => window.clearTimeout(timer);
  }, [pin, creating, phase, firstPin, session?.user.id, fail, enterApp]);

  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (busyRef.current) return;
      if (event.metaKey || event.ctrlKey || event.altKey) return;
      if (event.key >= "0" && event.key <= "9") {
        event.preventDefault();
        setError(null);
        setPinValue((p) => (p + event.key).slice(0, PIN_LEN));
      } else if (event.key === "Backspace") {
        event.preventDefault();
        setPinValue((p) => p.slice(0, -1));
        setError(null);
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  if (!session) return null;

  function pushDigit(digit: string) {
    if (busy) return;
    setError(null);
    setPinValue((p) => (p + digit).slice(0, PIN_LEN));
  }

  function popDigit() {
    if (busy) return;
    setPinValue((p) => p.slice(0, -1));
    setError(null);
  }

  /* "Forgot PIN": drop the local PIN and session, keep the identity keys, and log in again.
     The phrase step re-derives the history key, and a fresh PIN is chosen on the way back. */
  function resetWithPhrase() {
    if (!session) return;
    clearPin(session.user.id);
    clearSession();
    navigate("/login", { replace: true });
  }

  const username = session.user.username;
  const verified = choreography !== "idle";
  const title =
    creating && phase === "confirm"
      ? "Confirm your PIN"
      : creating
        ? "Choose a PIN"
        : "Chats are locked";
  const lede =
    creating && phase === "confirm"
      ? "Enter the same 6 digits again."
      : creating
        ? "This PIN unlocks Shroud in this browser. Idle and hidden tabs lock after 5 minutes."
        : "Your messages stay encrypted in this browser until you unlock them. Idle and hidden tabs lock after 5 minutes.";
  const cardLabel = creating
    ? phase === "confirm"
      ? "Repeat the 6 digits"
      : "Pick 6 digits"
    : "Enter your 6-digit PIN";

  return (
    <div
      className={`lock-screen${ready ? " in" : ""}${verified ? " is-verified" : ""}${
        choreography === "releasing" ? " is-releasing" : ""
      }`}
    >
      <div className="lock-hero-col">
        <div className="lock-stage" aria-hidden="true">
          <span className="lock-ring lock-ring-outer" />
          <span className="lock-ring lock-ring-mid" />
          <span className="lock-ring lock-ring-inner" />
          <span className="lock-chip lock-chip-locked">
            {verified ? <LockOpen size={13} /> : <Lock size={13} />}
            <span>{verified ? "Hey! 👋" : "•••• ••••"}</span>
          </span>
          <span className="lock-chip lock-chip-sealed">
            {verified ? <Check size={14} /> : <ShieldCheck size={14} />}
            <span>{verified ? "Unlocked" : "Sealed"}</span>
          </span>
          <span className="lock-mark">
            <Shield size={48} strokeWidth={2} />
          </span>
          <span className="lock-badge">
            {verified ? <LockOpen size={18} strokeWidth={2.25} /> : <Lock size={18} strokeWidth={2.25} />}
          </span>
        </div>

        <span className="lock-account">
          <Avatar name={username} seed={session.user.id} size="sm" />
          <span>@{username}</span>
          <span className="sr-only">is signed in</span>
        </span>
        <h1>{title}</h1>
        <p className="lede">{lede}</p>
        <p className="lock-trust">
          <KeyRound size={12} aria-hidden="true" />
          Keys never leave this browser
        </p>
      </div>

      <div className="lock-card">
        <p className="lock-card-label">{cardLabel}</p>
        <div
          className={`lock-dots${shake ? " shake" : ""}`}
          role="img"
          aria-label={`${pin.length} of ${PIN_LEN} digits entered`}
        >
          {Array.from({ length: PIN_LEN }, (_, i) => (
            <span
              key={i}
              className={`lock-dot${i < pin.length || verified ? " filled" : ""}`}
              style={{ transitionDelay: `${i * 30}ms` }}
            />
          ))}
        </div>
        {error ? (
          <p className="lock-err" role="alert">
            {error}
          </p>
        ) : (
          <p className="lock-hint">Type on your keyboard or use the keypad</p>
        )}

        <div className="lock-pad">
          {KEYS.map((key, i) =>
            key === "" ? (
              <span key={`empty-${i}`} className="lock-key ghost" />
            ) : key === "del" ? (
              <button
                key="del"
                type="button"
                className="lock-key del"
                aria-label="Delete"
                disabled={busy || pin.length === 0}
                onClick={popDigit}
              >
                <Delete size={22} />
              </button>
            ) : (
              <button
                key={key}
                type="button"
                className="lock-key"
                disabled={busy}
                onClick={() => pushDigit(key)}
              >
                {key}
              </button>
            ),
          )}
        </div>

        {creating ? null : confirmReset ? (
          <p className="lock-reset">
            This logs you out of this browser. Your chats come back once you sign in with
            your encryption phrase.
            <span>
              <button type="button" className="auth-link" onClick={resetWithPhrase} disabled={busy}>
                Continue
              </button>
              <button
                type="button"
                className="auth-link muted"
                onClick={() => setConfirmReset(false)}
                disabled={busy}
              >
                Cancel
              </button>
            </span>
          </p>
        ) : (
          <button
            type="button"
            className="auth-link lock-phrase"
            onClick={() => setConfirmReset(true)}
            disabled={busy}
          >
            Use encryption phrase instead
          </button>
        )}
      </div>
    </div>
  );
}
