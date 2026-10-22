import { afterEach, describe, expect, it, vi } from 'vitest';
import { addToPlaylist, createPlaylist, deletePlaylist, fetchMyPlaylists, removeFromPlaylist } from './playlists';

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

describe('playlists api', () => {
  it('fetchMyPlaylists returns the playlists', async () => {
    const playlists = [
      { id: 1, name: 'Watch later', watchLater: true, videos: [{ youtubeId: 'dQw4w9WgXcQ', title: 'Title', creator: 'Creator' }] },
    ];
    const fetchMock = fakeFetch({ playlists });
    expect(await fetchMyPlaylists()).toEqual(playlists);
    expect(fetchMock.mock.calls[0][0]).toBe('/api/me/playlists');
  });

  it('createPlaylist POSTs the name as JSON and returns the new playlist', async () => {
    const playlist = { id: 2, name: 'Exam prep', watchLater: false, videos: [] };
    const fetchMock = fakeFetch({ playlist }, 201);
    expect(await createPlaylist('Exam prep')).toEqual(playlist);
    expect(fetchMock.mock.calls[0][0]).toBe('/api/me/playlists');
    expect(fetchMock.mock.calls[0][1].method).toBe('POST');
    expect(fetchMock.mock.calls[0][1].headers['Content-Type']).toBe('application/json');
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ name: 'Exam prep' });
  });

  it('adds with PUT, and removes or deletes with DELETE', async () => {
    const fetchMock = fakeFetch(null, 204);
    await addToPlaylist(2, 'dQw4w9WgXcQ');
    await removeFromPlaylist(2, 'dQw4w9WgXcQ');
    await deletePlaylist(2);
    expect(fetchMock.mock.calls.map((call) => [call[0], call[1].method])).toEqual([
      ['/api/me/playlists/2/videos/dQw4w9WgXcQ', 'PUT'],
      ['/api/me/playlists/2/videos/dQw4w9WgXcQ', 'DELETE'],
      ['/api/me/playlists/2', 'DELETE'],
    ]);
  });

  it('encodes the video id in the path', async () => {
    const fetchMock = fakeFetch(null, 204);
    await addToPlaylist(2, '../../admin/stats');
    expect(fetchMock.mock.calls[0][0]).toBe('/api/me/playlists/2/videos/..%2F..%2Fadmin%2Fstats');
  });

  it('throws the backend error message', async () => {
    fakeFetch({ error: 'You already have a playlist with that name.' }, 409);
    await expect(createPlaylist('Watch later')).rejects.toThrow('You already have a playlist with that name.');
  });
});
