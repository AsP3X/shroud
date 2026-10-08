import { ApiError, api } from "./api/client";
import type { Contact } from "./api/client";
import { bytesToB64, utf8, utf8decode } from "./crypto/bytes";
import { openContactBook, sealContactBook } from "./crypto/contactBook";
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

/**
 * Shown until a name opens: "Contact · 7f3c", the start of the account ID, so chats with
 * different people can be told apart. The ID is already on this browser; no name is guessed.
 */
export function placeholderName(peerId: string): string {
  return `${CONTACT_PLACEHOLDER} · ${peerId.replace(/-/g, "").slice(0, 4).toLowerCase()}`;
}

export function displayContactName(ownerId: string, peerId: string): string {
  return contactName(ownerId, peerId) ?? placeholderName(peerId);
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
  if (!box) return null;
  try {
    const name = utf8decode(await openBox(box, ourPrivate, theirPublic, ourPublic));
    return NAME_RE.test(name) ? name : null;
  } catch {
    return null;
  }
}

/**
 * Merges the book the account's other devices share with this browser's names, and writes the
 * union back when this browser knows names the book lacks. A write another device beat is read
 * again and merged once more, so no device's names are lost.
 */
async function syncNameBook(
  token: string,
  ownerId: string,
  historyKey: Uint8Array,
  contactIds: Set<string>,
): Promise<void> {
  for (let attempt = 0; attempt < 3; attempt++) {
    const remote = await api.getContactNames(token);
    const remoteNames = openContactBook(historyKey, ownerId, remote.sealed) ?? {};
    for (const [id, name] of Object.entries(remoteNames)) {
      if (contactIds.has(id) && !contactName(ownerId, id)) rememberContactName(ownerId, id, name);
    }
    const merged: Record<string, string> = {};
    for (const [id, name] of Object.entries({ ...remoteNames, ...readMap(bookKey(ownerId)) })) {
      if (contactIds.has(id)) merged[id] = name;
    }
    const same =
      Object.keys(merged).length === Object.keys(remoteNames).length &&
      Object.entries(merged).every(([id, name]) => remoteNames[id] === name);
    if (same) return;
    try {
      await api.putContactNames(token, sealContactBook(historyKey, ownerId, merged), remote.version);
      return;
    } catch (err) {
      if (!(err instanceof ApiError) || err.code !== "VERSION_CONFLICT") throw err;
    }
  }
}

/**
 * Opens every contact's seal, merges the account's shared name book, and publishes this
 * account's name, sealed to each contact. People who have not added each other never receive
 * it. The server stores the boxes and the book and cannot read either.
 */
export async function syncContactNames(
  token: string,
  ownerId: string,
  username: string,
  contacts: Contact[],
  material: IdentityMaterial | null,
): Promise<Contact[]> {
  retainContactNames(ownerId, contacts.map((contact) => contact.user_id));
  if (material) {
    const canPublish = NAME_RE.test(username);
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
        if (!canPublish) continue;
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
    try {
      await syncNameBook(
        token,
        ownerId,
        material.historyKey,
        new Set(contacts.map((contact) => contact.user_id.toLowerCase())),
      );
    } catch {
      /* offline, or the server is older: the names this browser opened still show */
    }
  }
  return contacts.map((contact) => ({
    ...contact,
    username: displayContactName(ownerId, contact.user_id),
  }));
}
