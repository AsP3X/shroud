/**
 * Set the moment a device wipe starts. From then on this tab stops writing account data:
 * a message that arrives over the socket mid-wipe, a ratchet step or a media save would
 * otherwise land after its store was emptied. Nothing unseals it — the wipe ends in a reload.
 *
 * Its own module so the storage layers can check it without importing the wipe itself.
 */
let sealed = false;

export function sealStorage(): void {
  sealed = true;
}

export function storageSealed(): boolean {
  return sealed;
}
