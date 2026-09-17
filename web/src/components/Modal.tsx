import { useEffect, useRef, type ReactNode } from "react";
import { X } from "lucide-react";

export function Modal({
  title,
  onClose,
  children,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
}) {
  const panel = useRef<HTMLDivElement>(null);

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") onClose();
    }
    document.addEventListener("keydown", onKeyDown);
    const focusable = panel.current?.querySelector<HTMLElement>(
      "input, textarea, button:not([disabled])",
    );
    focusable?.focus();
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  return (
    <div className="modal-scrim" onMouseDown={onClose}>
      <div
        className="modal"
        role="dialog"
        aria-modal="true"
        aria-label={title}
        ref={panel}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header>
          <h2>{title}</h2>
          <button className="icon-btn" type="button" onClick={onClose} aria-label="Close">
            <X size={18} />
          </button>
        </header>
        {children}
      </div>
    </div>
  );
}
