import type { ReactNode } from "react";
import { ChevronLeft, KeyRound, Lock, Smartphone } from "lucide-react";
import { BrandMark } from "../BrandMark";

const TRUST = [
  {
    Icon: Lock,
    title: "End-to-end encrypted",
    body: "Messages decrypt only on your devices.",
  },
  {
    Icon: Smartphone,
    title: "A first-class device",
    body: "This browser is one of up to five linked devices.",
  },
  {
    Icon: KeyRound,
    title: "Safety numbers",
    body: "Compare keys in person before you trust a change.",
  },
];

export function Stepper({ step, steps }: { step: number; steps: number }) {
  return (
    <div className="auth-steps" role="group" aria-label={`Step ${step} of ${steps}`}>
      {Array.from({ length: steps }, (_, i) => (
        <span key={i} className={i < step ? "auth-step done" : "auth-step"} />
      ))}
      <em>
        Step {step} of {steps}
      </em>
    </div>
  );
}

export function AuthLayout({
  title,
  subtitle,
  step,
  steps,
  onBack,
  backLabel = "Back",
  footer,
  children,
}: {
  title: string;
  subtitle?: ReactNode;
  step?: number;
  steps?: number;
  onBack?: () => void;
  backLabel?: string;
  footer?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="auth">
      <aside className="auth-brand">
        <span className="auth-glow" aria-hidden="true" />
        <div className="auth-brand-inner">
          <span className="auth-mark" aria-hidden="true">
            <BrandMark size={52} />
          </span>
          <p className="auth-brand-title">
            Private messaging,
            <br />
            fully encrypted
          </p>
          <p className="auth-brand-sub">
            No phone number. No email. Just your username and a 12-word encryption phrase.
          </p>
          <ul className="auth-trust">
            {TRUST.map(({ Icon, title: heading, body }) => (
              <li key={heading}>
                <span aria-hidden="true">
                  <Icon size={16} />
                </span>
                <div>
                  <strong>{heading}</strong>
                  <span>{body}</span>
                </div>
              </li>
            ))}
          </ul>
        </div>
      </aside>

      <main className="auth-panel">
        <div className="auth-card">
          <span className="auth-mark auth-mark-compact" aria-hidden="true">
            <BrandMark size={52} />
          </span>
          <p className="auth-mobile-kicker">Private messaging, fully encrypted</p>
          {onBack ? (
            <button type="button" className="auth-back" onClick={onBack}>
              <ChevronLeft size={16} aria-hidden="true" />
              {backLabel}
            </button>
          ) : null}
          {step && steps ? <Stepper step={step} steps={steps} /> : null}
          <h1>{title}</h1>
          {subtitle ? <p className="auth-sub">{subtitle}</p> : null}
          {children}
          {footer ? <p className="auth-foot">{footer}</p> : null}
        </div>
      </main>
    </div>
  );
}
