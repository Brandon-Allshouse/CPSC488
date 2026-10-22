import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from './client';
import { fetchSavedVideos, removeSavedVideo, saveVideo } from './savedVideos';

vi.mock('./client', () => ({ request: vi.fn() }));
beforeEach(() => vi.resetAllMocks());

describe('saved videos api', () => {
  it('loads the signed-in user saved videos', async () => {
    const video = { youtubeId: 'dQw4w9WgXcQ', title: 'Test video', channelTitle: 'Test channel', topicId: 1, publishedAt: '2026-10-22T00:00:00Z' };
    vi.mocked(request).mockResolvedValue({ videos: [video] });
    expect(await fetchSavedVideos()).toEqual([video]);
    expect(request).toHaveBeenCalledWith('/api/me/saved-videos');
  });

  it('saves and removes by video id only, with no user id sent', async () => {
    vi.mocked(request).mockResolvedValue(undefined);
    await saveVideo('dQw4w9WgXcQ');
    await removeSavedVideo('dQw4w9WgXcQ');
    expect(request).toHaveBeenNthCalledWith(1, '/api/me/saved-videos/dQw4w9WgXcQ', { method: 'POST' });
    expect(request).toHaveBeenNthCalledWith(2, '/api/me/saved-videos/dQw4w9WgXcQ', { method: 'DELETE' });
  });

  it('escapes the id so it cannot change the request path', async () => {
    vi.mocked(request).mockResolvedValue(undefined);
    await saveVideo('../admin');
    expect(request).toHaveBeenCalledWith('/api/me/saved-videos/..%2Fadmin', { method: 'POST' });
  });
});
