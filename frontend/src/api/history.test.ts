import { afterEach, describe, expect, it, vi } from 'vitest';
import { clearWatchHistory, fetchWatchHistory, recordWatch, removeFromWatchHistory } from './history';

function fakeFetch(body: unknown, status = 200) {
  const fetchMock = vi
    .fn()
    .mockImplementation(() =>
      Promise.resolve(status === 204 ? new Response(null, { status }) : new Response(JSON.stringify(body), { status })),
    );
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('watch history api', () => {
  it('fetchWatchHistory returns the videos and the limit', async () => {
    const history = {
      videos: [{ youtubeId: 'dQw4w9WgXcQ', title: 'Title', creator: 'Creator', watchedAt: '2026-10-21T12:00:00Z' }],
      limit: 25,
    };
    const fetchMock = fakeFetch(history);
    expect(await fetchWatchHistory()).toEqual(history);
    expect(fetchMock.mock.calls[0][0]).toBe('/api/me/watch-history');
  });

  it('recordWatch uses PUT with the JSON header the backend requires', async () => {
    const fetchMock = fakeFetch(null, 204);
    await recordWatch('dQw4w9WgXcQ');
    expect(fetchMock.mock.calls[0][0]).toBe('/api/me/watch-history/dQw4w9WgXcQ');
    expect(fetchMock.mock.calls[0][1].method).toBe('PUT');
    expect(fetchMock.mock.calls[0][1].headers['Content-Type']).toBe('application/json');
  });

  it('removes one video or clears everything with DELETE', async () => {
    const fetchMock = fakeFetch(null, 204);
    await removeFromWatchHistory('dQw4w9WgXcQ');
    await clearWatchHistory();
    expect(fetchMock.mock.calls.map((call) => [call[0], call[1].method])).toEqual([
      ['/api/me/watch-history/dQw4w9WgXcQ', 'DELETE'],
      ['/api/me/watch-history', 'DELETE'],
    ]);
  });

  it('encodes the video id in the path', async () => {
    const fetchMock = fakeFetch(null, 204);
    await recordWatch('../admin/stats');
    expect(fetchMock.mock.calls[0][0]).toBe('/api/me/watch-history/..%2Fadmin%2Fstats');
  });
});
