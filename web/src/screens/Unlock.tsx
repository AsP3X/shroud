import { useCallback, useEffect, useRef, useState } from "react";
import { Check, Delete, KeyRound, Lock, LockOpen, ShieldCheck } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { api } from "../api/client";
import { Avatar } from "../components/Avatar";
import { BrandMark } from "../components/BrandMark";
import { abandonPin, clearPin, hasPin, pinLength, setPin, unlockWithPin } from "../crypto/vaultAccess";
import { clearSession, loadSession, setLocked, touchLastActive } from "../session";

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
  /** Phrase reset is underway. A PIN try still in flight must not open the app or sign out again. */
  const leaving = useRef(false);
  /** Bumped when phrase reset starts, so that in-flight PIN try ignores its own result. */
  const pinAttempt = useRef(0);
  /* False once the screen is gone, so a choreography that outlives it does not navigate. */
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

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
    if (leaving.current || !mounted.current) return;
    setBusy(true);
    setChoreography("verified");
    await new Promise((resolve) => window.setTimeout(resolve, VERIFIED_MS));
    if (!mounted.current || leaving.current) return;
    setChoreography("releasing");
    await new Promise((resolve) => window.setTimeout(resolve, RELEASE_MS));
    if (!mounted.current || leaving.current) return;
    setLocked(false);
    touchLastActive(true);
    navigate("/app", { replace: true });
  }, [navigate]);

  useEffect(() => {
    const userId = session?.user.id;
    if (!userId || leaving.current || inflight.current) return;

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
    // A vault PIN costs a key derivation per try, so wait for all of its digits.
    const expected = pinLength(userId);
    if (expected !== null && pin.length !== expected) return;

    const wait = pin.length === PIN_LEN ? 40 : 320;
    const timer = window.setTimeout(() => {
      void (async () => {
        if (inflight.current || leaving.current) return;
        const attempt = pinAttempt.current;
        inflight.current = true;
        try {
          const result = await unlockWithPin(userId, pin);
          // Phrase reset may have started while this PIN was still being checked.
          if (attempt !== pinAttempt.current || !mounted.current || leaving.current) return;
          if (result.ok) {
            await enterApp();
            return;
          }
          if (result.kind === "gone") {
            // Too many wrong PINs: the server deleted its half of the key. The token is sealed
            // in the vault too, so the way back is a full sign-in and the phrase.
            const username = session?.user.username;
            clearSession();
            navigate("/login", { replace: true, state: { notice: result.message, username } });
            return;
          }
          if (result.kind === "offline" || pin.length >= PIN_LEN) fail(result.message);
        } finally {
          if (attempt === pinAttempt.current) inflight.current = false;
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

  /* "Forgot PIN": delete the server's pepper, then drop the PIN wrap and the session. The
     phrase step opens the vault with its history key, and a fresh PIN is chosen on the way back.
     The pepper has to go first: a copy of this browser could still unlock until it does, and
     the token is sealed, so this screen cannot call logout. The next login revokes that session. */
  async function resetWithPhrase() {
    if (!session || leaving.current) return;
    // A PIN check may already be in flight. It keeps running, and its result is ignored.
    pinAttempt.current += 1;
    leaving.current = true;
    inflight.current = true;
    setBusy(true);
    setError(null);
    try {
      await abandonPin(session.user.id);
    } catch {
      inflight.current = false;
      leaving.current = false;
      setBusy(false);
      setError("Can’t reach Shroud to turn off the PIN. Try again.");
      return;
    }
    if (!mounted.current) return;
    // Unlocked (no vault yet) the token is still here. Locked, it is sealed and this is a no-op.
    if (session.token) {
      void api.logout(session.token).catch(() => {
        /* still drop the local token, as Auth does */
      });
    }
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
        ? "This PIN encrypts and unlocks Shroud in this browser. Idle and hidden tabs lock after 5 minutes."
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
            <BrandMark size={60} />
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
