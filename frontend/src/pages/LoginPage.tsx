import { useState, type FormEvent } from 'react';
import { login, register, type User } from '../api/auth';
import { CircleAlert } from 'lucide-react';
import AuthLayout from '@/components/layout/AuthLayout';
import ProductPitch from '@/components/ProductPitch';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
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
    <AuthLayout
      name="login"
      aside={<ProductPitch />}
      title={isRegister ? 'Create your account' : 'Welcome back'}
      description={
        isRegister
          ? 'Save your interests and get a feed that actually teaches you something.'
          : 'Log in to pick up your feed where you left off.'
      }
    >
      {/* noValidate: we show our own error messages instead of the browser's pop-ups. */}
      <form id="auth-form" className="flex flex-col gap-4" onSubmit={handleSubmit} noValidate>
        <div className="flex flex-col gap-2">
          <Label htmlFor="email">Email</Label>
          <Input
            id="email"
            type="email"
            autoComplete="email"
            placeholder="you@sru.edu"
            maxLength={MAX_EMAIL}
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
            autoFocus
          />
        </div>

        {isRegister && (
          <div className="flex flex-col gap-2">
            <Label htmlFor="username">Username</Label>
            <Input
              id="username"
              type="text"
              autoComplete="username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              minLength={3}
              maxLength={30}
              required
            />
          </div>
        )}

        <div className="flex flex-col gap-2">
          <Label htmlFor="password">Password</Label>
          <div className="relative">
            <Input
              id="password"
              type={showPassword ? 'text' : 'password'}
              autoComplete={isRegister ? 'new-password' : 'current-password'}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              aria-describedby={isRegister ? 'password-hint' : undefined}
              className="pr-16"
              required
            />
            <button
              type="button"
              className="password-toggle absolute inset-y-1 right-1 cursor-pointer rounded px-2.5 text-xs font-semibold tracking-wider text-muted-foreground transition-colors duration-150 hover:bg-accent hover:text-foreground"
              onClick={() => setShowPassword((s) => !s)}
              aria-pressed={showPassword}
              aria-controls="password"
            >
              {showPassword ? 'HIDE' : 'SHOW'}
            </button>
          </div>
          {isRegister && (
            <small id="password-hint" className="text-xs text-muted-foreground">
              At least {MIN_PASSWORD} characters ({passwordLength(password)} so far). Spaces and special characters are accepted.
            </small>
          )}
        </div>

        {isRegister && (
          <div className="flex flex-col gap-2">
            <Label htmlFor="confirm-password">Confirm password</Label>
            <Input
              id="confirm-password"
              type={showPassword ? 'text' : 'password'}
              autoComplete="new-password"
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
              required
            />
          </div>
        )}

        {error && (
          <Alert variant="destructive" role="alert">
            <CircleAlert aria-hidden="true" />
            {error}
          </Alert>
        )}

        <Button type="submit" size="lg" disabled={submitting}>
          {submitting ? 'Please wait…' : isRegister ? 'Sign up' : 'Log in'}
        </Button>
      </form>

      <div className="flex items-center gap-3 text-xs text-muted-foreground uppercase before:h-px before:flex-1 before:bg-border after:h-px after:flex-1 after:bg-border">
        or
      </div>

      <Button variant="outline" size="lg" onClick={onGuest}>
        Continue as guest
      </Button>

      <p className="text-center text-sm text-muted-foreground">
        {isRegister ? 'Already have an account?' : "Don't have an account?"}{' '}
        <Button variant="link" onClick={() => switchMode(isRegister ? 'login' : 'register')}>
          {isRegister ? 'Log in' : 'Sign up'}
        </Button>
      </p>
    </AuthLayout>
  );
}
