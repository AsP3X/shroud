import { useEffect, useId, useRef, useState, type FormEvent } from "react";
import { AtSign, Eye, EyeOff, LoaderCircle, MessageSquareX, Smartphone, Trash2, Users } from "lucide-react";
import { api, type Session } from "../../api/client";
import { DELETE_ACCOUNT_COPY, mapDeleteAccountAnswer } from "./deleteAccount";
import { SettingsCard } from "./SettingsRow";

const CONSEQUENCES = [
  { Icon: MessageSquareX, text: DELETE_ACCOUNT_COPY.consequences[0] },
  { Icon: Users, text: DELETE_ACCOUNT_COPY.consequences[1] },
  { Icon: Trash2, text: DELETE_ACCOUNT_COPY.consequences[2] },
  { Icon: AtSign, text: DELETE_ACCOUNT_COPY.consequences[3] },
  { Icon: Smartphone, text: DELETE_ACCOUNT_COPY.consequences[4] },
] as const;

/**
 * The warning and the password. While the request runs nothing on this screen dismisses it:
 * the field, Cancel and Back are disabled, and Escape is swallowed. The password is cleared
 * when leaving and after a wipe; a wrong password stays selected.
 */
export function DeleteAccountView({
  session,
  onCancel,
  onAccountDeleted,
  onUnauthorized,
  onBusyChange,
}: {
  session: Session;
  onCancel: () => void;
  onAccountDeleted: () => void;
  onUnauthorized: (err?: unknown) => void;
  onBusyChange: (busy: boolean) => void;
}) {
  const fieldId = useId();
  const errorId = useId();
  const passwordRef = useRef<HTMLInputElement>(null);
  const submitting = useRef(false);
  /** Set before the request yields, so Escape is swallowed in that same turn. */
  const busyRef = useRef(false);
  const [password, setPassword] = useState("");
  const [shown, setShown] = useState(false);
  const [busy, setBusy] = useState(false);
  const [fieldError, setFieldError] = useState<string | null>(null);

  useEffect(() => {
    if (busy || fieldError !== DELETE_ACCOUNT_COPY.wrongPassword) return;
    const input = passwordRef.current;
    if (!input) return;
    input.focus({ preventScroll: true });
    input.select();
  }, [busy, fieldError]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (!busyRef.current || event.key !== "Escape") return;
      event.preventDefault();
      event.stopImmediatePropagation();
    };
    window.addEventListener("keydown", onKeyDown, true);
    return () => window.removeEventListener("keydown", onKeyDown, true);
  }, []);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (submitting.current || password.length === 0) return;
    submitting.current = true;
    busyRef.current = true;
    setBusy(true);
    setFieldError(null);
    onBusyChange(true);
    let secret = password;
    let hold = false;
    try {
      await api.deleteAccount(session.token, secret);
      secret = "";
      hold = true;
      setPassword("");
      onAccountDeleted();
    } catch (err) {
      secret = "";
      const outcome = mapDeleteAccountAnswer(err);
      if (outcome === "deleted" || outcome === "wiped") {
        hold = true;
        setPassword("");
        onAccountDeleted();
        return;
      }
      if (outcome === "wrong-password") {
        setFieldError(DELETE_ACCOUNT_COPY.wrongPassword);
        return;
      }
      if (outcome === "rate-limited") {
        setFieldError(DELETE_ACCOUNT_COPY.rateLimited);
        return;
      }
      if (outcome === "signed-out") {
        hold = true;
        setPassword("");
        onUnauthorized(err);
        return;
      }
      setFieldError(DELETE_ACCOUNT_COPY.unreachable);
    } finally {
      secret = "";
      if (!hold) {
        busyRef.current = false;
        submitting.current = false;
        setBusy(false);
        onBusyChange(false);
      }
    }
  }

  return (
    <div className="delete-account">
      <h2 className="delete-account-title">{DELETE_ACCOUNT_COPY.title}</h2>
      <SettingsCard>
        <ul className="delete-account-list">
          {CONSEQUENCES.map((line) => (
            <li key={line.text}>
              <line.Icon size={16} aria-hidden="true" />
              <span>{line.text}</span>
            </li>
          ))}
        </ul>
      </SettingsCard>
      <form className="delete-account-form" onSubmit={(event) => void submit(event)}>
        <p className="delete-account-note">{DELETE_ACCOUNT_COPY.confirm}</p>
        <div className="afield">
          <label htmlFor={fieldId}>{DELETE_ACCOUNT_COPY.passwordLabel}</label>
          <div className="afield-wrap">
            <input
              ref={passwordRef}
              id={fieldId}
              className={fieldError ? "afield-input invalid" : "afield-input"}
              type={shown ? "text" : "password"}
              name="password"
              autoComplete="current-password"
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
              placeholder={DELETE_ACCOUNT_COPY.passwordPlaceholder}
              value={password}
              disabled={busy}
              aria-invalid={fieldError ? true : undefined}
              aria-describedby={fieldError ? errorId : undefined}
              onChange={(event) => {
                setPassword(event.target.value);
                if (fieldError) setFieldError(null);
              }}
            />
            <button
              type="button"
              className="afield-reveal"
              onClick={() => setShown((on) => !on)}
              disabled={busy}
              aria-label={shown ? "Hide password" : "Show password"}
              aria-pressed={shown}
              tabIndex={-1}
            >
              {shown ? <EyeOff size={16} /> : <Eye size={16} />}
            </button>
          </div>
          {fieldError ? (
            <p id={errorId} className="delete-account-error" role="alert">
              {fieldError}
            </p>
          ) : null}
        </div>
        <div className="delete-account-actions">
          <button type="button" className="btn btn-secondary" onClick={onCancel} disabled={busy}>
            {DELETE_ACCOUNT_COPY.cancel}
          </button>
          <button
            type="submit"
            className={busy ? "btn btn-destructive is-busy" : "btn btn-destructive"}
            disabled={busy || password.length === 0}
          >
            {busy ? (
              <>
                <LoaderCircle className="wipe-foot-spinner" size={15} aria-hidden="true" />
                {DELETE_ACCOUNT_COPY.submitting}
              </>
            ) : (
              DELETE_ACCOUNT_COPY.submit
            )}
          </button>
        </div>
      </form>
    </div>
  );
}
