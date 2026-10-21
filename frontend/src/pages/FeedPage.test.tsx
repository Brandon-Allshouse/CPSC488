// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  fetchFeed,
  fetchMyVideoFeedback,
  saveMyVideoFeedback,
  type SavedVideo,
  type Video,
} from '../api/feed';
import { scrollInto, useFakeIntersectionObserver } from '../test/fakeIntersectionObserver';
import FeedPage from './FeedPage';

// The admin page loads its own data; empty answers are enough to check the navigation.
vi.mock('../api/admin', () => ({
  fetchAdminStats: vi.fn().mockResolvedValue({ users: 0, bannedUsers: 0, admins: 1, videos: 0, bannedVideos: 0 }),
  searchUsers: vi.fn().mockResolvedValue([]),
  searchVideos: vi.fn().mockResolvedValue([]),
  fetchBannedVideos: vi.fn().mockResolvedValue([]),
  fetchAdminActions: vi.fn().mockResolvedValue([]),
}));

vi.mock('../api/feed', () => ({
  fetchFeed: vi.fn(),
  fetchMyVideoFeedback: vi.fn(),
  saveMyVideoFeedback: vi.fn(),
}));

const testUser = { id: 1, email: 'test@sru.edu', username: 'testuser', createdAt: '2026-10-05T00:00:00Z', admin: false };

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
    <FeedPage topicIds={[1, 2]} user={user} onEditInterests={onEditInterests} onLogout={onLogout} />,
  );
  // the invisible marker at the bottom of the feed that triggers loading more
  const scrollToBottom = () => scrollInto(result.container.querySelector('.feed-sentinel')!);
  return { ...result, scrollToBottom };
}

beforeEach(() => {
  vi.clearAllMocks();
  // clearAllMocks keeps queued mockResolvedValueOnce answers, so a test that stopped early would
  // hand its leftover pages to the next test. Reset fetchFeed completely instead.
  vi.mocked(fetchFeed).mockReset();
  useFakeIntersectionObserver();
  vi.mocked(fetchMyVideoFeedback).mockResolvedValue({ likedVideos: [], dislikedVideos: [] });
  vi.mocked(saveMyVideoFeedback).mockResolvedValue(undefined);
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
    expect(screen.getByRole('img', { name: 'BrainFeed brain logo' }).classList.contains('feed-logo')).toBe(true);
  });

  it('shows the brain logo beside the BrainFeed header', () => {
    vi.mocked(fetchFeed).mockResolvedValue({ videos: [], nextPage: null, fetching: false });
    renderFeed();
    const brand = screen.getByText('BrainFeed').parentElement!;
    expect(brand.classList.contains('feed-brand')).toBe(true);
    expect(brand.querySelector('.feed-brand-logo')).toBeTruthy();
  });

  it('moves between videos with the arrow keys and J/K', () => {
    vi.mocked(fetchFeed).mockResolvedValue({ videos: [], nextPage: null, fetching: false });
    const { container } = renderFeed();
    const list = container.querySelector('.feed-list')!;
    const scrollBy = vi.fn();
    Object.defineProperty(list, 'clientHeight', { value: 600 });
    Object.defineProperty(list, 'scrollBy', { value: scrollBy });

    fireEvent.keyDown(document.body, { key: 'ArrowDown' });
    fireEvent.keyDown(document.body, { key: 'j' });
    fireEvent.keyDown(document.body, { key: 'ArrowUp' });
    fireEvent.keyDown(document.body, { key: 'k' });

    expect(scrollBy.mock.calls.map(([options]) => options.top)).toEqual([600, 600, -600, -600]);
  });

  it('ignores the shortcut keys while typing or with Ctrl held', () => {
    vi.mocked(fetchFeed).mockResolvedValue({ videos: [], nextPage: null, fetching: false });
    const { container } = renderFeed();
    const list = container.querySelector('.feed-list')!;
    const scrollBy = vi.fn();
    Object.defineProperty(list, 'scrollBy', { value: scrollBy });
    const input = document.createElement('input');
    document.body.appendChild(input);

    fireEvent.keyDown(input, { key: 'j' });
    fireEvent.keyDown(document.body, { key: 'ArrowDown', ctrlKey: true });
    fireEvent.keyDown(document.body, { key: 'x' });

    expect(scrollBy).not.toHaveBeenCalled();
    input.remove();
  });

  it('turns the shortcut keys off while another page is open', () => {
    vi.mocked(fetchFeed).mockResolvedValue({ videos: [], nextPage: null, fetching: false });
    renderFeed();
    fireEvent.click(screen.getByText('Profile'));
    fireEvent.click(screen.getByRole('button', { name: 'Leaderboard' }));
    // the feed list isn't on screen anymore, so a key press must not throw or scroll anything
    expect(() => fireEvent.keyDown(document.body, { key: 'ArrowDown' })).not.toThrow();
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

  it('loads saved choices and persists a changed video choice', async () => {
    const savedVideo: SavedVideo = {
      youtubeId: 'video000001',
      title: 'Test video 1',
      creator: 'Test Channel',
    };
    vi.mocked(fetchMyVideoFeedback).mockResolvedValue({ likedVideos: [savedVideo], dislikedVideos: [] });
    vi.mocked(fetchFeed).mockResolvedValue({ videos: [makeVideo(1)], nextPage: null, fetching: false });
    const { scrollToBottom } = renderFeed();

    scrollToBottom();
    const like = await screen.findByRole('button', { name: 'I liked this video' });
    await waitFor(() => expect(like.getAttribute('aria-pressed')).toBe('true'));
    expect(like.getAttribute('aria-pressed')).toBe('true');
    fireEvent.click(like);
    await vi.waitFor(() => expect(saveMyVideoFeedback).toHaveBeenCalledWith('video000001', null));
    await waitFor(() => expect(like.getAttribute('aria-pressed')).toBe('false'));
  });

  it('takes a guest to login instead of saving video feedback', async () => {
    vi.mocked(fetchFeed).mockResolvedValue({ videos: [makeVideo(1)], nextPage: null, fetching: false });
    const { scrollToBottom } = renderFeed(null);
    scrollToBottom();

    fireEvent.click(await screen.findByRole('button', { name: 'I liked this video' }));

    expect(onLogout).toHaveBeenCalled();
    expect(saveMyVideoFeedback).not.toHaveBeenCalled();
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

  it('keeps loading more videos after visiting another page and coming back', async () => {
    vi.mocked(fetchFeed)
      .mockResolvedValueOnce({ videos: [makeVideo(1)], nextPage: 1, fetching: false })
      .mockResolvedValueOnce({ videos: [makeVideo(2)], nextPage: null, fetching: false });
    const { scrollToBottom } = renderFeed();
    scrollToBottom();
    await screen.findByText('Test video 1');

    fireEvent.click(screen.getByText('Profile'));
    fireEvent.click(screen.getByRole('button', { name: 'Leaderboard' }));
    fireEvent.click(screen.getByRole('button', { name: 'Back to feed' }));

    // the bottom marker is a new element now, and it still has to trigger the next page
    scrollToBottom();
    expect(await screen.findByText('Test video 2')).toBeTruthy();
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

  it('shows the username and sign out action in the profile menu', () => {
    vi.mocked(fetchFeed).mockResolvedValue({ videos: [], nextPage: null, fetching: false });
    renderFeed();
    fireEvent.click(screen.getByText('Profile'));
    expect(screen.getByText('testuser')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(onLogout).toHaveBeenCalled();
  });

  it('opens the blank leaderboard screen and returns to the feed', () => {
    vi.mocked(fetchFeed).mockResolvedValue({ videos: [], nextPage: null, fetching: false });
    renderFeed();

    fireEvent.click(screen.getByText('Profile'));
    fireEvent.click(screen.getByRole('button', { name: 'Leaderboard' }));

    expect(screen.getByRole('heading', { name: 'Leaderboard' })).toBeTruthy();
    expect(screen.getByRole('region', { name: 'Leaderboard information' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Update leaderboard' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Back to feed' }));
    expect(screen.getByRole('button', { name: 'Interests' })).toBeTruthy();
  });

  it('lets an admin open the admin page and come back', async () => {
    vi.mocked(fetchFeed).mockResolvedValue({ videos: [], nextPage: null, fetching: false });
    renderFeed({ ...testUser, admin: true });

    fireEvent.click(screen.getByText('Profile'));
    fireEvent.click(screen.getByRole('button', { name: 'Admin' }));

    expect(screen.getByRole('heading', { name: 'Admin' })).toBeTruthy();
    expect(await screen.findByText('No banned videos.')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Back to feed' }));
    expect(screen.queryByRole('heading', { name: 'Admin' })).toBeNull();
  });

  // jsdom has no scrollIntoView, so record which elements were scrolled to.
  function trackScrollIntoView() {
    const scrolledTo: Element[] = [];
    Element.prototype.scrollIntoView = function (this: Element) {
      scrolledTo.push(this);
    };
    return scrolledTo;
  }

  async function openFromYourVideos(title: string) {
    fireEvent.click(screen.getByText('Profile'));
    fireEvent.click(screen.getByRole('button', { name: 'Liked videos' }));
    fireEvent.click(await screen.findByRole('button', { name: title }));
  }

  it('opens a liked video that is already in the feed and scrolls to it', async () => {
    const scrolledTo = trackScrollIntoView();
    vi.mocked(fetchMyVideoFeedback).mockResolvedValue({
      likedVideos: [{ youtubeId: 'video000002', title: 'Test video 2', creator: 'Test Channel' }],
      dislikedVideos: [],
    });
    vi.mocked(fetchFeed).mockResolvedValue({ videos: [makeVideo(1), makeVideo(2)], nextPage: null, fetching: false });
    const { container, scrollToBottom } = renderFeed();
    scrollToBottom();
    await screen.findByText('Test video 2');

    await openFromYourVideos('Test video 2');

    // back on the feed, with the same two videos and no copy
    expect(container.querySelectorAll('article')).toHaveLength(2);
    const card = container.querySelector('#video-video000002')!;
    expect(scrolledTo).toEqual([card]);
    expect(card.getAttribute('data-highlighted')).toBe('true');
  });

  it('puts a liked video at the top of the feed when it was not loaded yet', async () => {
    const scrolledTo = trackScrollIntoView();
    vi.mocked(fetchMyVideoFeedback).mockResolvedValue({
      likedVideos: [{ youtubeId: 'video000009', title: 'Saved video', creator: 'Saved Channel' }],
      dislikedVideos: [],
    });
    vi.mocked(fetchFeed)
      .mockResolvedValueOnce({ videos: [makeVideo(1)], nextPage: 1, fetching: false })
      .mockResolvedValueOnce({ videos: [makeVideo(9), makeVideo(10)], nextPage: null, fetching: false });
    const { container, scrollToBottom } = renderFeed();
    scrollToBottom();
    await screen.findByText('Test video 1');

    await openFromYourVideos('Saved video');

    const cards = container.querySelectorAll('article');
    expect(cards[0].id).toBe('video-video000009');
    expect(within(cards[0] as HTMLElement).getByText('Saved video')).toBeTruthy();
    expect(within(cards[0] as HTMLElement).getByText('Saved Channel')).toBeTruthy();
    expect(scrolledTo).toEqual([cards[0]]);

    // the next page has the same video in it, but it isn't shown a second time
    scrollToBottom();
    await screen.findByText('Test video 10');
    expect(container.querySelectorAll('#video-video000009')).toHaveLength(1);
  });

  it('stops outlining the opened video after a few seconds', async () => {
    trackScrollIntoView();
    vi.mocked(fetchMyVideoFeedback).mockResolvedValue({
      likedVideos: [{ youtubeId: 'video000001', title: 'Test video 1', creator: 'Test Channel' }],
      dislikedVideos: [],
    });
    vi.mocked(fetchFeed).mockResolvedValue({ videos: [makeVideo(1)], nextPage: null, fetching: false });
    const { container, scrollToBottom } = renderFeed();
    scrollToBottom();
    await screen.findByText('Test video 1');
    fireEvent.click(screen.getByText('Profile'));
    fireEvent.click(screen.getByRole('button', { name: 'Liked videos' }));
    const title = await screen.findByRole('button', { name: 'Test video 1' });

    vi.useFakeTimers();
    fireEvent.click(title);
    const card = container.querySelector('#video-video000001')!;
    expect(card.getAttribute('data-highlighted')).toBe('true');
    act(() => {
      vi.advanceTimersByTime(3000);
    });
    expect(card.hasAttribute('data-highlighted')).toBe(false);
  });

  it("doesn't jump back to the opened video when returning to the feed later", async () => {
    const scrolledTo = trackScrollIntoView();
    vi.mocked(fetchMyVideoFeedback).mockResolvedValue({
      likedVideos: [{ youtubeId: 'video000001', title: 'Test video 1', creator: 'Test Channel' }],
      dislikedVideos: [],
    });
    vi.mocked(fetchFeed).mockResolvedValue({ videos: [makeVideo(1)], nextPage: null, fetching: false });
    const { scrollToBottom } = renderFeed();
    scrollToBottom();
    await screen.findByText('Test video 1');
    await openFromYourVideos('Test video 1');
    expect(scrolledTo).toHaveLength(1);

    fireEvent.click(screen.getByText('Profile'));
    fireEvent.click(screen.getByRole('button', { name: 'Leaderboard' }));
    fireEvent.click(screen.getByRole('button', { name: 'Back to feed' }));
    expect(scrolledTo).toHaveLength(1);
  });

  it('scrolls back to the first video when the BrainFeed logo is clicked on the feed', () => {
    vi.mocked(fetchFeed).mockResolvedValue({ videos: [], nextPage: null, fetching: false });
    const { container } = renderFeed();
    const scrollTo = vi.fn();
    Object.defineProperty(container.querySelector('.feed-list')!, 'scrollTo', { value: scrollTo });

    fireEvent.click(screen.getByRole('button', { name: 'BrainFeed, go to your feed' }));
    expect(scrollTo).toHaveBeenCalledWith({ top: 0, behavior: 'smooth' });
  });

  it('comes back to the feed when the BrainFeed logo is clicked on another page', () => {
    vi.mocked(fetchFeed).mockResolvedValue({ videos: [], nextPage: null, fetching: false });
    renderFeed();
    fireEvent.click(screen.getByText('Profile'));
    fireEvent.click(screen.getByRole('button', { name: 'Leaderboard' }));
    expect(screen.getByRole('heading', { name: 'Leaderboard' })).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'BrainFeed, go to your feed' }));
    expect(screen.queryByRole('heading', { name: 'Leaderboard' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Scroll down' })).toBeTruthy();
  });

  it('shows the signed-in user liked and disliked videos and returns to the feed', async () => {
    vi.mocked(fetchMyVideoFeedback).mockResolvedValue({
      likedVideos: [{ youtubeId: 'video000001', title: 'Liked title', creator: 'Liked creator' }],
      dislikedVideos: [{ youtubeId: 'video000002', title: 'Disliked title', creator: 'Disliked creator' }],
    });
    vi.mocked(fetchFeed).mockResolvedValue({ videos: [], nextPage: null, fetching: false });
    renderFeed();

    fireEvent.click(screen.getByText('Profile'));
    fireEvent.click(screen.getByRole('button', { name: 'Liked videos' }));

    expect(screen.getByRole('heading', { name: 'Your videos' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Disliked videos' })).toBeTruthy();
    expect(await screen.findByText('Liked title')).toBeTruthy();
    expect(screen.getByText('Liked creator')).toBeTruthy();
    expect(screen.getByText('Disliked title')).toBeTruthy();
    expect(screen.getByText('Disliked creator')).toBeTruthy();
    expect(screen.getAllByRole('columnheader').map((header) => header.textContent)).toEqual([
      'Title',
      'Creator',
      'Title',
      'Creator',
    ]);

    fireEvent.click(screen.getByRole('button', { name: 'Back to feed' }));
    expect(screen.getByText('Profile')).toBeTruthy();
  });

  it('does not request or reveal saved videos to a guest', () => {
    vi.mocked(fetchFeed).mockResolvedValue({ videos: [], nextPage: null, fetching: false });
    renderFeed(null);

    fireEvent.click(screen.getByText('Profile'));
    fireEvent.click(screen.getByRole('button', { name: 'Liked videos' }));

    expect(screen.getAllByText('Sign in to view saved videos.')).toHaveLength(2);
    expect(fetchMyVideoFeedback).not.toHaveBeenCalled();
  });

  it('shows Guest and a log in action in the profile menu for guests', () => {
    vi.mocked(fetchFeed).mockResolvedValue({ videos: [], nextPage: null, fetching: false });
    renderFeed(null);
    fireEvent.click(screen.getByText('Profile'));
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
