import { api } from "./api/client";
import type { Contact } from "./api/client";
import { bytesToB64, utf8, utf8decode } from "./crypto/bytes";
import type { IdentityMaterial } from "./crypto/identity";
import { peerIdentityForSending } from "./crypto/peerIdentity";
import { openBox, sealBox, type SealedBox } from "./crypto/sealedBox";
import { usernameHashB64 } from "./crypto/username";

/** Shown until a mutual contact's seal opens on this device. */
export const CONTACT_PLACEHOLDER = "Contact";

const NAME_RE = /^[a-z0-9_]{3,32}$/;

function bookKey(ownerId: string): string {
  return `shroud.contact-names.${ownerId.toLowerCase()}`;
}

function publishedKey(ownerId: string): string {
  return `shroud.contact-names.published.${ownerId.toLowerCase()}`;
}

function readMap(key: string): Record<string, string> {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return {};
    const parsed = JSON.parse(raw) as Record<string, string>;
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch {
    return {};
  }
}

function writeMap(key: string, map: Record<string, string>) {
  try {
    if (Object.keys(map).length === 0) localStorage.removeItem(key);
    else localStorage.setItem(key, JSON.stringify(map));
  } catch {
    /* a full disk keeps the names already on screen */
  }
}

/** A name this device already opened, or null. Never a value the server sent. */
export function contactName(ownerId: string, peerId: string): string | null {
  const name = readMap(bookKey(ownerId))[peerId.toLowerCase()];
  return name && NAME_RE.test(name) ? name : null;
}

export function displayContactName(ownerId: string, peerId: string): string {
  return contactName(ownerId, peerId) ?? CONTACT_PLACEHOLDER;
}

export function rememberContactName(ownerId: string, peerId: string, name: string) {
  if (!NAME_RE.test(name)) return;
  const key = bookKey(ownerId);
  const map = readMap(key);
  map[peerId.toLowerCase()] = name;
  writeMap(key, map);
}

export function forgetContactName(ownerId: string, peerId: string) {
  const id = peerId.toLowerCase();
  for (const key of [bookKey(ownerId), publishedKey(ownerId)]) {
    const map = readMap(key);
    if (!(id in map)) continue;
    delete map[id];
    writeMap(key, map);
  }
}

/** Drops names of people who are no longer contacts. */
export function retainContactNames(ownerId: string, peerIds: string[]) {
  const keep = new Set(peerIds.map((id) => id.toLowerCase()));
  for (const key of [bookKey(ownerId), publishedKey(ownerId)]) {
    const map = readMap(key);
    let changed = false;
    for (const id of Object.keys(map)) {
      if (keep.has(id)) continue;
      delete map[id];
      changed = true;
    }
    if (changed) writeMap(key, map);
  }
}

function parseBox(sealed: string): SealedBox | null {
  try {
    const box = JSON.parse(sealed) as SealedBox;
    if (!box || typeof box.ek !== "string" || typeof box.ct !== "string") return null;
    return box;
  } catch {
    return null;
  }
}

/**
 * Opens the seal a contact made for this account. A tag that does not prove them, or a
 * plaintext that is not a username, is ignored.
 */
export async function openContactName(
  sealed: string,
  ourPrivate: Uint8Array,
  theirPublic: Uint8Array,
  ourPublic: Uint8Array,
): Promise<string | null> {
  const box = parseBox(sealed);
  if (!box?.t) return null;
  try {
    const opened = await openBox(box, ourPrivate, theirPublic, ourPublic);
    if (!opened.authenticated) return null;
    const name = utf8decode(opened.plaintext);
    return NAME_RE.test(name) ? name : null;
  } catch {
    return null;
  }
}

/**
 * Opens every contact's seal and publishes this account's name, sealed to each of them.
 * People who have not added each other never receive it. The server stores the box and
 * cannot read it.
 */
export async function syncContactNames(
  token: string,
  ownerId: string,
  username: string,
  contacts: Contact[],
  material: IdentityMaterial | null,
): Promise<Contact[]> {
  retainContactNames(ownerId, contacts.map((contact) => contact.user_id));
  if (material && NAME_RE.test(username)) {
    const published = readMap(publishedKey(ownerId));
    let publishedChanged = false;
    for (const contact of contacts) {
      const id = contact.user_id.toLowerCase();
      try {
        const theirPublic = await peerIdentityForSending(token, contact.user_id);
        if (contact.sealed_name) {
          const opened = await openContactName(
            contact.sealed_name,
            material.agreementPrivate,
            theirPublic,
            material.agreementPublic,
          );
          if (opened) rememberContactName(ownerId, id, opened);
        }
        const fingerprint = usernameHashB64(username + "." + bytesToB64(theirPublic));
        if (published[id] === fingerprint) continue;
        const box = await sealBox(utf8(username), material.agreementPrivate, material.agreementPublic, theirPublic);
        await api.putContactName(token, contact.user_id, JSON.stringify(box));
        published[id] = fingerprint;
        publishedChanged = true;
      } catch {
        /* no key yet, or a key change waiting to be trusted: try again later */
      }
    }
    if (publishedChanged) writeMap(publishedKey(ownerId), published);
  }
  return contacts.map((contact) => ({
    ...contact,
    username: displayContactName(ownerId, contact.user_id),
  }));
}
