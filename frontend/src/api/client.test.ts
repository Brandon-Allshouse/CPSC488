import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, OFFLINE_MESSAGE, request } from './client';

// replaces the browser's fetch with one that always returns `response`
function fakeFetch(response: Response) {
  const fetchMock = vi.fn().mockResolvedValue(response);
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('request', () => {
  it('returns the JSON the backend sent', async () => {
    fakeFetch(json({ status: 'ok' }));
    expect(await request('/api/health')).toEqual({ status: 'ok' });
  });

  it('does not send Content-Type on GET requests', async () => {
    const fetchMock = fakeFetch(json({}));
    await request('/api/health');
    const [, init] = fetchMock.mock.calls[0];
    expect(init.headers).not.toHaveProperty('Content-Type');
    expect(init.credentials).toBe('same-origin');
  });

  it('sends Content-Type: application/json on POST and PUT, which the backend needs for CSRF protection', async () => {
    const fetchMock = fakeFetch(json({}));
    await request('/api/auth/logout', { method: 'POST' });
    await request('/api/me/interests', { method: 'put', body: '{}' });
    expect(fetchMock.mock.calls[0][1].headers['Content-Type']).toBe('application/json');
    expect(fetchMock.mock.calls[1][1].headers['Content-Type']).toBe('application/json');
  });

  it('returns nothing for 204 No Content', async () => {
    fakeFetch(new Response(null, { status: 204 }));
    expect(await request('/api/auth/logout', { method: 'POST' })).toBeUndefined();
  });

  it('throws an ApiError with the backend message when the request fails', async () => {
    fakeFetch(json({ error: 'Incorrect email or password.' }, 401));
    const error = await request<never>('/api/auth/login').catch((e: ApiError) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(401);
    expect(error.message).toBe('Incorrect email or password.');
  });

  it('uses a general message when the error is not JSON (like when the backend is down)', async () => {
    fakeFetch(new Response('<html>Bad Gateway</html>', { status: 502 }));
    const error = await request<never>('/api/feed').catch((e: ApiError) => e);
    expect(error.message).toBe('Request failed (502)');
  });

  it('shows a plain message instead of "Failed to fetch" when the server cannot be reached', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    const error = await request<never>('/api/feed').catch((e: ApiError) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(0);
    expect(error.message).toBe(OFFLINE_MESSAGE);
  });

  it('does not show a huge error field, since that is not one of our messages', async () => {
    fakeFetch(json({ error: 'x'.repeat(5000) }, 500));
    const error = await request<never>('/api/feed').catch((e: ApiError) => e);
    expect(error.message).toBe('Request failed (500)');
  });

  it('uses the general message when the error field is not text', async () => {
    fakeFetch(json({ error: { stack: 'at Server.java:12' } }, 500));
    const error = await request<never>('/api/feed').catch((e: ApiError) => e);
    expect(error.message).toBe('Request failed (500)');
  });

  it('always sends credentials same-origin, even if a caller asks for include', async () => {
    const fetchMock = fakeFetch(json({}));
    await request('/api/health', { credentials: 'include' });
    expect(fetchMock.mock.calls[0][1].credentials).toBe('same-origin');
  });

  it('sends Content-Type on DELETE too, so the CSRF check passes for removals', async () => {
    const fetchMock = fakeFetch(new Response(null, { status: 204 }));
    await request('/api/me/watch-history', { method: 'DELETE' });
    expect(fetchMock.mock.calls[0][1].headers['Content-Type']).toBe('application/json');
    expect(fetchMock.mock.calls[0][1].headers.Accept).toBe('application/json');
  });
});
