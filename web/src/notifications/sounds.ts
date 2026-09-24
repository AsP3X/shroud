import type { SoundId } from "./prefs";

/**
 * The notification sounds: tones made by `scripts/gen_notification_sounds.py`, the same files
 * the iPhone app plays (`public/sounds/<id>.wav`).
 */
export const SOUND_CHOICES: readonly { id: SoundId; label: string }[] = [
  { id: "note", label: "Note" },
  { id: "chime", label: "Chime" },
  { id: "glass", label: "Glass" },
  { id: "pop", label: "Pop" },
  { id: "pulse", label: "Pulse" },
  { id: "none", label: "None" },
];

export function soundLabel(id: SoundId): string {
  return SOUND_CHOICES.find((choice) => choice.id === id)?.label ?? "Note";
}

/** A burst of messages rings once. */
const QUIET_AFTER_PLAY_MS = 1200;
const VOLUME = 0.6;

const players = new Map<SoundId, HTMLAudioElement>();
let lastPlayedAt = 0;

/**
 * Plays a sound; `preview` (the settings picker) always plays and cuts off the previous one.
 * Browsers refuse audio before the page's first click or key press; that failure is silent.
 */
export function playSound(id: SoundId, { preview = false } = {}): void {
  if (id === "none" || typeof Audio === "undefined") return;
  const now = Date.now();
  if (!preview && now - lastPlayedAt < QUIET_AFTER_PLAY_MS) return;
  lastPlayedAt = now;
  if (preview) {
    for (const player of players.values()) player.pause();
  }
  let player = players.get(id);
  if (!player) {
    player = new Audio(`/sounds/${id}.wav`);
    player.preload = "auto";
    players.set(id, player);
  }
  player.volume = VOLUME;
  player.currentTime = 0;
  void player.play().catch(() => {
    /* autoplay policy before any interaction, or the file is gone */
  });
}
