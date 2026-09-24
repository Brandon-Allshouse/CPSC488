// Mirrors the rules in AuthController.java / PasswordPolicy.java so users get instant feedback.
// The backend re-checks everything (plus the breached-password check); this is for
// convenience, not security.

export const MAX_EMAIL = 254;
// NIST SP 800-63B rev. 4: at least 15 characters when the password is the only factor,
// and allow at least 64. No composition rules.
export const MIN_PASSWORD = 15;
export const MAX_PASSWORD = 128;

const EMAIL = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;
const USERNAME = /^[A-Za-z0-9_]{3,30}$/;

/** Length in Unicode characters (code points) after NFKC normalization, matching the backend. */
export function passwordLength(password: string): number {
  return [...password.normalize('NFKC')].length;
}

export function validateEmail(email: string): string | null {
  const trimmed = email.trim();
  if (!trimmed) return 'Email is required.';
  if (trimmed.length > MAX_EMAIL || !EMAIL.test(trimmed)) return 'Please enter a valid email address.';
  return null;
}

export function validateUsername(username: string): string | null {
  if (!USERNAME.test(username.trim())) {
    return 'Username must be 3-30 characters: letters, numbers, or underscores.';
  }
  return null;
}

export function validateNewPassword(password: string): string | null {
  const length = passwordLength(password);
  if (length < MIN_PASSWORD) {
    return `Password must be at least ${MIN_PASSWORD} characters. A few random words works well.`;
  }
  if (length > MAX_PASSWORD) return `Password must be at most ${MAX_PASSWORD} characters.`;
  return null;
}
