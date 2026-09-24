import { useState, type FormEvent } from 'react';
import { login, register, type User } from '../api/auth';
import {
  MAX_EMAIL,
  MIN_PASSWORD,
  passwordLength,
  validateEmail,
  validateNewPassword,
  validateUsername,
} from '../validation';

// One page with two modes: logging in and signing up. They share the email and password fields.
type Mode = 'login' | 'register';

interface Props {
  onAuthenticated: (user: User) => void;
  onGuest: () => void;
}

export default function LoginPage({ onAuthenticated, onGuest }: Props) {
  const [mode, setMode] = useState<Mode>('login');
  const [email, setEmail] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const isRegister = mode === 'register';

  // Clear passwords when switching so they don't linger in state or appear in the other form.
  function switchMode(next: Mode) {
    setMode(next);
    setError(null);
    setPassword('');
    setConfirmPassword('');
    setShowPassword(false);
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);

    // Quick checks for instant feedback; the backend validates everything again. Login only checks
    // the email format and that a password was typed. It must never apply the new-password rules,
    // or accounts created under older rules would be locked out if the rules change.
    const problem = isRegister
      ? validateEmail(email) ??
        validateUsername(username) ??
        validateNewPassword(password) ??
        (password !== confirmPassword ? 'Passwords do not match.' : null)
      : validateEmail(email) ?? (password ? null : 'Password is required.');
    if (problem) {
      setError(problem);
      return;
    }

    setSubmitting(true);
    try {
      const user = isRegister
        ? await register(email, username, password)
        : await login(email, password);
      onAuthenticated(user);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong.');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="auth-page">
      <section className="auth-card" aria-labelledby="auth-title">
        <header className="auth-header">
          <p className="brand">BrainFeed</p>
          <h1 id="auth-title">{isRegister ? 'Create your account' : 'Welcome back'}</h1>
          <p className="muted">
            {isRegister
              ? 'Save your interests and get a feed that actually teaches you something.'
              : 'Log in to pick up your feed where you left off.'}
          </p>
        </header>

        {/* noValidate: we show our own error messages instead of the browser's pop-ups. */}
        <form onSubmit={handleSubmit} noValidate>
          <label className="field">
            <span>Email</span>
            <input
              type="email"
              autoComplete="email"
              maxLength={MAX_EMAIL}
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
              autoFocus
            />
          </label>

          {isRegister && (
            <label className="field">
              <span>Username</span>
              <input
                type="text"
                autoComplete="username"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                minLength={3}
                maxLength={30}
                required
              />
            </label>
          )}

          <div className="field">
            <label htmlFor="password">Password</label>
            <div className="password-row">
              <input
                id="password"
                type={showPassword ? 'text' : 'password'}
                autoComplete={isRegister ? 'new-password' : 'current-password'}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                aria-describedby={isRegister ? 'password-hint' : undefined}
                required
              />
              <button
                type="button"
                className="link-button"
                onClick={() => setShowPassword((s) => !s)}
                aria-pressed={showPassword}
                aria-controls="password"
              >
                {showPassword ? 'Hide' : 'Show'}
              </button>
            </div>
            {isRegister && (
              <small id="password-hint" className="muted">
                At least {MIN_PASSWORD} characters ({passwordLength(password)} so far). Spaces and special characters are accepted.
              </small>
            )}
          </div>

          {isRegister && (
            <label className="field">
              <span>Confirm password</span>
              <input
                type={showPassword ? 'text' : 'password'}
                autoComplete="new-password"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                required
              />
            </label>
          )}

          {error && (
            <p className="form-error" role="alert">
              {error}
            </p>
          )}

          <button type="submit" className="btn btn-primary" disabled={submitting}>
            {submitting ? 'Please wait…' : isRegister ? 'Sign up' : 'Log in'}
          </button>
        </form>

        <div className="divider">
          <span>or</span>
        </div>

        <button type="button" className="btn btn-secondary" onClick={onGuest}>
          Continue as guest
        </button>

        <p className="switch-mode">
          {isRegister ? 'Already have an account?' : "Don't have an account?"}{' '}
          <button
            type="button"
            className="link-button"
            onClick={() => switchMode(isRegister ? 'login' : 'register')}
          >
            {isRegister ? 'Log in' : 'Sign up'}
          </button>
        </p>
      </section>
    </main>
  );
}
