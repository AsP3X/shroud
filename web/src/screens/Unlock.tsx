import { useState, type FormEvent } from "react";
import { Lock } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { hasPin, loadSession, setLocked, setPin, verifyPin } from "../session";

export function Unlock() {
  const navigate = useNavigate();
  const session = loadSession();
  const creating = Boolean(session && !hasPin(session.user.id));
  const [pin, setPinValue] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (!session) return null;
  const userId = session.user.id;

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    if (pin.length < 4) {
      setError("Use at least 4 digits.");
      return;
    }
    if (creating && pin !== confirm) {
      setError("PINs do not match.");
      return;
    }
    setBusy(true);
    try {
      if (creating) {
        await setPin(userId, pin);
      } else if (!(await verifyPin(userId, pin))) {
        setError("Wrong PIN.");
        return;
      }
      setLocked(false);
      navigate("/app", { replace: true });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="screen">
      <form className="stack" onSubmit={onSubmit}>
        <div className="mark" style={{ background: "var(--accent-soft)", color: "var(--accent)" }}>
          <Lock size={24} />
        </div>
        <div>
          <h1>{creating ? "Choose a PIN" : "Chats are locked"}</h1>
          <p className="lede">
            {creating
              ? "This PIN unlocks Shroud on this browser. Idle and hidden tabs lock after 5 minutes."
              : "Enter your PIN to unlock this browser. Idle and hidden tabs lock after 5 minutes."}
          </p>
        </div>
        <div className="pin-row" aria-hidden>
          {Array.from({ length: 6 }, (_, i) => (
            <span key={i} className={i < pin.length ? "pin-dot filled" : "pin-dot"} />
          ))}
        </div>
        <input
          className="field"
          inputMode="numeric"
          autoComplete="off"
          maxLength={6}
          placeholder="PIN"
          value={pin}
          onChange={(e) => setPinValue(e.target.value.replace(/\D/g, "").slice(0, 6))}
          autoFocus
        />
        {creating ? (
          <input
            className="field"
            inputMode="numeric"
            autoComplete="off"
            maxLength={6}
            placeholder="Confirm PIN"
            value={confirm}
            onChange={(e) => setConfirm(e.target.value.replace(/\D/g, "").slice(0, 6))}
          />
        ) : null}
        {error ? <p className="err">{error}</p> : null}
        <div className="actions">
          <button className="btn btn-primary" type="submit" disabled={busy}>
            {busy ? "Please wait…" : creating ? "Save PIN" : "Unlock"}
          </button>
        </div>
      </form>
    </div>
  );
}
