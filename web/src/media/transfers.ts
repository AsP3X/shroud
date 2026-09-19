import { useSyncExternalStore } from "react";

/**
 * Live upload / download progress per message id. Kept outside React state so a
 * progress tick re-renders only the bubble that shows it, not the whole thread.
 */
export type Transfer = {
  direction: "up" | "down";
  loaded: number;
  /** Null while the size is unknown (the ring spins instead of filling). */
  total: number | null;
};

const transfers = new Map<string, Transfer>();
const listeners = new Set<() => void>();

function key(id: string): string {
  return id.toLowerCase();
}

function emit(): void {
  for (const listener of listeners) listener();
}

export function setTransfer(id: string, transfer: Transfer | null): void {
  if (transfer) transfers.set(key(id), transfer);
  else if (!transfers.delete(key(id))) return;
  emit();
}

export function getTransfer(id: string): Transfer | null {
  return transfers.get(key(id)) ?? null;
}

export function subscribeTransfers(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function useTransfer(id: string): Transfer | null {
  return useSyncExternalStore(
    subscribeTransfers,
    () => getTransfer(id),
    () => null,
  );
}

/** 0…1, or null when the total is unknown. */
export function transferFraction(transfer: Transfer | null): number | null {
  if (!transfer?.total) return null;
  return Math.min(1, Math.max(0, transfer.loaded / transfer.total));
}
