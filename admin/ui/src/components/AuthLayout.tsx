import { Check, CircleAlert, Copy } from "lucide-react";
import { useCallback, useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { flushSync } from "react-dom";
import { Mark } from "./Mark";

/** How a step arrives: sliding in from the right (forward), from the left (back), or fading. */
export type StepDirection = "forward" | "back" | "fade";
type Enter = StepDirection | "intro" | null;

const reducedMotion = () => window.matchMedia("(prefers-reduced-motion: reduce)").matches;

let transitionSeq = 0;

/** The current step of a signed-out flow and a `go` that animates to the next one. Where the
 *  browser has view transitions the outgoing step slides out while the brand and the progress bar
 *  glide to their new places (`<html data-auth-step>` scopes the CSS to these transitions);
 *  elsewhere the incoming step slides in on its own. Both move only transform and opacity.
 *  `alongside` runs in the same update, so state such as an error lands in the new snapshot. */
export function useSteps<S extends string>(initial: S) {
  const [state, setState] = useState<{ step: S; enter: Enter }>({ step: initial, enter: "intro" });

  const go = useCallback((step: S, direction: StepDirection = "forward", alongside?: () => void) => {
    const apply = (enter: Enter) => {
      alongside?.();
      setState({ step, enter });
    };
    if (reducedMotion()) return apply(null);
    if (typeof document.startViewTransition !== "function" || document.visibilityState !== "visible") {
      return apply(direction);
    }
    const root = document.documentElement;
    const seq = ++transitionSeq;
    root.dataset.authStep = direction;
    const transition = document.startViewTransition(() => flushSync(() => apply(null)));
    const done = () => {
      if (seq === transitionSeq) delete root.dataset.authStep;
    };
    transition.finished.then(done, done);
    transition.ready.catch(() => undefined);
  }, []);

  return { step: state.step, enter: state.enter, go };
}

/** The signed-out page: the brand (a top bar on phones), an optional progress bar, one step and a
 *  footer with this console's address (frames "Sign in" and "Set up · …"). */
export function AuthLayout({
  progress,
  note,
  children,
}: {
  progress?: { at: number; of: number };
  note?: string;
  children: ReactNode;
}) {
  return (
    <div className="auth">
      <main className="auth__main">
        <div className="auth__column">
          <div className="auth__brand">
            <Mark size={44} />
            <div className="auth__wordmark">
              <span className="auth__name">Shroud</span>
              <span className="admin-tag">ADMIN</span>
            </div>
          </div>
          {progress ? <Progress at={progress.at} of={progress.of} /> : null}
          {children}
        </div>
      </main>
      <footer className="auth__footer">
        <span className="auth__host">{window.location.host}/admin</span>
        {note ? (
          <>
            <span className="auth__dot" aria-hidden="true" />
            <span>{note}</span>
          </>
        ) : null}
      </footer>
    </div>
  );
}

function Progress({ at, of }: { at: number; of: number }) {
  return (
    <div className="progress" role="progressbar" aria-valuemin={1} aria-valuemax={of} aria-valuenow={at} aria-label={`Step ${at} of ${of}`}>
      <div className="progress__bars">
        {Array.from({ length: of }, (_, index) => (
          <span className="progress__bar" key={index}>
            <span className="progress__fill" data-on={index < at ? "" : undefined} />
          </span>
        ))}
      </div>
      <span className="progress__count" aria-hidden="true">
        {at} of {of}
      </span>
    </div>
  );
}

const SHAKE: Keyframe[] = [
  { transform: "translateX(0)" },
  { transform: "translateX(-7px)" },
  { transform: "translateX(6px)" },
  { transform: "translateX(-4px)" },
  { transform: "translateX(2px)" },
  { transform: "translateX(0)" },
];

/** One step: heading, an optional error, the fields and the buttons. Give each step its own `id`
 *  (and React key): the id names its view transition, so the old and the new step animate apart.
 *  The step takes focus when it arrives (its autofocus field, else its heading); a change of
 *  `shake` shakes the fields, as after a wrong code. */
export function AuthStep({
  id,
  enter,
  icon,
  title,
  sub,
  error,
  shake = 0,
  onSubmit,
  actions,
  below,
  children,
}: {
  id: string;
  enter: Enter;
  icon?: ReactNode;
  title: string;
  sub?: ReactNode;
  error?: { title: string; body?: ReactNode } | null;
  shake?: number;
  onSubmit?: (event: FormEvent) => void;
  actions?: ReactNode;
  below?: ReactNode;
  children?: ReactNode;
}) {
  const sectionRef = useRef<HTMLElement>(null);
  const bodyRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const section = sectionRef.current;
    if (section && !section.contains(document.activeElement)) {
      section.querySelector<HTMLElement>(".auth-step__title")?.focus({ preventScroll: true });
    }
  }, []);

  useEffect(() => {
    if (shake === 0 || reducedMotion()) return;
    bodyRef.current?.animate(SHAKE, { duration: 380, easing: "cubic-bezier(0.36, 0.07, 0.19, 0.97)" });
  }, [shake]);

  const Form = onSubmit ? "form" : "div";
  return (
    <section
      ref={sectionRef}
      className="auth-step"
      data-enter={enter ?? undefined}
      style={{ viewTransitionName: `auth-step-${id}` }}
      aria-labelledby={`auth-step-${id}`}
    >
      <header className="auth-step__head">
        {icon ? <div className="auth-step__icon">{icon}</div> : null}
        <h1 className="auth-step__title" id={`auth-step-${id}`} tabIndex={-1}>
          {title}
        </h1>
        {sub ? <p className="auth-step__sub">{sub}</p> : null}
      </header>
      <Form className="auth-step__form" onSubmit={onSubmit} noValidate={onSubmit ? true : undefined}>
        {error ? <ErrorBox title={error.title} body={error.body} /> : null}
        {children ? (
          <div className="auth-step__body" ref={bodyRef}>
            {children}
          </div>
        ) : null}
        {actions ? <div className="auth-step__actions">{actions}</div> : null}
      </Form>
      {below ? <div className="auth-step__below">{below}</div> : null}
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

/** A button that copies `text` and says so for a moment; the icon swaps with a small pop. */
export function CopyButton({ text, label = "Copy", className = "link-button" }: { text: string; label?: string; className?: string }) {
  const [copied, setCopied] = useState(false);
  const [clicked, setClicked] = useState(false);

  useEffect(() => {
    if (!copied) return;
    const timer = window.setTimeout(() => setCopied(false), 1800);
    return () => window.clearTimeout(timer);
  }, [copied]);

  const copy = async () => {
    setClicked(true);
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  };

  return (
    <button type="button" className={className} onClick={() => void copy()}>
      <span className={clicked ? "swap swap--animate" : "swap"} key={copied ? "copied" : "copy"} aria-hidden="true">
        {copied ? <Check /> : <Copy />}
      </span>
      <span aria-live="polite">{copied ? "Copied" : label}</span>
    </button>
  );
}

export function Spinner() {
  return <span className="spinner" aria-hidden="true" />;
}
