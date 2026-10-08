import type { LucideIcon } from "lucide-react";
import { useEffect, useRef, type ReactNode } from "react";
import { createPortal } from "react-dom";

export type DialogTone = "accent" | "warn" | "danger";

/** The frames' 440 px dialog over a scrim: modal, focus moves in and stays, Escape cancels,
 *  focus returns to the element that opened it. */
export function Dialog({
  icon: Icon,
  tone,
  title,
  body,
  children,
  actions,
  onClose,
  labelledBy = "dialog-title",
}: {
  icon: LucideIcon;
  tone: DialogTone;
  title: string;
  body?: ReactNode;
  children?: ReactNode;
  actions: ReactNode;
  onClose: () => void;
  labelledBy?: string;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const opener = useRef<Element | null>(null);

  useEffect(() => {
    opener.current = document.activeElement;
    const dialog = ref.current;
    const focusable = () =>
      Array.from(dialog?.querySelectorAll<HTMLElement>("input:not([disabled]), button:not([disabled]), a[href]") ?? []);
    (focusable().find((element) => element.tagName === "INPUT") ?? focusable()[0])?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        onClose();
        return;
      }
      if (event.key !== "Tab") return;
      const items = focusable();
      const first = items[0];
      const last = items[items.length - 1];
      if (!first || !last) return;
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener("keydown", onKey);
    const previous = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.style.overflow = previous;
      if (opener.current instanceof HTMLElement) opener.current.focus();
    };
  }, [onClose]);

  return createPortal(
    <div className="scrim" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby={labelledBy} ref={ref}>
        <span className={`dialog__icon dialog__icon--${tone}`} aria-hidden="true">
          <Icon />
        </span>
        <div className="dialog__text">
          <h2 className="dialog__title" id={labelledBy}>
            {title}
          </h2>
          {body ? <div className="dialog__body">{body}</div> : null}
        </div>
        {children}
        <div className="dialog__actions">{actions}</div>
      </div>
    </div>,
    document.body,
  );
}
