// Shared fetch helper for every backend call. New API calls should go through request() so they
// get the same headers and error handling.

/**
 * A failed request. `message` is the backend's user-friendly error text (or one of ours), safe to
 * display. `status` is 0 when the server couldn't be reached at all.
 */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

// Backend error messages are one short sentence. Anything longer isn't one of ours (say, a proxy
// that put a whole error page in the field), so it's not shown to the user.
const MAX_MESSAGE_LENGTH = 300;

/** Shown when the request never got an answer (offline, or the server is down). */
export const OFFLINE_MESSAGE = "Couldn't reach BrainFeed. Check your connection and try again.";

/**
 * Calls the backend and returns the parsed JSON, or throws ApiError. Paths are relative
 * ("/api/..."): in development Vite forwards them to the backend (see vite.config.ts), which
 * keeps the browser on a single origin so the session cookie is sent automatically.
 */
export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const method = (init?.method ?? 'GET').toUpperCase();
  let res: Response;
  try {
    res = await fetchWith(path, method, init);
  } catch {
    // fetch only rejects when there's no response at all (offline, server gone). Its own messages,
    // like "Failed to fetch", mean nothing to users, so every page gets this one instead.
    throw new ApiError(0, OFFLINE_MESSAGE);
  }

  // 204 No Content (e.g. logout) has no body to parse.
  if (res.status === 204) {
    return undefined as T;
  }

  // null if the body isn't JSON, e.g. the Vite proxy's error page when the backend isn't running.
  const data = await res.json().catch(() => null);
  if (!res.ok) {
    const error = data && typeof data.error === 'string' ? data.error.trim() : '';
    const message = error && error.length <= MAX_MESSAGE_LENGTH ? error : `Request failed (${res.status})`;
    throw new ApiError(res.status, message);
  }
  return data as T;
}

function fetchWith(path: string, method: string, init?: RequestInit) {
  return fetch(path, {
    ...init,
    headers: {
      Accept: 'application/json',
      // Required on every non-GET request, even ones without a body (like logout): the backend
      // rejects writes without it as part of its CSRF protection (App.rejectCrossSiteWrites).
      ...(method !== 'GET' ? { 'Content-Type': 'application/json' } : {}),
      ...init?.headers,
    },
    // Set after the spread so no caller can turn it off or send the cookie cross-site. All /api
    // paths are same-origin, so 'include' would add nothing but risk.
    credentials: 'same-origin',
  });
}
