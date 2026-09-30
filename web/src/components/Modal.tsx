import { useEffect, useRef, type ReactNode } from "react";
import { X } from "lucide-react";

export function Modal({
  title,
  onClose,
  children,
  className,
  sheet = false,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
  className?: string;
  /** Becomes a bottom sheet below 900px. */
  sheet?: boolean;
}) {
  const panel = useRef<HTMLDivElement>(null);
  /* Read through a ref: callers pass inline closures, and re-running the effect
     on every parent render used to pull focus back to the first control. */
  const close = useRef(onClose);
  close.current = onClose;

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") close.current();
    }
    document.addEventListener("keydown", onKeyDown);
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    (
      panel.current?.querySelector<HTMLElement>("[data-autofocus]") ??
      panel.current?.querySelector<HTMLElement>("input, textarea, button:not([disabled])")
    )?.focus();
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.body.style.overflow = previousOverflow;
    };
  }, []);

  const variant = sheet ? " sheet" : "";
  return (
    <div className={`modal-scrim${variant}`} onMouseDown={() => close.current()}>
      <div
        className={`modal${variant}${className ? ` ${className}` : ""}`}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        ref={panel}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header>
          <h2>{title}</h2>
          <button className="icon-btn" type="button" onClick={() => close.current()} aria-label="Close">
            <X size={18} />
          </button>
        </header>
        {children}
      </div>
    </div>
  );
}
