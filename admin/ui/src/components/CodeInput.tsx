import { useId, useState } from "react";

const LENGTH = 6;

/** Six cells over one real input, so the browser's one-time-code autofill and screen readers see
 *  a plain numeric field while the eye sees the frame's digit boxes. A digit pops in as it is
 *  typed: each one is keyed by its value, so a change remounts it and replays the CSS animation.
 *  While `disabled` (a check in flight) the field is read-only rather than disabled, so it keeps
 *  focus and a wrong code can be retyped straight away. */
export function CodeInput({
  value,
  onChange,
  disabled,
  autoFocus,
  label = "Authenticator code",
}: {
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
  autoFocus?: boolean;
  label?: string;
}) {
  const id = useId();
  const [focused, setFocused] = useState(false);
  const digits = value.padEnd(LENGTH, " ").slice(0, LENGTH).split("");
  const active = Math.min(value.length, LENGTH - 1);

  return (
    <div className="code">
      <input
        id={id}
        className="code__input"
        type="text"
        inputMode="numeric"
        autoComplete="one-time-code"
        pattern="[0-9]*"
        maxLength={LENGTH}
        aria-label={label}
        value={value}
        readOnly={disabled}
        aria-disabled={disabled || undefined}
        autoFocus={autoFocus}
        onChange={(event) => onChange(event.target.value.replace(/\D/g, "").slice(0, LENGTH))}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
      />
      {digits.map((digit, index) => {
        const isActive = focused && index === active && value.length < LENGTH;
        const filled = digit.trim() !== "";
        const classes = ["code__cell", isActive ? "code__cell--active" : "", filled ? "code__cell--filled" : ""];
        return (
          <div key={index} className={classes.filter(Boolean).join(" ")} aria-hidden="true">
            {filled ? (
              <span className="code__digit" key={digit}>
                {digit}
              </span>
            ) : isActive ? (
              <span className="code__caret" />
            ) : null}
          </div>
        );
      })}
    </div>
  );
}
