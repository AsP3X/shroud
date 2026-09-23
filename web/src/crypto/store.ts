import { storageSealed } from "../storageSeal";
import { deserializeIdentity, serializeIdentity, type IdentityMaterial } from "./identity";
import { isSealedValue, isVaultOpen, vaultGet, vaultSet } from "./vault";

/*
 * The identity keys live sealed in the vault (see vault.ts). Between the phrase step and the
 * first PIN there is no vault yet, so a freshly derived identity waits here in memory; a
 * reload in that window goes back to the phrase step.
 */
let pending: IdentityMaterial | null = null;

function key(userId: string): string {
  return `shroud.identity.${userId.toLowerCase()}`;
}

/** Whether this browser holds the account's identity, sealed or pending — readable or not. */
export function hasIdentity(userId: string): boolean {
  if (pending && pending.userId.toLowerCase() === userId.toLowerCase()) return true;
  try {
    return localStorage.getItem(key(userId)) !== null;
  } catch {
    return false;
  }
}

/** A pre-vault identity stored in the clear, which the first unlock seals. */
export function hasPlaintextIdentity(userId: string): boolean {
  try {
    const raw = localStorage.getItem(key(userId));
    return raw !== null && !isSealedValue(raw);
  } catch {
    return false;
  }
}

export function loadIdentity(userId: string): IdentityMaterial | null {
  if (pending && pending.userId.toLowerCase() === userId.toLowerCase()) return pending;
  const raw = vaultGet(key(userId));
  if (!raw) return null;
  try {
    const material = deserializeIdentity(raw);
    if (material.userId.toLowerCase() !== userId.toLowerCase()) return null;
    return material;
  } catch {
    return null;
  }
}

/** Reads a pre-vault plaintext identity (migration only). */
export function loadPlaintextIdentity(userId: string): IdentityMaterial | null {
  try {
    const raw = localStorage.getItem(key(userId));
    if (!raw || isSealedValue(raw)) return null;
    const material = deserializeIdentity(raw);
    return material.userId.toLowerCase() === userId.toLowerCase() ? material : null;
  } catch {
    return null;
  }
}

/**
 * Seals the identity into the open vault, or holds it in memory until the first PIN creates
 * one (`flushPendingIdentity`). Never writes it in the clear.
 */
export function saveIdentity(material: IdentityMaterial): void {
  if (storageSealed()) return;
  if (isVaultOpen(material.userId) && vaultSet(key(material.userId), serializeIdentity(material))) {
    if (pending?.userId.toLowerCase() === material.userId.toLowerCase()) pending = null;
    return;
  }
  pending = material;
}

/** The identity waiting for a vault, if any. */
export function pendingIdentity(userId: string): IdentityMaterial | null {
  return pending && pending.userId.toLowerCase() === userId.toLowerCase() ? pending : null;
}

/** Seals the pending identity once the vault is open. */
export function flushPendingIdentity(userId: string): void {
  const material = pendingIdentity(userId);
  if (material) saveIdentity(material);
}

export function clearIdentity(userId: string): void {
  if (pending?.userId.toLowerCase() === userId.toLowerCase()) pending = null;
  localStorage.removeItem(key(userId));
}
