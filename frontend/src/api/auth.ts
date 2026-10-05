// Typed wrappers around the backend's /api/auth endpoints (see the README's "Auth API" table).

import { ApiError, request } from './client';

// Matches User.java on the backend.
export interface User {
  id: number;
  email: string;
  username: string;
  createdAt: string;
}

export async function login(email: string, password: string): Promise<User> {
  const { user } = await request<{ user: User }>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  });
  return user;
}

export async function register(email: string, username: string, password: string): Promise<User> {
  const { user } = await request<{ user: User }>('/api/auth/register', {
    method: 'POST',
    body: JSON.stringify({ email, username, password }),
  });
  return user;
}

export async function logout(): Promise<void> {
  await request<void>('/api/auth/logout', { method: 'POST' });
}

/** Returns the logged-in user, or null if there's no valid session. */
export async function fetchCurrentUser(): Promise<User | null> {
  try {
    const { user } = await request<{ user: User }>('/api/auth/me');
    return user;
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) {
      return null;
    }
    throw e;
  }
}
