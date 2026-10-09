// How strong a new operator password looks, scored like the chat clients' sign-up meter (web
// `evaluatePassword`, iOS `PasswordStrengthEvaluator`) so the two read the same. It only informs:
// what the setup page and the API require is 12 characters.

export type PasswordLevel = "" | "Weak" | "Fair" | "Good" | "Strong";

export type PasswordStrength = { level: PasswordLevel; score: number };

export function passwordStrength(password: string): PasswordStrength {
  if (!password) return { level: "", score: 0 };
  const length = [...password].length;
  const hasMinimumLength = length >= 12;
  const hasSymbolAndNumber = /\d/.test(password) && /[^a-zA-Z0-9\s]/.test(password);
  const hasMixedCase = /[A-Z]/.test(password) && /[a-z]/.test(password);

  let score = 0;
  if (length >= 8) score += 0.15;
  if (hasMinimumLength) score += 0.35;
  if (hasSymbolAndNumber) score += 0.35;
  if (hasMixedCase) score += 0.1;
  if (length >= 16) score += 0.05;

  let level: PasswordLevel = "Weak";
  if (hasMinimumLength && hasSymbolAndNumber) {
    level = hasMixedCase || length >= 14 ? "Strong" : "Good";
  } else if (length >= 8 || hasSymbolAndNumber) {
    level = "Fair";
  }
  return { level, score: Math.min(score, 1) };
}
