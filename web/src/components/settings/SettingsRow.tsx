import type { ComponentType, ReactNode } from "react";
import { ChevronRight } from "lucide-react";

type IconProps = { size?: number };

/** A settings row: a quiet glyph, the title and its line, then the value, a switch or a chevron. */
export function SettingsRow({
  title,
  subtitle,
  value,
  Icon,
  onClick,
  danger = false,
  disabled = false,
  dot,
  wrapSubtitle = false,
}: {
  title: string;
  subtitle?: string;
  value?: string;
  Icon: ComponentType<IconProps>;
  onClick?: () => void;
  danger?: boolean;
  /** Shown but not usable right now (dimmed, still a button). */
  disabled?: boolean;
  /** An accent dot after the value, read out as this text (e.g. "Update available"). */
  dot?: string;
  /** The subtitle may wrap. Other rows stay on one line. */
  wrapSubtitle?: boolean;
}) {
  const interactive = Boolean(onClick);
  const body = (
    <>
      <span className={danger ? "set-ico danger" : "set-ico"} aria-hidden="true">
        <Icon size={20} />
      </span>
      <span className="set-row-copy">
        <strong className={danger ? "danger" : undefined}>{title}</strong>
        {subtitle ? <span className={wrapSubtitle ? "wrap" : undefined}>{subtitle}</span> : null}
      </span>
      {value ? <span className="set-row-value">{value}</span> : null}
      {dot ? <span className="set-dot" role="img" aria-label={dot} /> : null}
      {interactive ? <ChevronRight size={16} className="set-chevron" aria-hidden="true" /> : null}
    </>
  );

  if (!interactive) {
    return <div className="set-row">{body}</div>;
  }
  return (
    <button type="button" className="set-row set-row-button" onClick={onClick} disabled={disabled}>
      {body}
    </button>
  );
}

export function SettingsCard({ children }: { children: ReactNode }) {
  return <div className="set-card">{children}</div>;
}

export function SettingsGroup({ title, children }: { title?: string; children: ReactNode }) {
  return (
    <section className="set-group">
      {title ? <h2>{title}</h2> : null}
      <div className="set-card">{children}</div>
    </section>
  );
}

export function SettingsNote({ children }: { children: ReactNode }) {
  return <p className="set-note">{children}</p>;
}

export function Switch({
  checked,
  onChange,
  label,
  description,
  disabled = false,
}: {
  checked: boolean;
  onChange: (next: boolean) => void;
  label: string;
  description?: string;
  disabled?: boolean;
}) {
  return (
    <label className={disabled ? "set-toggle disabled" : "set-toggle"}>
      <span className="set-row-copy">
        <strong>{label}</strong>
        {description ? <span className="wrap">{description}</span> : null}
      </span>
      <input
        type="checkbox"
        role="switch"
        checked={checked}
        disabled={disabled}
        onChange={(event) => onChange(event.target.checked)}
      />
      <span className="set-switch" aria-hidden="true" />
    </label>
  );
}
