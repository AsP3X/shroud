const prefix = "shroud.pt.";

export function loadPlaintext(messageId: string): string | null {
  try {
    return localStorage.getItem(prefix + messageId.toLowerCase());
  } catch {
    return null;
  }
}

export function savePlaintext(messageId: string, text: string): void {
  try {
    localStorage.setItem(prefix + messageId.toLowerCase(), text);
  } catch {
    /* quota */
  }
}
