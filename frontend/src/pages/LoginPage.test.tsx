// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { login, register } from '../api/auth';
import { ApiError } from '../api/client';
import LoginPage from './LoginPage';

vi.mock('../api/auth', () => ({ login: vi.fn(), register: vi.fn() }));

const testUser = { id: 1, email: 'test@sru.edu', username: 'testuser', createdAt: '2026-10-05T00:00:00Z' };
const onAuthenticated = vi.fn();
const onGuest = vi.fn();

function type(label: string, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

function submit(name: string) {
  fireEvent.click(screen.getByRole('button', { name }));
}

beforeEach(() => {
  vi.clearAllMocks();
  render(<LoginPage onAuthenticated={onAuthenticated} onGuest={onGuest} />);
});

afterEach(() => {
  cleanup();
});

describe('LoginPage', () => {
  it('starts on the login form', () => {
    expect(screen.getByText('Welcome back')).toBeTruthy();
    expect(screen.queryByLabelText('Username')).toBeNull();
  });

  it('logs in and passes the user up', async () => {
    vi.mocked(login).mockResolvedValue(testUser);
    type('Email', 'test@sru.edu');
    type('Password', 'simple test password');
    submit('Log in');

    await vi.waitFor(() => expect(onAuthenticated).toHaveBeenCalledWith(testUser));
    expect(login).toHaveBeenCalledWith('test@sru.edu', 'simple test password');
  });

  it('checks the email before sending anything', () => {
    type('Email', 'testuser');
    type('Password', 'simple test password');
    submit('Log in');

    expect(screen.getByRole('alert').textContent).toBe('Please enter a valid email address.');
    expect(login).not.toHaveBeenCalled();
  });

  it('does not apply the new password rules when logging in', async () => {
    // an account made under older rules should still be able to log in
    vi.mocked(login).mockResolvedValue(testUser);
    type('Email', 'test@sru.edu');
    type('Password', 'short');
    submit('Log in');

    await vi.waitFor(() => expect(login).toHaveBeenCalled());
  });

  it('shows the error message from the backend', async () => {
    vi.mocked(login).mockRejectedValue(new ApiError(401, 'Incorrect email or password.'));
    type('Email', 'test@sru.edu');
    type('Password', 'wrong password here');
    submit('Log in');

    expect((await screen.findByRole('alert')).textContent).toBe('Incorrect email or password.');
    expect(onAuthenticated).not.toHaveBeenCalled();
  });

  it('can switch to sign up and back', () => {
    fireEvent.click(screen.getByRole('button', { name: 'Sign up' }));
    expect(screen.getByText('Create your account')).toBeTruthy();
    expect(screen.getByLabelText('Username')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Log in' }));
    expect(screen.getByText('Welcome back')).toBeTruthy();
  });

  it('clears the password when switching forms', () => {
    type('Password', 'simple test password');
    fireEvent.click(screen.getByRole('button', { name: 'Sign up' }));
    expect((screen.getByLabelText('Password') as HTMLInputElement).value).toBe('');
  });

  it('signs up with all the fields', async () => {
    vi.mocked(register).mockResolvedValue(testUser);
    fireEvent.click(screen.getByRole('button', { name: 'Sign up' }));
    type('Email', 'test@sru.edu');
    type('Username', 'testuser');
    type('Password', 'simple test password');
    type('Confirm password', 'simple test password');
    submit('Sign up');

    await vi.waitFor(() => expect(onAuthenticated).toHaveBeenCalledWith(testUser));
    expect(register).toHaveBeenCalledWith('test@sru.edu', 'testuser', 'simple test password');
  });

  it('will not sign up with a short password', () => {
    fireEvent.click(screen.getByRole('button', { name: 'Sign up' }));
    type('Email', 'test@sru.edu');
    type('Username', 'testuser');
    type('Password', 'too short');
    type('Confirm password', 'too short');
    submit('Sign up');

    expect(screen.getByRole('alert').textContent).toContain('at least 15 characters');
    expect(register).not.toHaveBeenCalled();
  });

  it('will not sign up when the passwords are different', () => {
    fireEvent.click(screen.getByRole('button', { name: 'Sign up' }));
    type('Email', 'test@sru.edu');
    type('Username', 'testuser');
    type('Password', 'simple test password');
    type('Confirm password', 'simple test passwurd');
    submit('Sign up');

    expect(screen.getByRole('alert').textContent).toBe('Passwords do not match.');
    expect(register).not.toHaveBeenCalled();
  });

  it('show button reveals the password', () => {
    const password = screen.getByLabelText('Password') as HTMLInputElement;
    expect(password.type).toBe('password');
    fireEvent.click(screen.getByRole('button', { name: 'Show' }));
    expect(password.type).toBe('text');
  });

  it('continue as guest calls onGuest', () => {
    fireEvent.click(screen.getByRole('button', { name: 'Continue as guest' }));
    expect(onGuest).toHaveBeenCalled();
  });
});
