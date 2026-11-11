import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from './client';
import { fetchVideoStudy } from './study';

vi.mock('./client', () => ({ request: vi.fn() }));
beforeEach(() => vi.resetAllMocks());

describe('study api', () => {
  it('asks for one video summary through the shared request helper', async () => {
    vi.mocked(request).mockResolvedValue({ status: 'pending' });
    expect(await fetchVideoStudy('dQw4w9WgXcQ')).toEqual({ status: 'pending' });
    expect(request).toHaveBeenCalledWith('/api/videos/dQw4w9WgXcQ/study');
  });

  it('encodes the video id so it cannot change the path', async () => {
    vi.mocked(request).mockResolvedValue({ status: 'unavailable' });
    await fetchVideoStudy('../admin/x');
    expect(request).toHaveBeenCalledWith('/api/videos/..%2Fadmin%2Fx/study');
  });
});
