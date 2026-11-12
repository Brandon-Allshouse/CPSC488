// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, OFFLINE_MESSAGE, request } from './client';
import { downloadLearningProgress, fetchLearningProgress, markLearningReviewed, setLearningStatus } from './learningProgress';

vi.mock('./client', async (load) => ({ ...await load<typeof import('./client')>(), request: vi.fn() }));
beforeEach(() => vi.resetAllMocks());
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe('learning progress API', () => {
  it('loads the server snapshot and sends exactly the selected status', async () => {
    vi.mocked(request).mockResolvedValue(undefined);
    await fetchLearningProgress();
    expect(request).toHaveBeenLastCalledWith('/api/me/learning-progress');
    await setLearningStatus('video000001', 'completed');
    expect(request).toHaveBeenLastCalledWith('/api/me/learning-progress/video000001', {
      method: 'PUT', body: '{"status":"completed"}',
    });
  });

  it('encodes identifiers and reviews without owner or timestamp fields', async () => {
    await markLearningReviewed('bad/id?');
    expect(request).toHaveBeenCalledWith('/api/me/learning-progress/bad%2Fid%3F/review', { method: 'POST' });
  });

  it('turns download connection failures into the standard offline message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('fetch failed')));
    await expect(downloadLearningProgress()).rejects.toEqual(new ApiError(0, OFFLINE_MESSAGE));
  });

  it('preserves authentication errors and rejects long proxy error messages', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(new Response('{"error":"Not logged in."}', { status: 401 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ error: 'x'.repeat(301) }), { status: 502 }));
    vi.stubGlobal('fetch', fetcher);
    await expect(downloadLearningProgress()).rejects.toEqual(new ApiError(401, 'Not logged in.'));
    await expect(downloadLearningProgress()).rejects.toEqual(new ApiError(502, 'Download failed (502)'));
  });

  it('downloads a file with same-origin credentials and cleans up its object URL', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('Video ID,Title\r\n', { headers: { 'Content-Type': 'text/csv' } })));
    const createUrl = vi.fn().mockReturnValue('blob:learning-test');
    const revokeUrl = vi.fn();
    vi.stubGlobal('URL', class extends URL { static createObjectURL = createUrl; static revokeObjectURL = revokeUrl; });
    let filename: string | undefined;
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) { filename = this.download; });
    await downloadLearningProgress();
    expect(fetch).toHaveBeenCalledWith('/api/me/learning-progress/export', { headers: { Accept: 'text/csv' }, credentials: 'same-origin' });
    expect(filename).toBe('brainfeed-learning-progress.csv');
    expect(document.querySelector('a[download]')).toBeNull();
    await vi.waitFor(() => expect(revokeUrl).toHaveBeenCalledWith('blob:learning-test'));
  });
});
