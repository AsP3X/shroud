import { ApiError } from "../../api/client";

/**
 * Copy for Delete Account, word for word from docs/account-deletion-plan.md §3.3.
 * C2 is the row subtitle (§3.4). The curly quotes and the ellipsis are the characters
 * in that file, not straight quotes or three dots.
 */
export const DELETE_ACCOUNT_COPY = {
  rowTitle: "Delete Account",
  rowSubtitle: "Deletes your account and erases it from every device.",
  title: "Delete your account?",
  consequences: [
    "Your messages are replaced with \u201cMessage deleted\u201d for everyone.",
    "Contacts who let you clear chats for them lose those chats. Everyone else keeps their own messages.",
    "Your contacts, Saved Messages, photos, files and call history are deleted.",
    "Your username and share code are released, so someone else can take them.",
    "Every device signed in to this account is signed out and erased.",
  ],
  confirm: "This can't be undone. Enter your password to confirm.",
  passwordLabel: "Password",
  passwordPlaceholder: "Your account password",
  cancel: "Cancel",
  submit: "Delete Account",
  submitting: "Deleting\u2026",
  wrongPassword: "That password isn't right.",
  rateLimited: "Too many tries. Try again later.",
  unreachable: "Couldn't reach the server. Your account wasn't deleted.",
} as const;

/**
 * What the screen does with an answer from `DELETE /auth/account` (§3.2).
 * `null` is the 204. A wrong password stays on the screen. Every `DEVICE_REMOVED`
 * on this call wipes as the account deleted, whatever its reason.
 */
export type DeleteAccountOutcome =
  | "deleted"
  | "wiped"
  | "wrong-password"
  | "rate-limited"
  | "unreachable"
  | "signed-out";

export function mapDeleteAccountAnswer(error: unknown | null): DeleteAccountOutcome {
  if (error == null) return "deleted";
  if (!(error instanceof ApiError)) return "unreachable";
  if (error.status === 401 && error.code === "INVALID_CREDENTIALS") return "wrong-password";
  if (error.status === 401 && error.code === "DEVICE_REMOVED") return "wiped";
  if (error.status === 401) return "signed-out";
  if (error.status === 429) return "rate-limited";
  if (error.status === 0 || error.status === 408 || error.code === "timeout" || error.status >= 500) {
    return "unreachable";
  }
  return "unreachable";
}
