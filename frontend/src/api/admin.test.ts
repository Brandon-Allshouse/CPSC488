// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  banUser,
  banVideo,
  downloadAuditLog,
  fetchAdminActions,
  fetchAdminStats,
  fetchAiTopics,
  fetchBannedVideos,
  removeAiTopic,
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

  it('lists AI-added topics and removes one with a reason', async () => {
    const fetchMock = fakeFetch({ topics: [] });
    expect(await fetchAiTopics()).toEqual([]);
    expect(fetchMock.mock.calls[0][0]).toBe('/api/admin/topics');

    const removeMock = fakeFetch(null, 204);
    await removeAiTopic(121, 'Not educational');
    expect(removeMock.mock.calls[0][0]).toBe('/api/admin/topics/121');
    expect(removeMock.mock.calls[0][1].method).toBe('DELETE');
    expect(JSON.parse(removeMock.mock.calls[0][1].body)).toEqual({ reason: 'Not educational' });
  });
});

describe('downloadAuditLog', () => {
  function csvResponse(disposition: string | null) {
    const headers: Record<string, string> = { 'Content-Type': 'text/csv' };
    if (disposition) headers['Content-Disposition'] = disposition;
    return new Response('"id"\r\n', { status: 200, headers });
  }

  function watchDownload() {
    const created = vi.fn(() => 'blob:test');
    vi.stubGlobal('URL', { ...URL, createObjectURL: created, revokeObjectURL: vi.fn() });
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    return { created, click };
  }

  afterEach(() => vi.restoreAllMocks());

  it('saves the file with the name the backend picked', async () => {
    const fetchMock = vi.fn().mockResolvedValue(csvResponse('attachment; filename="brainfeed-audit-log-2026-11-11.csv"'));
    vi.stubGlobal('fetch', fetchMock);
    const { created, click } = watchDownload();
    let savedAs = '';
    click.mockImplementation(function (this: HTMLAnchorElement) { savedAs = this.download; });

    await downloadAuditLog();

    expect(fetchMock.mock.calls[0][0]).toBe('/api/admin/actions/export');
    expect(fetchMock.mock.calls[0][1].credentials).toBe('same-origin');
    expect(created).toHaveBeenCalledOnce();
    expect(savedAs).toBe('brainfeed-audit-log-2026-11-11.csv');
  });

  it('ignores a strange file name and uses a safe default', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(csvResponse('attachment; filename="../../evil.exe"')));
    const { click } = watchDownload();
    let savedAs = '';
    click.mockImplementation(function (this: HTMLAnchorElement) { savedAs = this.download; });
    await downloadAuditLog();
    expect(savedAs).toBe('brainfeed-audit-log.csv');
  });

  it('turns a backend error into an ApiError with its message', async () => {
    fakeFetch({ error: 'Admins only.' }, 403);
    await expect(downloadAuditLog()).rejects.toMatchObject({ status: 403, message: 'Admins only.' });
  });

  it('gives a generic message when the error body is not JSON', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>proxy error</html>', { status: 502 })));
    await expect(downloadAuditLog()).rejects.toMatchObject({ status: 502, message: 'Download failed (502)' });
  });

  it('says the server could not be reached when fetch fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    await expect(downloadAuditLog()).rejects.toMatchObject({ status: 0 });
  });
});
