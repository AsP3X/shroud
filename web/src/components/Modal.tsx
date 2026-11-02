import { useEffect, useLayoutEffect, useRef, type ReactNode } from "react";
import { X } from "lucide-react";

export function Modal({
  title,
  onClose,
  children,
  className,
  sheet = false,
  actions,
  morphKey,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
  className?: string;
  /** Becomes a bottom sheet below 900px. */
  sheet?: boolean;
  /** Header buttons drawn before the close button (the text viewer's Download). */
  actions?: ReactNode;
  /**
   * One dialog becoming another: when this changes, the box stays where it is and grows or
   * shrinks to the new content while the old content fades out and the new one settles in.
   */
  morphKey?: string;
}) {
  const panel = useRef<HTMLDivElement>(null);
  const content = useRef<HTMLDivElement>(null);
  const last = useRef<{ key: string | undefined; rect: DOMRect; html: string } | null>(null);
  /* The morph: measured against the box the previous content left, after React has laid the
     new content out. The old content lives on in a ghost that fades as the box moves. */
  useLayoutEffect(() => {
    const box = panel.current;
    if (!box) return;
    const prev = last.current;
    const rect = box.getBoundingClientRect();
    last.current = { key: morphKey, rect, html: box.innerHTML };
    if (!prev || prev.key === morphKey || morphKey === undefined) return;
    if (typeof box.animate !== "function" || window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    const ease = "cubic-bezier(0.16, 1, 0.3, 1)";
    const ghost = document.createElement("div");
    ghost.className = "modal-ghost";
    ghost.setAttribute("aria-hidden", "true");
    ghost.style.width = `${prev.rect.width}px`;
    ghost.innerHTML = prev.html;
    const fresh = content.current;
    const style = getComputedStyle(box);
    if (fresh) fresh.style.width = `${rect.width - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight) - 2}px`;
    box.classList.add("is-morphing");
    box.appendChild(ghost);
    const grow = box.animate(
      [
        { width: `${prev.rect.width}px`, height: `${prev.rect.height}px` },
        { width: `${rect.width}px`, height: `${rect.height}px` },
      ],
      { duration: 400, easing: ease },
    );
    const done = () => {
      box.classList.remove("is-morphing");
      if (fresh) fresh.style.width = "";
      ghost.remove();
      if (last.current) last.current.rect = box.getBoundingClientRect();
      (
        box.querySelector<HTMLElement>("[data-autofocus]") ??
        box.querySelector<HTMLElement>(".modal-content input, .modal-content textarea")
      )?.focus();
    };
    grow.onfinish = done;
    grow.oncancel = done;
    ghost.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 140, easing: "ease-out", fill: "forwards" });
    box.querySelector("header")?.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 200, delay: 120, easing: ease, fill: "backwards" });
    fresh?.animate([{ opacity: 0, transform: "translateY(10px)" }, { opacity: 1, transform: "none" }], { duration: 280, delay: 120, easing: ease, fill: "backwards" });
  }, [morphKey]);
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
          {actions ? (
            <span className="modal-head-actions">
              {actions}
              <button className="icon-btn" type="button" onClick={() => close.current()} aria-label="Close">
                <X size={18} />
              </button>
            </span>
          ) : (
            <button className="icon-btn" type="button" onClick={() => close.current()} aria-label="Close">
              <X size={18} />
            </button>
          )}
        </header>
        <div className="modal-content" ref={content}>
          {children}
        </div>
      </div>
    </div>
  );
}
