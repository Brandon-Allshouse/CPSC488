// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { clearWatchHistory, fetchWatchHistory, removeFromWatchHistory, type WatchedVideo } from '../api/history';
import WatchHistoryPage, { timeAgo } from './WatchHistoryPage';

vi.mock('../api/history', () => ({
  fetchWatchHistory: vi.fn(),
  removeFromWatchHistory: vi.fn(),
  clearWatchHistory: vi.fn(),
}));

const testUser = { id: 1, email: 'test@sru.edu', username: 'testuser', createdAt: '2026-10-05T00:00:00Z', admin: false };
const handlers = {
  onBack: vi.fn(),
  onEditInterests: vi.fn(),
  onShowLikedVideos: vi.fn(),
  onShowLeaderboard: vi.fn(),
  onShowHistory: vi.fn(),
  onLogout: vi.fn(),
  onOpenVideo: vi.fn(),
};

function watched(n: number, overrides: Partial<WatchedVideo> = {}): WatchedVideo {
  return {
    youtubeId: `video${String(n).padStart(6, '0')}`,
    title: `Test video ${n}`,
    creator: 'Test Channel',
    watchedAt: new Date(Date.now() - n * 60_000).toISOString(),
    ...overrides,
  };
}

function renderPage(user = testUser as typeof testUser | null) {
  return render(<WatchHistoryPage user={user} {...handlers} />);
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(fetchWatchHistory).mockResolvedValue({ videos: [watched(1), watched(2)], limit: 25 });
  vi.mocked(removeFromWatchHistory).mockResolvedValue(undefined);
  vi.mocked(clearWatchHistory).mockResolvedValue(undefined);
});

afterEach(() => {
  cleanup();
});

describe('WatchHistoryPage', () => {
  it('lists the watched videos, newest first, with thumbnails and when they were watched', async () => {
    const { container } = renderPage();
    expect(await screen.findByText('Test video 1')).toBeTruthy();

    const items = container.querySelectorAll('#watch-history-list li');
    expect(items).toHaveLength(2);
    expect(items[0].textContent).toContain('Test video 1');
    expect(items[1].textContent).toContain('Test video 2');
    expect(items[0].querySelector('img')!.getAttribute('src')).toBe('https://i.ytimg.com/vi/video000001/mqdefault.jpg');
    expect(items[0].querySelector('time')!.textContent).toBe('1 minute ago');
  });

  it('says how many videos it keeps', async () => {
    renderPage();
    expect(await screen.findByText('The last 25 videos you pressed play on. Only you can see this.')).toBeTruthy();
  });

  it('shows a loading message first', async () => {
    renderPage();
    expect(screen.getByText('Loading your watch history…')).toBeTruthy();
    await screen.findByText('Test video 1');
  });

  it('opens a video in the feed when its title is clicked', async () => {
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Test video 2' }));
    expect(handlers.onOpenVideo).toHaveBeenCalledWith(watched(2, { watchedAt: expect.any(String) }));
  });

  it('removes one video', async () => {
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Remove Test video 1 from your history' }));

    await vi.waitFor(() => expect(screen.queryByText('Test video 1')).toBeNull());
    expect(removeFromWatchHistory).toHaveBeenCalledWith('video000001');
    expect(screen.getByText('Test video 2')).toBeTruthy();
  });

  it('asks before clearing everything, and can be cancelled', async () => {
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Clear history' }));
    expect(screen.getByText('Clear all?')).toBeTruthy();
    expect(clearWatchHistory).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.getByRole('button', { name: 'Clear history' })).toBeTruthy();
    expect(clearWatchHistory).not.toHaveBeenCalled();
  });

  it('clears everything once confirmed', async () => {
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Clear history' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, clear' }));

    expect(await screen.findByText('Nothing here yet')).toBeTruthy();
    expect(clearWatchHistory).toHaveBeenCalledTimes(1);
    // nothing left to clear, so the button goes away
    expect(screen.queryByRole('button', { name: 'Clear history' })).toBeNull();
  });

  it('shows an empty state when nothing has been watched', async () => {
    vi.mocked(fetchWatchHistory).mockResolvedValue({ videos: [], limit: 25 });
    renderPage();
    expect(await screen.findByText('Nothing here yet')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Clear history' })).toBeNull();
  });

  it("doesn't ask the backend for anything when a guest opens it", () => {
    renderPage(null);
    expect(screen.getByText('Sign in to keep a watch history.')).toBeTruthy();
    expect(fetchWatchHistory).not.toHaveBeenCalled();
  });

  it('shows an error if the history could not be loaded', async () => {
    vi.mocked(fetchWatchHistory).mockRejectedValue(new ApiError(500, 'Something went wrong on our end.'));
    renderPage();
    expect((await screen.findByRole('alert')).textContent).toBe('Something went wrong on our end.');
  });

  it('shows an error if removing fails, and keeps the video', async () => {
    vi.mocked(removeFromWatchHistory).mockRejectedValue(new ApiError(404, "That video isn't in your history."));
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Remove Test video 1 from your history' }));
    expect((await screen.findByRole('alert')).textContent).toBe("That video isn't in your history.");
    expect(screen.getByText('Test video 1')).toBeTruthy();
  });

  it('sends the user to log in again when their session has expired', async () => {
    vi.mocked(fetchWatchHistory).mockRejectedValue(new ApiError(401, 'Not logged in.'));
    renderPage();
    await vi.waitFor(() => expect(handlers.onLogout).toHaveBeenCalledTimes(1));
  });

  it('shows titles and creators with HTML in them as plain text', async () => {
    // these come from YouTube, so they should never be treated as HTML
    vi.mocked(fetchWatchHistory).mockResolvedValue({
      videos: [watched(1, { title: '<b>not bold</b>', creator: '<i>Test Channel</i>' })],
      limit: 25,
    });
    const { container } = renderPage();
    expect(await screen.findByText('<b>not bold</b>')).toBeTruthy();
    // shown twice: on the video and as the top channel
    expect(screen.getAllByText('<i>Test Channel</i>')).toHaveLength(2);
    expect(container.querySelector('b')).toBeNull();
    expect(container.querySelector('i')).toBeNull();
  });
  it('sums up the history at the top', async () => {
    vi.mocked(fetchWatchHistory).mockResolvedValue({
      videos: [watched(1), watched(2, { creator: 'Other Channel' }), watched(3)],
      limit: 25,
    });
    renderPage();
    const summary = await screen.findByRole('region', { name: 'Watch history summary' });

    expect(summary.textContent).toContain('Videos watched3');
    expect(summary.textContent).toContain('Channels2');
    expect(summary.textContent).toContain('Top channelTest Channel2 videos');
  });

  it('says 25+ when the history is full, since older ones were dropped', async () => {
    vi.mocked(fetchWatchHistory).mockResolvedValue({ videos: Array.from({ length: 25 }, (_, i) => watched(i + 1)), limit: 25 });
    renderPage();
    const summary = await screen.findByRole('region', { name: 'Watch history summary' });
    expect(summary.textContent).toContain('Videos watched25+');
  });

  it('groups videos by when they were watched', async () => {
    const lastMonth = new Date(Date.now() - 40 * 86_400_000).toISOString();
    vi.mocked(fetchWatchHistory).mockResolvedValue({ videos: [watched(1), watched(2, { watchedAt: lastMonth })], limit: 25 });
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Today' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Older' })).toBeTruthy();
  });

  it('has a button back to the feed when the history is empty', async () => {
    vi.mocked(fetchWatchHistory).mockResolvedValue({ videos: [], limit: 25 });
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Start watching' }));
    expect(handlers.onBack).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('region', { name: 'Watch history summary' })).toBeNull();
  });
});

describe('timeAgo', () => {
  const now = Date.parse('2026-10-21T12:00:00Z');

  it('says just now for under a minute', () => {
    expect(timeAgo('2026-10-21T11:59:30Z', now)).toBe('just now');
  });

  it('uses minutes, hours, then days', () => {
    expect(timeAgo('2026-10-21T11:55:00Z', now)).toBe('5 minutes ago');
    expect(timeAgo('2026-10-21T09:00:00Z', now)).toBe('3 hours ago');
    expect(timeAgo('2026-10-20T12:00:00Z', now)).toBe('yesterday');
    expect(timeAgo('2026-10-11T12:00:00Z', now)).toBe('10 days ago');
  });
});
