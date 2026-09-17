import { useCallback, useEffect, useRef, useState } from "react";
import { Lock, Delete } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { hasPin, loadSession, setLocked, setPin, verifyPin } from "../session";

const PIN_LEN = 6;
const KEYS = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "del"] as const;

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
            setLocked(false);
            navigate("/app", { replace: true });
          } catch {
            fail("Could not store the PIN. Open Shroud over HTTPS (or localhost).");
          } finally {
            inflight.current = false;
            setBusy(false);
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
            setBusy(true);
            setLocked(false);
            navigate("/app", { replace: true });
            return;
          }
          if (pin.length >= PIN_LEN) fail("Wrong PIN.");
        } finally {
          inflight.current = false;
          setBusy(false);
        }
      })();
    }, wait);
    return () => window.clearTimeout(timer);
  }, [pin, creating, phase, firstPin, session?.user.id, fail, navigate]);

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
        : `Signed in as @${session.user.username}. Enter your PIN to decrypt this browser.`;

  return (
    <div className={`lock-screen${ready ? " in" : ""}`}>
      <div className="lock-glow" aria-hidden />
      <div className="lock-body">
        <div className="lock-mark">
          <Lock size={28} strokeWidth={1.75} />
        </div>
        <h1>{title}</h1>
        <p className="lede">{lede}</p>

        <div
          className={`lock-dots${shake ? " shake" : ""}`}
          role="img"
          aria-label={`${pin.length} of ${PIN_LEN} digits entered`}
        >
          {Array.from({ length: PIN_LEN }, (_, i) => (
            <span
              key={i}
              className={`lock-dot${i < pin.length ? " filled" : ""}`}
              style={{ transitionDelay: `${i * 30}ms` }}
            />
          ))}
        </div>
        {error ? <p className="lock-err">{error}</p> : <p className="lock-err spacer">&nbsp;</p>}

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
      </div>
    </div>
  );
}
