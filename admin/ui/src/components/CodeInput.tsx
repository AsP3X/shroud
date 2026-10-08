import { useId, useState } from "react";

const LENGTH = 6;

/** Six cells over one real input, so the browser's one-time-code autofill and screen readers see
 *  a plain numeric field while the eye sees the frame's digit boxes. */
export function CodeInput({
  value,
  onChange,
  disabled,
  label = "Authenticator code",
}: {
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
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
        disabled={disabled}
        onChange={(event) => onChange(event.target.value.replace(/\D/g, "").slice(0, LENGTH))}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
      />
      {digits.map((digit, index) => {
        const isActive = focused && index === active && value.length < LENGTH;
        return (
          <div key={index} className={isActive ? "code__cell code__cell--active" : "code__cell"} aria-hidden="true">
            {digit.trim() ? digit : isActive ? <span className="code__caret" /> : null}
          </div>
        );
      })}
    </div>
  );
}
