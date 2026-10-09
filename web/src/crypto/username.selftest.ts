import {
  argon2UsernameB64,
  normalizeUsername,
  usernameHashB64,
  usernameKdfParams,
  USERNAME_ARGON2_ALICE,
  UsernameError,
} from "./username";

function check(cond: boolean, label: string) {
  if (!cond) throw new Error(label);
}

check(normalizeUsername(" Alice_1 ") === "alice_1", "folds and trims");
check(usernameHashB64("alice") === "K9gGyX8OAK8aH8Myj6djqSaXI8jbj6xPk69x2xhtbpA=", "sha256 matches the server");
const alice = await argon2UsernameB64(
  "alice",
  usernameKdfParams({
    algorithm: "argon2id",
    version: 19,
    salt: "ABEiM0RVZneImaq7zN3u/w==",
    memory_kib: 65536,
    iterations: 8,
    parallelism: 1,
    output_bytes: 32,
  }),
);
check(alice === USERNAME_ARGON2_ALICE, "argon2id matches the server");
let weak = false;
try {
  usernameKdfParams({
    algorithm: "argon2id",
    version: 19,
    salt: "ABEiM0RVZneImaq7zN3u/w==",
    memory_kib: 19456,
    iterations: 2,
    parallelism: 1,
    output_bytes: 32,
  });
} catch (err) {
  weak = err instanceof UsernameError;
}
check(weak, "a cheap username hash is refused");
let reserved = false;
try {
  normalizeUsername("shroud_bot");
} catch (err) {
  reserved = err instanceof UsernameError;
}
check(reserved, "a reserved prefix is refused before it is hashed");
console.log("username.selftest ok");
