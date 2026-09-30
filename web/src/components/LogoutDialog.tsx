import { useEffect, useRef } from "react";

/**
 * "Log out of Shroud?" — the one confirmation for logging out, whether it was asked for from
 * Settings or from the account menu on the rail. Confirming hands over to `DeviceWipeDialog`.
 *
 * Cancel has focus, so a stray Enter never logs anyone out. Escape backs out and is taken in
 * the capture phase, so it doesn't also leave the settings page behind the dialog.
 */
export function LogoutDialog({
  onCancel,
  onConfirm,
}: {
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const cancel = useRef<HTMLButtonElement>(null);
  const close = useRef(onCancel);
  close.current = onCancel;

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
        aria-labelledby="logout-title"
        aria-describedby="logout-body"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header>
          <h2 id="logout-title">Log out of Shroud?</h2>
        </header>
        <p id="logout-body">
          Everything Shroud keeps in this browser is deleted: messages, photos and voice notes,
          your encryption keys and settings. Your other devices keep your chats. To sign in
          again you’ll need your password and encryption phrase.
        </p>
        <div className="modal-actions">
          <button ref={cancel} type="button" className="btn btn-secondary" onClick={() => close.current()}>
            Cancel
          </button>
          <button type="button" className="btn btn-destructive" onClick={onConfirm}>
            Log Out
          </button>
        </div>
      </div>
    </div>
  );
}
