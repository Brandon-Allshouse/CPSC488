import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from './client';
import { fetchLeaderboard } from './leaderboard';

vi.mock('./client', () => ({ request: vi.fn() }));
beforeEach(() => vi.resetAllMocks());

describe('leaderboard api', () => {
  it('gets both boards in one request', async () => {
    const board = { weekStart: '2026-11-09T00:00:00Z', week: { top: [], you: null }, allTime: { top: [], you: null } };
    vi.mocked(request).mockResolvedValue(board);
    expect(await fetchLeaderboard()).toEqual(board);
    expect(request).toHaveBeenCalledWith('/api/leaderboard');
  });

  it('passes errors on to the page', async () => {
    vi.mocked(request).mockRejectedValue(new Error('offline'));
    await expect(fetchLeaderboard()).rejects.toThrow('offline');
  });
});
