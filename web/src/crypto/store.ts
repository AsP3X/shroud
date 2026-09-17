import { deserializeIdentity, serializeIdentity, type IdentityMaterial } from "./identity";

function key(userId: string): string {
  return `shroud.identity.${userId.toLowerCase()}`;
}

export function hasIdentity(userId: string): boolean {
  return loadIdentity(userId) !== null;
}

export function loadIdentity(userId: string): IdentityMaterial | null {
  try {
    const raw = localStorage.getItem(key(userId));
    if (!raw) return null;
    const material = deserializeIdentity(raw);
    if (material.userId.toLowerCase() !== userId.toLowerCase()) return null;
    return material;
  } catch {
    return null;
  }
}

export function saveIdentity(material: IdentityMaterial): void {
  localStorage.setItem(key(material.userId), serializeIdentity(material));
}

export function clearIdentity(userId: string): void {
  localStorage.removeItem(key(userId));
}
