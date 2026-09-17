import { useCallback, useEffect, useState } from "react";
import { Laptop, Smartphone, Monitor } from "lucide-react";
import { api, ApiError, type Device, type Session } from "../../api/client";
import { listTimestamp } from "../../format";
import { SettingsGroup, SettingsNote } from "./SettingsRow";

/** Five is the server-side cap; the list is the only place to get back under it. */
const DEVICE_LIMIT = 5;

function DeviceIcon({ name }: { name: string }) {
  const lower = name.toLowerCase();
  if (/iphone|android|phone|mobile/.test(lower)) return <Smartphone size={16} />;
  if (/mac|windows|linux|laptop/.test(lower)) return <Laptop size={16} />;
  return <Monitor size={16} />;
}

function isCurrent(device: Device, session: Session): boolean {
  return device.is_current || device.id === session.device.id;
}

export function DevicesView({
  session,
  onUnauthorized,
  onCount,
}: {
  session: Session;
  onUnauthorized: () => void;
  onCount?: (count: number) => void;
}) {
  const [devices, setDevices] = useState<Device[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [revoking, setRevoking] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<Device | null>(null);

  const fail = useCallback(
    (err: unknown, fallback: string) => {
      if (err instanceof ApiError && err.isAuthFailure) {
        onUnauthorized();
        return;
      }
      setError(err instanceof ApiError ? err.message : fallback);
    },
    [onUnauthorized],
  );

  const load = useCallback(async () => {
    try {
      const res = await api.devices(session.token);
      setDevices(res.devices);
      onCount?.(res.devices.length);
      setError(null);
    } catch (err) {
      fail(err, "Could not load devices.");
    }
  }, [session.token, fail, onCount]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!confirming) return;
    function onKeyDown(event: KeyboardEvent) {
      if (event.key !== "Escape") return;
      event.stopImmediatePropagation();
      setConfirming(null);
    }
    document.addEventListener("keydown", onKeyDown, true);
    return () => document.removeEventListener("keydown", onKeyDown, true);
  }, [confirming]);

  async function revoke(device: Device) {
    if (isCurrent(device, session)) return;
    setConfirming(null);
    setRevoking(device.id);
    setError(null);
    try {
      await api.revokeDevice(session.token, device.id);
      setDevices((prev) => {
        const next = (prev ?? []).filter((item) => item.id !== device.id);
        onCount?.(next.length);
        return next;
      });
      await load();
    } catch (err) {
      fail(err, "Could not revoke that device.");
    } finally {
      setRevoking(null);
    }
  }

  const list = devices ?? [];
  const others = list.filter((d) => !isCurrent(d, session));

  return (
    <>
      <SettingsGroup
        title={
          devices === null
            ? "Linked devices"
            : `Linked devices — ${list.length} of ${DEVICE_LIMIT}`
        }
      >
        {devices === null ? (
          <p className="set-placeholder">{error ?? "Loading devices…"}</p>
        ) : list.length === 0 ? (
          <p className="set-placeholder">{error ?? "No devices found."}</p>
        ) : (
          list.map((device) => {
            const name = device.name ?? "Unnamed device";
            const current = isCurrent(device, session);
            return (
              <div key={device.id} className="set-row">
                <span className="set-tile set-tile-muted" aria-hidden="true">
                  <DeviceIcon name={name} />
                </span>
                <span className="set-row-copy">
                  <strong>
                    {name}
                    {current ? <span className="set-chip">This browser</span> : null}
                  </strong>
                  <span>
                    {current
                      ? "Active now"
                      : device.last_seen_at
                        ? `Last seen ${listTimestamp(device.last_seen_at)}`
                        : `Linked ${listTimestamp(device.created_at)}`}
                  </span>
                </span>
                {!current ? (
                  <button
                    type="button"
                    className="mini-btn ghost danger"
                    disabled={revoking === device.id}
                    onClick={() => setConfirming(device)}
                  >
                    {revoking === device.id ? "Revoking…" : "Revoke"}
                  </button>
                ) : null}
              </div>
            );
          })
        )}
      </SettingsGroup>

      {error && list.length > 0 ? <p className="set-error">{error}</p> : null}

      <SettingsNote>
        Every device holds its own identity key. Revoking one ends its sessions immediately — that
        device has to sign in again with your password and 12-word phrase.
        {devices && list.length > 0 && others.length === 0
          ? " This browser is your only linked device."
          : ""}
      </SettingsNote>

      {confirming ? (
        <div className="modal-scrim" onMouseDown={() => setConfirming(null)}>
          <div
            className="modal"
            role="alertdialog"
            aria-modal="true"
            aria-labelledby="revoke-title"
            onMouseDown={(event) => event.stopPropagation()}
          >
            <header>
              <h2 id="revoke-title">Revoke {confirming.name ?? "this device"}?</h2>
            </header>
            <p>
              Its sessions end immediately and it loses access to new messages. Anything already
              decrypted and stored on that device stays there.
            </p>
            <div className="modal-actions">
              <button type="button" className="btn btn-secondary" onClick={() => setConfirming(null)}>
                Cancel
              </button>
              <button
                type="button"
                className="btn btn-destructive"
                onClick={() => void revoke(confirming)}
              >
                Revoke device
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </>
  );
}
