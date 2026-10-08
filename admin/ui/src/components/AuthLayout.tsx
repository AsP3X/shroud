import { CircleAlert, Shield } from "lucide-react";
import type { ReactNode } from "react";
import { Mark } from "./Mark";

/** The signed-out layout: the brand on top, one card in the middle (frame "Sign in"). */
export function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <div className="auth">
      <div className="auth__top">
        <Mark size={28} />
        <span className="auth__top-name">Shroud</span>
        <span className="admin-tag">ADMIN</span>
      </div>
      <div className="auth__center">{children}</div>
    </div>
  );
}

export function AuthCard({ title, sub, children }: { title: string; sub?: ReactNode; children: ReactNode }) {
  return (
    <section className="auth-card" aria-labelledby="auth-title">
      <div className="auth-card__head">
        <h1 className="auth-card__title" id="auth-title">
          {title}
        </h1>
        {sub ? <p className="auth-card__sub" style={{ margin: 0 }}>{sub}</p> : null}
      </div>
      {children}
    </section>
  );
}

export function ErrorBox({ title, body }: { title: string; body?: ReactNode }) {
  return (
    <div className="error-box" role="alert">
      <CircleAlert aria-hidden="true" />
      <div>
        <div className="error-box__title">{title}</div>
        {body ? <div className="error-box__body">{body}</div> : null}
      </div>
    </div>
  );
}

export function AuthFootnote({ children }: { children: ReactNode }) {
  return (
    <div className="auth-footnote">
      <Shield aria-hidden="true" />
      <div>{children}</div>
    </div>
  );
}

export function Field({ label, action, children }: { label: string; action?: ReactNode; children: ReactNode }) {
  return (
    <label className="field">
      <span className="field__label">
        <span>{label}</span>
        {action}
      </span>
      {children}
    </label>
  );
}
