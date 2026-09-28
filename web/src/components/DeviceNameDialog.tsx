import { useState, type FormEvent } from "react";
import { ApiError } from "../api/client";
import { DEVICE_NAME_MAX_BYTES, normalizeDeviceName } from "../crypto/deviceName";
import { Modal } from "./Modal";

/**
 * "Name this browser", shown once the app opens after a login or sign-up, and whenever this
 * browser has no name its account can read. Closing it without saving keeps `fallback`, so the
 * device list never shows a nameless browser because someone pressed Escape.
 */
export function DeviceNameDialog({
  initial,
  onSave,
  onSkip,
}: {
  initial: string;
  /** Seals and stores the name; rejects on a network or server error. */
  onSave: (name: string) => Promise<void>;
  onSkip: () => void;
}) {
  const [name, setName] = useState(initial);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const cleaned = normalizeDeviceName(name);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!cleaned || busy) return;
    setBusy(true);
    setError(null);
    try {
      await onSave(cleaned);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not save the name. Try again.");
      setBusy(false);
    }
  }

  return (
    <Modal title="Name this device" onClose={() => (busy ? undefined : onSkip())}>
      <p>
        So you can tell your devices apart in Settings → Devices. The name is encrypted — only
        your own devices can read it.
      </p>
      <form className="modal-form" onSubmit={submit}>
        <input
          className="field"
          aria-label="Device name"
          value={name}
          onChange={(event) => setName(event.target.value)}
          maxLength={DEVICE_NAME_MAX_BYTES}
          autoComplete="off"
          spellCheck={false}
          data-autofocus
        />
        {error ? <p className="err">{error}</p> : null}
        <button className="btn btn-primary" type="submit" disabled={!cleaned || busy}>
          {busy ? "Saving…" : "Save"}
        </button>
        <button className="btn btn-secondary" type="button" onClick={onSkip} disabled={busy}>
          Not now
        </button>
      </form>
    </Modal>
  );
}
