import { useEffect, useRef } from "react";
import { createPortal } from "react-dom";
import { DEVICE_LIMIT, type OldestDevice } from "../../deviceLimit";
import { listTimestamp, longDate } from "../../format";
import { DeviceTile } from "../settings/DevicesView";

/**
 * "Log out your oldest device?" — a login that found every device slot signed in. Same copy as
 * the iOS and Android apps. The device has no name here (it is sealed with the phrase, which
 * this browser doesn't have yet), so the card tells it by its dates, written the way
 * Settings › Devices writes them.
 *
 * Cancel has focus, so a stray Enter never logs a device out. Escape and the scrim cancel, and
 * Tab stays inside, except while the retry runs: then nothing closes it.
 */
export function DeviceLimitDialog({
  device,
  busy,
  onCancel,
  onConfirm,
}: {
  device: OldestDevice;
  busy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const card = useRef<HTMLDivElement>(null);
  const cancel = useRef<HTMLButtonElement>(null);
  const confirm = useRef<HTMLButtonElement>(null);
  const state = useRef({ busy, onCancel });
  state.current = { busy, onCancel };

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        event.stopImmediatePropagation();
        if (!state.current.busy) state.current.onCancel();
        return;
      }
      if (event.key !== "Tab") return;
      event.preventDefault();
      const buttons = [...(card.current?.querySelectorAll<HTMLButtonElement>("button:not([disabled])") ?? [])];
      if (buttons.length === 0) return;
      const index = buttons.indexOf(document.activeElement as HTMLButtonElement);
      const step = event.shiftKey ? -1 : 1;
      buttons[(index + step + buttons.length) % buttons.length]?.focus();
    };
    window.addEventListener("keydown", onKeyDown, true);
    return () => window.removeEventListener("keydown", onKeyDown, true);
  }, []);

  // Busy, focus stays on the button that shows it (Cancel is disabled). A swapped-in device
  // (the first one was gone by the retry) is a new question, so it starts at Cancel again.
  useEffect(() => {
    (busy ? confirm : cancel).current?.focus();
  }, [busy, device.id]);

  const lastActive = device.last_seen_at ? `Last active ${listTimestamp(device.last_seen_at)}` : "Never active";
  const linked = `Linked ${longDate(device.created_at)}`;

  // On <body>: the auth screen's own styles and stacking stay out of the dialog.
  return createPortal(
    <div className="modal-scrim" onMouseDown={() => (busy ? undefined : onCancel())}>
      <div
        ref={card}
        className="modal dev-limit"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="dev-limit-title"
        aria-describedby="dev-limit-body dev-limit-note"
        aria-busy={busy || undefined}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header>
          <h2 id="dev-limit-title">Log out your oldest device?</h2>
        </header>
        <p id="dev-limit-body">
          Your account is logged in on {DEVICE_LIMIT} devices, the most it can have. To log in
          here, Shroud logs out the one you used least recently:
        </p>
        <div className="set-card">
          <div className="set-row">
            <DeviceTile label={null} />
            <span className="set-row-copy">
              <strong>{lastActive}</strong>
              <span>{linked}</span>
            </span>
          </div>
        </div>
        <p id="dev-limit-note" className="dev-limit-note">
          It’s logged out right away and erases everything of your account on it: messages, keys
          and files. What it already sent stays in your chats.
        </p>
        <div className="modal-actions">
          <button ref={cancel} type="button" className="btn btn-secondary" onClick={onCancel} disabled={busy}>
            Cancel
          </button>
          <button
            ref={confirm}
            type="button"
            className="btn btn-destructive"
            // Not `disabled`: that would drop focus out of the dialog and dim the button.
            aria-disabled={busy || undefined}
            onClick={() => (busy ? undefined : onConfirm())}
          >
            {busy ? (
              <>
                <span className="dev-spinner on-danger" aria-hidden="true" />
                Logging Out…
              </>
            ) : (
              "Log Out and Continue"
            )}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  );
}
