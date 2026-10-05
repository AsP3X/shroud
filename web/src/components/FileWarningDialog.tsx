import { useEffect, useRef } from "react";
import { warningDialog, type FileWarning } from "../files";

/**
 * Asked before a received APK or Office file with macros leaves the browser (docs/file-sharing.md
 * §6). Cancel has focus, so a stray Enter never hands the file over; each answer covers one
 * action and nothing is remembered. Escape backs out in the capture phase, so it doesn't also
 * drop a reply in progress.
 */
export function FileWarningDialog({
  warning,
  sender,
  onCancel,
  onContinue,
}: {
  warning: FileWarning;
  /** The contact's display name. */
  sender: string;
  onCancel: () => void;
  onContinue: () => void;
}) {
  const cancel = useRef<HTMLButtonElement>(null);
  const close = useRef(onCancel);
  close.current = onCancel;
  const { title, message } = warningDialog(warning, sender);

  useEffect(() => {
    cancel.current?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.preventDefault();
      event.stopImmediatePropagation();
      close.current();
    };
    window.addEventListener("keydown", onKeyDown, true);
    return () => window.removeEventListener("keydown", onKeyDown, true);
  }, []);

  return (
    <div className="modal-scrim" onMouseDown={() => close.current()}>
      <div
        className="modal"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="file-warning-title"
        aria-describedby="file-warning-body"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header>
          <h2 id="file-warning-title">{title}</h2>
        </header>
        <p id="file-warning-body">{message}</p>
        <div className="modal-actions">
          <button ref={cancel} type="button" className="btn btn-secondary" onClick={() => close.current()}>
            Cancel
          </button>
          <button type="button" className="btn btn-destructive" onClick={onContinue}>
            Continue
          </button>
        </div>
      </div>
    </div>
  );
}
