import { afterEach, describe, expect, it, vi } from 'vitest';
import { fetchCurrentUser, login, logout, register } from './auth';

const testUser = { id: 1, email: 'test@sru.edu', username: 'testuser', createdAt: '2026-10-05T00:00:00Z' };

function fakeFetch(body: unknown, status = 200) {
  const fetchMock = vi.fn().mockResolvedValue(
    status === 204 ? new Response(null, { status }) : new Response(JSON.stringify(body), { status }),
  );
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('auth api', () => {
  it('login posts the email and password and returns the user', async () => {
    const fetchMock = fakeFetch({ user: testUser });
    expect(await login('test@sru.edu', 'simple test password')).toEqual(testUser);
    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe('/api/auth/login');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body)).toEqual({ email: 'test@sru.edu', password: 'simple test password' });
  });

  it('register posts the email, username and password', async () => {
    const fetchMock = fakeFetch({ user: testUser }, 201);
    expect(await register('test@sru.edu', 'testuser', 'simple test password')).toEqual(testUser);
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({
      email: 'test@sru.edu',
      username: 'testuser',
      password: 'simple test password',
    });
  });

  it('logout posts to the logout endpoint', async () => {
    const fetchMock = fakeFetch(null, 204);
    await logout();
    expect(fetchMock.mock.calls[0][0]).toBe('/api/auth/logout');
    expect(fetchMock.mock.calls[0][1].method).toBe('POST');
  });

  it('fetchCurrentUser returns the user when logged in', async () => {
    fakeFetch({ user: testUser });
    expect(await fetchCurrentUser()).toEqual(testUser);
  });

  it('fetchCurrentUser returns null when not logged in', async () => {
    fakeFetch({ error: 'Not logged in.' }, 401);
    expect(await fetchCurrentUser()).toBeNull();
  });

  it('fetchCurrentUser still throws for other errors', async () => {
    fakeFetch({ error: 'Something went wrong on our end.' }, 500);
    await expect(fetchCurrentUser()).rejects.toThrow('Something went wrong on our end.');
  });
});
