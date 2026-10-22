import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  banUser,
  banVideo,
  fetchAdminActions,
  fetchAdminStats,
  fetchBannedVideos,
  searchUsers,
  searchVideos,
  unbanUser,
  unbanVideo,
} from './admin';

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

describe('admin api', () => {
  it('fetchAdminStats returns the counts', async () => {
    const stats = { users: 3, bannedUsers: 1, admins: 1, videos: 10, bannedVideos: 2 };
    const fetchMock = fakeFetch(stats);
    expect(await fetchAdminStats()).toEqual(stats);
    expect(fetchMock.mock.calls[0][0]).toBe('/api/admin/stats');
  });

  it('searchUsers sends the search as a query parameter', async () => {
    const fetchMock = fakeFetch({ users: [] });
    expect(await searchUsers('test user&admin=1')).toEqual([]);
    // encoded, so the search text can't add its own parameters
    expect(fetchMock.mock.calls[0][0]).toBe('/api/admin/users?q=test+user%26admin%3D1');
  });

  it('banUser sends the reason with PUT and unbanUser uses DELETE', async () => {
    const fetchMock = fakeFetch(null, 204);
    await banUser(5, 'Spamming');
    await unbanUser(5);

    expect(fetchMock.mock.calls[0][0]).toBe('/api/admin/users/5/ban');
    expect(fetchMock.mock.calls[0][1].method).toBe('PUT');
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ reason: 'Spamming' });
    expect(fetchMock.mock.calls[1][0]).toBe('/api/admin/users/5/ban');
    expect(fetchMock.mock.calls[1][1].method).toBe('DELETE');
  });

  it('banVideo and unbanVideo encode the video id', async () => {
    const fetchMock = fakeFetch(null, 204);
    await banVideo('dQw4w9WgXcQ', 'Off-topic');
    await unbanVideo('../users/1');

    expect(fetchMock.mock.calls[0][0]).toBe('/api/admin/banned-videos/dQw4w9WgXcQ');
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ reason: 'Off-topic' });
    expect(fetchMock.mock.calls[1][0]).toBe('/api/admin/banned-videos/..%2Fusers%2F1');
    expect(fetchMock.mock.calls[1][1].method).toBe('DELETE');
  });

  it('reads the video, banned video and action lists', async () => {
    const fetchMock = fakeFetch({ videos: [], actions: [] });
    expect(await searchVideos('plants')).toEqual([]);
    expect(await fetchBannedVideos()).toEqual([]);
    expect(await fetchAdminActions()).toEqual([]);
    expect(fetchMock.mock.calls.map((call) => call[0])).toEqual([
      '/api/admin/videos?q=plants',
      '/api/admin/banned-videos',
      '/api/admin/actions',
    ]);
  });
});
