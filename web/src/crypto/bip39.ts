import { sha256 } from "@noble/hashes/sha2.js";
import { sha512 } from "@noble/hashes/sha2.js";
import { pbkdf2 } from "@noble/hashes/pbkdf2.js";
import { BIP39_ENGLISH } from "./bip39-wordlist";
import { randomBytes } from "./bytes";

export const WORD_COUNT = 12;

export type Bip39Error = "invalid_word_count" | "unknown_word" | "invalid_checksum";

export class PhraseError extends Error {
  readonly code: Bip39Error;
  constructor(code: Bip39Error, message: string) {
    super(message);
    this.code = code;
  }
}

function bitsFromBytes(data: Uint8Array): boolean[] {
  const bits: boolean[] = [];
  for (const byte of data) {
    for (let shift = 7; shift >= 0; shift--) bits.push(((byte >> shift) & 1) === 1);
  }
  return bits;
}

function checksumBits(entropy: Uint8Array, count: number): boolean[] {
  const hash = sha256(entropy);
  return Array.from({ length: count }, (_, index) => ((hash[0] >> (7 - index)) & 1) === 1);
}

export function generateMnemonic(): string[] {
  const entropy = randomBytes(16);
  const entropyBits = bitsFromBytes(entropy);
  const combined = entropyBits.concat(checksumBits(entropy, 4));
  const words: string[] = [];
  for (let i = 0; i < WORD_COUNT; i++) {
    let value = 0;
    const start = i * 11;
    for (let bit = 0; bit < 11; bit++) {
      if (combined[start + bit]) value |= 1 << (10 - bit);
    }
    words.push(BIP39_ENGLISH[value]);
  }
  return words;
}

export function validateMnemonic(words: string[]): string[] {
  const normalized = words
    .map((w) => w.trim().toLowerCase())
    .filter((w) => w.length > 0);
  if (normalized.length !== WORD_COUNT) {
    throw new PhraseError("invalid_word_count", "Enter all 12 words of your encryption phrase.");
  }
  const indices: number[] = [];
  for (const word of normalized) {
    const index = BIP39_ENGLISH.indexOf(word);
    if (index < 0) {
      throw new PhraseError("unknown_word", `Unknown word: ${word}`);
    }
    indices.push(index);
  }
  const bits: boolean[] = [];
  for (const index of indices) {
    for (let shift = 10; shift >= 0; shift--) bits.push(((index >> shift) & 1) === 1);
  }
  const entropy = new Uint8Array(16);
  for (let byteIndex = 0; byteIndex < 16; byteIndex++) {
    let value = 0;
    for (let bit = 0; bit < 8; bit++) {
      if (bits[byteIndex * 8 + bit]) value |= 1 << (7 - bit);
    }
    entropy[byteIndex] = value;
  }
  const got = bits.slice(128, 132);
  const expected = checksumBits(entropy, 4);
  if (got.some((b, i) => b !== expected[i])) {
    throw new PhraseError("invalid_checksum", "That encryption phrase checksum is invalid.");
  }
  return normalized;
}

export function mnemonicToSeed(words: string[], passphrase = ""): Uint8Array {
  const validated = validateMnemonic(words);
  const mnemonic = validated.join(" ").normalize("NFKD");
  const salt = (`mnemonic${passphrase}`).normalize("NFKD");
  return pbkdf2(sha512, mnemonic, salt, { c: 2048, dkLen: 64 });
}
