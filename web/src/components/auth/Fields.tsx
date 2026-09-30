import { useId, useState, type InputHTMLAttributes, type ReactNode } from "react";
import { Check, Eye, EyeOff, TriangleAlert } from "lucide-react";

type BaseProps = Omit<InputHTMLAttributes<HTMLInputElement>, "onChange" | "value"> & {
  label: string;
  value: string;
  onChange: (value: string) => void;
  hint?: ReactNode;
  error?: string | null;
  ok?: boolean;
};

function inputClass(error?: string | null, ok?: boolean): string {
  if (error) return "afield-input invalid";
  if (ok) return "afield-input ok";
  return "afield-input";
}

export function TextField({ label, value, onChange, hint, error, ok, ...rest }: BaseProps) {
  const id = useId();
  return (
    <div className="afield">
      <label htmlFor={id}>{label}</label>
      <input
        id={id}
        className={inputClass(error, ok)}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        aria-invalid={error ? true : undefined}
        {...rest}
      />
      {error ? <p className="afield-note error">{error}</p> : hint ? <p className="afield-note">{hint}</p> : null}
    </div>
  );
}

export function PasswordField({ label, value, onChange, hint, error, ok, ...rest }: BaseProps) {
  const id = useId();
  const [shown, setShown] = useState(false);
  const [caps, setCaps] = useState(false);

  return (
    <div className="afield">
      <label htmlFor={id}>{label}</label>
      <div className="afield-wrap">
        <input
          id={id}
          className={inputClass(error, ok)}
          type={shown ? "text" : "password"}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          onKeyUp={(event) => setCaps(event.getModifierState?.("CapsLock") ?? false)}
          onBlur={() => setCaps(false)}
          aria-invalid={error ? true : undefined}
          {...rest}
        />
        <button
          type="button"
          className="afield-reveal"
          onClick={() => setShown((on) => !on)}
          aria-label={shown ? "Hide password" : "Show password"}
          aria-pressed={shown}
          tabIndex={-1}
        >
          {shown ? <EyeOff size={16} /> : <Eye size={16} />}
        </button>
      </div>
      {error ? (
        <p className="afield-note error">{error}</p>
      ) : hint ? (
        <p className="afield-note">{hint}</p>
      ) : null}
      {caps ? (
        <p className="afield-note warn">
          <TriangleAlert size={12} aria-hidden="true" /> Caps Lock is on
        </p>
      ) : null}
    </div>
  );
}

/** Live pass/fail list — the rules are the same ones the server enforces. */
export function RuleList({ rules }: { rules: { label: string; met: boolean }[] }) {
  return (
    <ul className="auth-rules">
      {rules.map(({ label, met }) => (
        <li key={label} className={met ? "met" : undefined}>
          <span aria-hidden="true">{met ? <Check size={12} /> : <span className="auth-rule-dot" />}</span>
          {label}
          <span className="sr-only">{met ? " — met" : " — not met yet"}</span>
        </li>
      ))}
    </ul>
  );
}

export function StrengthMeter({ score, level }: { score: number; level: string }) {
  const pct = Math.round(score * 100);
  return (
    <div className="auth-strength" aria-live="polite">
      <div className="auth-strength-track">
        <div
          className="auth-strength-fill"
          data-level={level.toLowerCase() || "none"}
          style={{ width: `${pct}%` }}
        />
      </div>
      <span>{level || "—"}</span>
    </div>
  );
}
