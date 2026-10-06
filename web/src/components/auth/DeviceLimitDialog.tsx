import { useEffect, useRef } from "react";
import { createPortal } from "react-dom";
import { Check } from "lucide-react";
import { deviceDisplayName, type DeviceLabel } from "../../crypto/deviceName";
import { DEVICE_LIMIT, type LimitDevice } from "../../deviceLimit";
import { listTimestamp, longDate } from "../../format";
import { Modal } from "../Modal";
import { DeviceTile } from "../settings/DevicesView";

function lastActive(device: LimitDevice): string {
  return device.last_seen_at ? `Last active ${listTimestamp(device.last_seen_at)}` : "Never active";
}

/**
 * "Log out your oldest device?" — a login that found every device slot signed in, asked once
 * the password and the phrase checked out. Same copy as the iOS and Android apps. The phrase
 * opened the devices' names, so the card shows the selected one the way Settings › Devices
 * does; "Choose Another Device" swaps this dialog for the picker below.
 *
 * Cancel has focus, so a stray Enter never logs a device out. Escape and the scrim cancel, and
 * Tab stays inside, except while the retry runs: then nothing closes it.
 */
export function DeviceLimitDialog({
  device,
  label,
  oldest,
  canChoose,
  busy,
  onChoose,
  onCancel,
  onConfirm,
}: {
  device: LimitDevice;
  label: DeviceLabel | null;
  /** The selected device is the least recently active one. */
  oldest: boolean;
  /** More than one device to choose from. */
  canChoose: boolean;
  busy: boolean;
  onChoose: () => void;
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
          <h2 id="dev-limit-title">{oldest ? "Log out your oldest device?" : "Log out this device?"}</h2>
        </header>
        <p id="dev-limit-body">
          Your account is logged in on {DEVICE_LIMIT} devices, the most it can have. To log in
          here, Shroud logs out:
        </p>
        <div className="dev-limit-pick">
          <div className="set-card">
            <div className="set-row">
              <DeviceTile label={label} />
              <span className="set-row-copy">
                <strong>{deviceDisplayName(label)}</strong>
                {/* Breaks at the dot on a narrow screen, never inside a date. */}
                <span>
                  <span>{lastActive(device)} ·</span> <span>Linked {longDate(device.created_at)}</span>
                </span>
              </span>
            </div>
          </div>
          {canChoose ? (
            <button type="button" className="dev-link dev-limit-choose" onClick={onChoose} disabled={busy}>
              Choose Another Device
            </button>
          ) : null}
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

/**
 * "Choose a device to log out": every device of the account, least recently active first, in
 * the shared modal. A row selects its device and goes back to the question; Cancel, Escape or
 * the scrim go back with the selection unchanged.
 */
export function DevicePickerDialog({
  devices,
  labels,
  selectedId,
  onSelect,
  onCancel,
}: {
  devices: LimitDevice[];
  labels: Record<string, DeviceLabel | null>;
  selectedId: string;
  onSelect: (id: string) => void;
  onCancel: () => void;
}) {
  return createPortal(
    <Modal title="Choose a device to log out" onClose={onCancel} className="dev-limit dev-picker">
      <p>Least recently used first.</p>
      <div className="set-card">
        {devices.map((device, index) => {
          const label = labels[device.id] ?? null;
          const name = deviceDisplayName(label);
          const selected = device.id === selectedId;
          return (
            <button
              key={device.id}
              type="button"
              className="set-row set-row-button"
              aria-pressed={selected}
              aria-label={`${name}${index === 0 ? ", oldest" : ""}, ${lastActive(device)}`}
              data-autofocus={selected ? "" : undefined}
              onClick={() => onSelect(device.id)}
            >
              <DeviceTile label={label} />
              <span className="set-row-copy">
                <strong>
                  <span className="dev-picker-name">{name}</span>
                  {index === 0 ? <em className="dev-picker-tag">Oldest</em> : null}
                </strong>
                <span>{lastActive(device)}</span>
              </span>
              {selected ? <Check size={18} className="dev-picker-check" aria-hidden="true" /> : null}
            </button>
          );
        })}
      </div>
      <div className="modal-actions">
        <button type="button" className="btn btn-secondary" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </Modal>,
    document.body,
  );
}
