import type { LucideIcon } from "lucide-react";
import { Info, Search, TriangleAlert } from "lucide-react";
import type { ReactNode } from "react";

export type Tone = "ok" | "warn" | "danger" | "neutral";

export function PageHeader({ title, meta, children }: { title: string; meta?: ReactNode; children?: ReactNode }) {
  return (
    <header className="page-header">
      <div className="page-header__title">
        <h1>{title}</h1>
        {meta ? <div className="meta">{meta}</div> : null}
      </div>
      {children}
    </header>
  );
}

export function StatTile({
  label,
  value,
  sub,
  className,
  style,
}: {
  label: string;
  value: ReactNode;
  sub?: ReactNode;
  className?: string;
  style?: React.CSSProperties;
}) {
  return (
    <div className={className ? `stat-tile ${className}` : "stat-tile"} style={style} role="group" aria-label={label}>
      <div className="stat-tile__label" aria-hidden="true">
        {label}
      </div>
      <div className="stat-tile__value">{value}</div>
      {sub ? <div className="stat-tile__sub">{sub}</div> : null}
    </div>
  );
}

export function StatusPill({ tone, children }: { tone: Tone; children: ReactNode }) {
  return <span className={`pill pill--${tone}`}>{children}</span>;
}

export function Chip({
  tone,
  mono,
  children,
}: {
  tone?: Tone | "accent";
  mono?: boolean;
  children: ReactNode;
}) {
  const classes = ["chip", tone && tone !== "neutral" ? `chip--${tone}` : "", mono ? "chip--mono" : ""];
  return <span className={classes.filter(Boolean).join(" ")}>{children}</span>;
}

export function Button({
  variant = "secondary",
  icon: Icon,
  className,
  children,
  ...rest
}: {
  variant?: "primary" | "secondary" | "danger";
  icon?: LucideIcon;
  children: ReactNode;
} & React.ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button type="button" {...rest} className={["button", `button--${variant}`, className ?? ""].join(" ").trim()}>
      {Icon ? <Icon aria-hidden="true" /> : null}
      {children}
    </button>
  );
}

export function SearchField(props: React.InputHTMLAttributes<HTMLInputElement>) {
  return (
    <label className="search">
      <Search aria-hidden="true" />
      <input type="search" aria-label={typeof props.placeholder === "string" ? props.placeholder : "Search"} {...props} />
    </label>
  );
}

export function Card({ fill, children }: { fill?: boolean; children: ReactNode }) {
  return <section className={fill ? "card card--fill" : "card"}>{children}</section>;
}

export function CardHead({ title, sub, children }: { title: string; sub?: ReactNode; children?: ReactNode }) {
  return (
    <div className="card__head">
      <div className="card__head-text">
        <h2 className="card__title">{title}</h2>
        {sub ? <div className="card__sub">{sub}</div> : null}
      </div>
      {children}
    </div>
  );
}

export function CardBody({ children }: { children: ReactNode }) {
  return <div className="card__body">{children}</div>;
}

export function Footnote({ icon: Icon = Info, children }: { icon?: LucideIcon; children: ReactNode }) {
  return (
    <div className="card__footnote">
      <Icon aria-hidden="true" />
      <div>{children}</div>
    </div>
  );
}

export function Notice({ icon: Icon = Info, children }: { icon?: LucideIcon; children: ReactNode }) {
  return (
    <div className="notice" role="note">
      <Icon aria-hidden="true" />
      <div>{children}</div>
    </div>
  );
}

export function Banner({ title, body }: { title: string; body?: ReactNode }) {
  return (
    <div className="banner" role="status">
      <TriangleAlert aria-hidden="true" />
      <div>
        <div className="banner__title">{title}</div>
        {body ? <div className="banner__body">{body}</div> : null}
      </div>
    </div>
  );
}

/** A fixed-width or filling table cell; widths follow the frames' cell widths. */
export function Cell({
  width,
  right,
  className,
  children,
}: {
  width?: number;
  right?: boolean;
  className?: string;
  children?: ReactNode;
}) {
  const classes = ["cell", width === undefined ? "cell--fill" : "", right ? "cell--right" : "", className ?? ""];
  return (
    <div className={classes.filter(Boolean).join(" ")} style={width === undefined ? undefined : { width }}>
      {children}
    </div>
  );
}

export function TableHead({ children }: { children: ReactNode }) {
  return <div className="table__head">{children}</div>;
}

export function TableRow({ link, children, ...rest }: { link?: boolean; children: ReactNode } & React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div className={link ? "table__row table__row--link" : "table__row"} {...rest}>
      {children}
    </div>
  );
}

export function KeyValueRows({ rows }: { rows: { key: ReactNode; value: ReactNode }[] }) {
  return (
    <div className="kv">
      {rows.map((row, index) => (
        <div className="kv__row" key={index}>
          <div className="kv__key">{row.key}</div>
          <div className="kv__value">{row.value}</div>
        </div>
      ))}
    </div>
  );
}
