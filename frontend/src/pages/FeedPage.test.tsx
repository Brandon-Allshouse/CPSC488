// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchFeed, type Video } from '../api/feed';
import { scrollInto, useFakeIntersectionObserver } from '../test/fakeIntersectionObserver';
import FeedPage from './FeedPage';

vi.mock('../api/savedVideos', () => ({
  fetchSavedVideos: vi.fn().mockResolvedValue([]),
  saveVideo: vi.fn().mockResolvedValue(undefined),
  removeSavedVideo: vi.fn().mockResolvedValue(undefined),
}));

vi.mock('../api/feed', () => ({ fetchFeed: vi.fn() }));

const onSavedVideos = vi.fn();

const testUser = { id: 1, email: 'test@sru.edu', username: 'testuser', createdAt: '2026-10-05T00:00:00Z' };

function makeVideo(n: number): Video {
  return {
    youtubeId: `video${String(n).padStart(6, '0')}`,
    title: `Test video ${n}`,
    channelTitle: 'Test Channel',
    topicId: 1,
    publishedAt: '2026-01-15T10:00:00Z',
  };
}

const onEditInterests = vi.fn();
const onLogout = vi.fn();

function renderFeed(user = testUser as typeof testUser | null) {
  const result = render(
    <FeedPage topicIds={[1, 2]} user={user} onEditInterests={onEditInterests} onLogout={onLogout} onSavedVideos={onSavedVideos}/>,
  );
  // the invisible marker at the bottom of the feed that triggers loading more
  const scrollToBottom = () => scrollInto(result.container.querySelector('.feed-sentinel')!);
  return { ...result, scrollToBottom };
}

beforeEach(() => {
  vi.clearAllMocks();
  useFakeIntersectionObserver();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

// lets the mocked fetchFeed promise finish and React update, without waiting for real time
async function settle() {
  await act(async () => {});
  await act(async () => {});
}

const stillFinding = { videos: [], nextPage: null, fetching: true };

describe('FeedPage', () => {
  it('shows the brain logo in the bottom-right corner', () => {
    vi.mocked(fetchFeed).mockResolvedValue({ videos: [], nextPage: null, fetching: false });
    renderFeed();
    expect(screen.getByRole('img', { name: 'BrainFeed brain logo' }).className).toBe('feed-logo');
  });

  it('shows the brain logo beside the BrainFeed header', () => {
    vi.mocked(fetchFeed).mockResolvedValue({ videos: [], nextPage: null, fetching: false });
    renderFeed();
    const brand = screen.getByText('BrainFeed').parentElement!;
    expect(brand.className).toBe('feed-brand');
    expect(brand.querySelector('.feed-brand-logo')).toBeTruthy();
  });

  it('scrolls the feed up and down when the navigation buttons are pressed', () => {
    vi.mocked(fetchFeed).mockResolvedValue({ videos: [], nextPage: null, fetching: false });
    const { container } = renderFeed();
    const list = container.querySelector('.feed-list')!;
    const scrollBy = vi.fn();
    Object.defineProperty(list, 'clientHeight', { value: 600 });
    Object.defineProperty(list, 'scrollBy', { value: scrollBy });

    fireEvent.click(screen.getByRole('button', { name: 'Scroll up' }));
    fireEvent.click(screen.getByRole('button', { name: 'Scroll down' }));

    expect(scrollBy).toHaveBeenNthCalledWith(1, { top: -600, behavior: 'smooth' });
    expect(scrollBy).toHaveBeenNthCalledWith(2, { top: 600, behavior: 'smooth' });
  });

  it('loads the first page when the feed opens', async () => {
    vi.mocked(fetchFeed).mockResolvedValue({ videos: [makeVideo(1), makeVideo(2)], nextPage: null, fetching: false });
    const { scrollToBottom } = renderFeed();
    scrollToBottom();

    expect(await screen.findByText('Test video 1')).toBeTruthy();
    expect(screen.getByText('Test video 2')).toBeTruthy();
    expect(fetchFeed).toHaveBeenCalledWith([1, 2], expect.any(Number), 0);
  });

  it('opens saved videos for a logged-in user', () => {
  vi.mocked(fetchFeed).mockResolvedValue({
    videos: [],
    nextPage: null,
  });

  renderFeed();

  fireEvent.click(
    screen.getByRole('button', { name: 'Saved' }),
  );

  expect(onSavedVideos).toHaveBeenCalled();
});

  it('loads the next page when scrolling to the bottom, with the same seed', async () => {
    vi.mocked(fetchFeed)
      .mockResolvedValueOnce({ videos: [makeVideo(1)], nextPage: 1, fetching: false })
      .mockResolvedValueOnce({ videos: [makeVideo(2)], nextPage: null, fetching: false });
    const { scrollToBottom } = renderFeed();

    scrollToBottom();
    await screen.findByText('Test video 1');
    scrollToBottom();
    await screen.findByText('Test video 2');

    const firstSeed = vi.mocked(fetchFeed).mock.calls[0][1];
    expect(vi.mocked(fetchFeed).mock.calls[1]).toEqual([[1, 2], firstSeed, 1]);
  });

  it('stops asking for more after the last page', async () => {
    vi.mocked(fetchFeed).mockResolvedValue({ videos: [makeVideo(1)], nextPage: null, fetching: false });
    const { scrollToBottom } = renderFeed();
    scrollToBottom();
    await screen.findByText('Test video 1');

    scrollToBottom();
    expect(fetchFeed).toHaveBeenCalledTimes(1);
  });

  it('says so when there are no videos yet', async () => {
    vi.mocked(fetchFeed).mockResolvedValue({ videos: [], nextPage: null, fetching: false });
    const { scrollToBottom } = renderFeed();
    scrollToBottom();
    expect(await screen.findByText('No videos for these topics yet.')).toBeTruthy();
  });

  it('says it is finding videos for new topics and checks again until they show up', async () => {
    // this was the bug: newly picked topics just said "no videos" until the next hourly fetch
    vi.useFakeTimers();
    vi.mocked(fetchFeed)
      .mockResolvedValueOnce(stillFinding)
      .mockResolvedValueOnce({ videos: [makeVideo(1)], nextPage: null, fetching: false });
    const { scrollToBottom } = renderFeed();
    scrollToBottom();
    await settle();

    expect(screen.getByRole('status').textContent).toContain('Finding videos for these topics');
    expect(screen.queryByText('No videos for these topics yet.')).toBeNull();

    await act(async () => {
      vi.advanceTimersByTime(3000);
    });
    await settle();

    expect(screen.getByText('Test video 1')).toBeTruthy();
    expect(screen.queryByRole('status')).toBeNull();
    expect(vi.mocked(fetchFeed).mock.calls[1][2]).toBe(0);
  });

  it('gives up after a minute and says there are no videos yet', async () => {
    vi.useFakeTimers();
    vi.mocked(fetchFeed).mockResolvedValue(stillFinding);
    const { scrollToBottom } = renderFeed();
    scrollToBottom();
    await settle();

    for (let i = 0; i < 20; i++) {
      await act(async () => {
        vi.advanceTimersByTime(3000);
      });
      await settle();
    }

    expect(screen.getByText('No videos for these topics yet.')).toBeTruthy();
    // the first try plus 20 checks
    expect(fetchFeed).toHaveBeenCalledTimes(21);
  });

  it('stops checking once the user leaves the feed', async () => {
    vi.useFakeTimers();
    vi.mocked(fetchFeed).mockResolvedValue(stillFinding);
    const { scrollToBottom, unmount } = renderFeed();
    scrollToBottom();
    await settle();

    unmount();
    await act(async () => {
      vi.advanceTimersByTime(30000);
    });

    expect(fetchFeed).toHaveBeenCalledTimes(1);
  });

  it('never shows the same video twice', async () => {
    // new videos can be added while scrolling, which can push one onto the next page too
    vi.mocked(fetchFeed)
      .mockResolvedValueOnce({ videos: [makeVideo(1), makeVideo(2)], nextPage: 1, fetching: false })
      .mockResolvedValueOnce({ videos: [makeVideo(2), makeVideo(3)], nextPage: null, fetching: false });
    const { scrollToBottom } = renderFeed();
    scrollToBottom();
    await screen.findByText('Test video 1');
    scrollToBottom();
    await screen.findByText('Test video 3');

    expect(screen.getAllByText('Test video 2').length).toBe(1);
  });

  it('shows an error and can try again', async () => {
    vi.mocked(fetchFeed)
      .mockRejectedValueOnce(new Error('Request failed (502)'))
      .mockResolvedValueOnce({ videos: [makeVideo(1)], nextPage: null, fetching: false });
    const { scrollToBottom } = renderFeed();
    scrollToBottom();

    expect((await screen.findByRole('alert')).textContent).toBe('Request failed (502)');
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('Test video 1')).toBeTruthy();
  });

  it('shows the username and a log out button', () => {
    vi.mocked(fetchFeed).mockResolvedValue({ videos: [], nextPage: null, fetching: false });
    renderFeed();
    expect(screen.getByText('testuser')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Log out' }));
    expect(onLogout).toHaveBeenCalled();
  });

  it('shows Guest and a log in button for guests', () => {
    vi.mocked(fetchFeed).mockResolvedValue({ videos: [], nextPage: null, fetching: false });
    renderFeed(null);
    expect(screen.getByText('Guest')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Log in' })).toBeTruthy();
  });

  it('interests button goes to the interest picker', () => {
    vi.mocked(fetchFeed).mockResolvedValue({ videos: [], nextPage: null, fetching: false });
    renderFeed();
    fireEvent.click(screen.getByRole('button', { name: 'Interests' }));
    expect(onEditInterests).toHaveBeenCalled();
  });
});
