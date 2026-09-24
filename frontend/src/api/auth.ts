// Typed wrappers around the backend's /api/auth endpoints (see the README's "Auth API" table).
// New API calls should go through request() below so they get the same headers and error handling.

// Matches User.java on the backend.
export interface User {
  id: number;
  email: string;
  username: string;
  createdAt: string;
}

/** A non-2xx response. `message` is the backend's user-friendly error text, safe to display. */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

/**
 * Calls the backend and returns the parsed JSON, or throws ApiError. Paths are relative
 * ("/api/..."): in development Vite forwards them to the backend (see vite.config.ts), which
 * keeps the browser on a single origin so the session cookie is sent automatically.
 */
async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const method = (init?.method ?? 'GET').toUpperCase();
  const res = await fetch(path, {
    ...init,
    headers: {
      Accept: 'application/json',
      // Required on every non-GET request, even ones without a body (like logout): the backend
      // rejects writes without it as part of its CSRF protection (App.rejectCrossSiteWrites).
      ...(method !== 'GET' ? { 'Content-Type': 'application/json' } : {}),
      ...init?.headers,
    },
    credentials: 'same-origin',
  });

  // 204 No Content (e.g. logout) has no body to parse.
  if (res.status === 204) {
    return undefined as T;
  }

  // null if the body isn't JSON, e.g. the Vite proxy's error page when the backend isn't running.
  const data = await res.json().catch(() => null);
  if (!res.ok) {
    const message = (data && typeof data.error === 'string' && data.error) || `Request failed (${res.status})`;
    throw new ApiError(res.status, message);
  }
  return data as T;
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
