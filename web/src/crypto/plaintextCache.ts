const prefix = "shroud.pt.";
const previewPrefix = "shroud.preview.";

export type ChatPreview = {
  text: string;
  at: string;
  isMine: boolean;
  failed?: boolean;
};

function previewKey(me: string, peer: string): string {
  return `${previewPrefix}${me.toLowerCase()}.${peer.toLowerCase()}`;
}

export function loadPreview(me: string, peer: string): ChatPreview | null {
  try {
    const raw = localStorage.getItem(previewKey(me, peer));
    if (!raw) return null;
    const parsed = JSON.parse(raw) as ChatPreview;
    if (!parsed.text || !parsed.at) return null;
    return parsed;
  } catch {
    return null;
  }
}

export function savePreview(me: string, peer: string, preview: ChatPreview): void {
  const existing = loadPreview(me, peer);
  if (existing && existing.at > preview.at) return;
  try {
    localStorage.setItem(previewKey(me, peer), JSON.stringify(preview));
  } catch {
    /* quota */
  }
}

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
