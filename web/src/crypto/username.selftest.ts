import { normalizeUsername, usernameHashB64, UsernameError } from "./username";

function check(cond: boolean, label: string) {
  if (!cond) throw new Error(label);
}

check(normalizeUsername(" Alice_1 ") === "alice_1", "folds and trims");
check(usernameHashB64("alice") === "K9gGyX8OAK8aH8Myj6djqSaXI8jbj6xPk69x2xhtbpA=", "sha256 matches the server");
let reserved = false;
try {
  normalizeUsername("shroud_bot");
} catch (err) {
  reserved = err instanceof UsernameError;
}
check(reserved, "a reserved prefix is refused before it is hashed");
console.log("username.selftest ok");
