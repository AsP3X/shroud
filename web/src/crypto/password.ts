export type PasswordLevel = "" | "Weak" | "Fair" | "Good" | "Strong";

export type PasswordEvaluation = {
  level: PasswordLevel;
  score: number;
  hasMinimumLength: boolean;
  hasSymbolAndNumber: boolean;
  meetsRequirements: boolean;
};

function isSymbol(ch: string): boolean {
  return !/[a-zA-Z0-9\s]/.test(ch);
}

/** Same rules as iOS `PasswordStrengthEvaluator` (12+ chars, number, and symbol). */
export function evaluatePassword(password: string): PasswordEvaluation {
  if (!password) {
    return {
      level: "",
      score: 0,
      hasMinimumLength: false,
      hasSymbolAndNumber: false,
      meetsRequirements: false,
    };
  }
  const hasMinimumLength = password.length >= 12;
  const hasNumber = /\d/.test(password);
  const hasSymbol = [...password].some(isSymbol);
  const hasSymbolAndNumber = hasNumber && hasSymbol;
  const hasMixedCase = /[A-Z]/.test(password) && /[a-z]/.test(password);
  const hasLongLength = password.length >= 16;

  let score = 0;
  if (password.length >= 8) score += 0.15;
  if (hasMinimumLength) score += 0.35;
  if (hasSymbolAndNumber) score += 0.35;
  if (hasMixedCase) score += 0.1;
  if (hasLongLength) score += 0.05;

  let level: PasswordLevel = "Weak";
  if (hasMinimumLength && hasSymbolAndNumber) {
    level = hasMixedCase || password.length >= 14 ? "Strong" : "Good";
  } else if (password.length >= 8 || hasSymbolAndNumber) {
    level = "Fair";
  }

  return {
    level,
    score: Math.min(score, 1),
    hasMinimumLength,
    hasSymbolAndNumber,
    meetsRequirements: hasMinimumLength && hasSymbolAndNumber,
  };
}
