import { ApiError } from "../../api/client";

/** The line above the password in both removal confirmations (security audit L4). */
export const REMOVE_DEVICE_PROMPT = "Enter your password to confirm.";

/**
 * What a confirmation does with an answer from `DELETE /devices/{id}`. `null` is the 204. A 404
 * means the device is already gone (removed elsewhere meanwhile), so the goal is met. A wrong
 * password and the rate limit stay in the dialog with Delete Account's copy; every other 401
 * ends the session.
 */
export type RemoveDeviceOutcome =
  | "removed"
  | "already-removed"
  | "wrong-password"
  | "rate-limited"
  | "signed-out"
  | "failed";

export function mapRemoveDeviceAnswer(error: unknown | null): RemoveDeviceOutcome {
  if (error == null) return "removed";
  if (!(error instanceof ApiError)) return "failed";
  if (error.status === 404) return "already-removed";
  if (error.status === 401 && error.code === "INVALID_CREDENTIALS") return "wrong-password";
  if (error.status === 401) return "signed-out";
  if (error.status === 429) return "rate-limited";
  return "failed";
}
