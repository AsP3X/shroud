/**
 * Whether calls from this browser always go through the TURN relay (Settings → Privacy).
 *
 * Off by default, like iOS `SecurityPreferences.alwaysRelayCalls`. On, the peer connection only
 * ever gathers relay candidates, so the other person sees the relay's address instead of this
 * network's. Costs the server relay bandwidth and a little latency. Per browser, in
 * localStorage: a preference, not a secret.
 */

const KEY = "shroud.alwaysRelayCalls";

export function alwaysRelaysCalls(): boolean {
  try {
    return localStorage.getItem(KEY) === "on";
  } catch {
    return false;
  }
}

export function setAlwaysRelaysCalls(enabled: boolean): void {
  try {
    localStorage.setItem(KEY, enabled ? "on" : "off");
  } catch {
    // Storage blocked (private mode): the default stays in effect.
  }
}

/** Shown when the switch is on but the server hands out no relay to go through. */
export const RELAY_UNAVAILABLE =
  "“Always relay calls” is on, but this server has no relay. Turn it off in Privacy to call directly.";
