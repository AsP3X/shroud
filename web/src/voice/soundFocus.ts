/**
 * One sound at a time (docs/file-sharing.md §11.5): a voice note starting stops an audio file,
 * and the other way round. Each player registers how it stops; starting one stops the others.
 * Kept apart from both players so neither imports the other.
 */
export type SoundOwner = "voice" | "file";

const stoppers = new Map<SoundOwner, () => void>();

export function registerSound(owner: SoundOwner, stop: () => void): void {
  stoppers.set(owner, stop);
}

/** `owner` is about to make a sound: every other player stops. */
export function claimSound(owner: SoundOwner): void {
  for (const [other, stop] of stoppers) if (other !== owner) stop();
}
