// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { fetchLeaderboard, type Leaderboard } from '../api/leaderboard';
import LeaderboardPage from './LeaderboardPage';

vi.mock('../api/leaderboard', () => ({ fetchLeaderboard: vi.fn() }));

const testUser = { id: 1, email: 'test@sru.edu', username: 'testuser', createdAt: '2026-10-05T00:00:00Z', admin: false };
const handlers = {
  onBack: vi.fn(),
  onEditInterests: vi.fn(),
  onShowLikedVideos: vi.fn(),
  onShowLeaderboard: vi.fn(),
  onLogout: vi.fn(),
};

const board: Leaderboard = {
  weekStart: '2026-11-09T00:00:00Z',
  week: {
    top: [
      { rank: 1, username: 'amy', points: 30, correct: 3, streak: 2 },
      { rank: 2, username: 'testuser', points: 10, correct: 1, streak: 1 },
      { rank: 2, username: 'zed', points: 10, correct: 1, streak: 0 },
    ],
    you: { rank: 2, username: 'testuser', points: 10, correct: 1, streak: 1 },
  },
  allTime: {
    top: [{ rank: 1, username: 'oldtimer', points: 500, correct: 50, streak: 0 }],
    you: { rank: 34, username: 'testuser', points: 40, correct: 4, streak: 1 },
  },
};

beforeEach(() => vi.mocked(fetchLeaderboard).mockResolvedValue(board));
afterEach(() => {
  cleanup();
  vi.resetAllMocks();
});

function rows() {
  return within(screen.getByRole('region', { name: 'Rankings' }))
    .getAllByRole('row')
    .slice(1)
    .map((row) => [...row.querySelectorAll('td')].map((cell) => cell.textContent));
}

describe('LeaderboardPage', () => {
  it('shows this week by default, with ties sharing a rank', async () => {
    render(<LeaderboardPage user={testUser} {...handlers} />);
    expect(screen.getByRole('heading', { name: 'Leaderboard' })).toBeTruthy();
    expect(await screen.findByText('amy')).toBeTruthy();
    expect(rows()).toEqual([
      ['#1', 'amy', '30', '3', '2 days'],
      ['#2', 'testuser (you)', '10', '1', '1 day'],
      ['#2', 'zed', '10', '1', '-'],
    ]);
  });

  it('switches to all time and shows your rank outside the top 20', async () => {
    render(<LeaderboardPage user={testUser} {...handlers} />);
    await screen.findByText('amy');
    fireEvent.click(screen.getByRole('button', { name: 'All time' }));
    expect(screen.getByText('oldtimer')).toBeTruthy();
    expect(screen.queryByText('amy')).toBeNull();
    const you = screen.getByRole('region', { name: 'Your rank' });
    expect(you.textContent).toContain('#34');
    expect(you.textContent).toContain('40');
    expect(screen.getByRole('button', { name: 'All time' }).getAttribute('aria-pressed')).toBe('true');
  });

  it('says when nobody has points yet and you are not ranked', async () => {
    vi.mocked(fetchLeaderboard).mockResolvedValue({
      ...board,
      week: { top: [], you: { rank: null, username: 'testuser', points: 0, correct: 0, streak: 0 } },
    });
    render(<LeaderboardPage user={testUser} {...handlers} />);
    expect(await screen.findByText('Nobody has points this week')).toBeTruthy();
    expect(screen.getByRole('region', { name: 'Your rank' }).textContent).toContain('Not ranked');
  });

  it('asks guests to log in instead of showing a rank', async () => {
    vi.mocked(fetchLeaderboard).mockResolvedValue({ ...board, week: { ...board.week, you: null } });
    render(<LeaderboardPage user={null} {...handlers} />);
    expect(await screen.findByText(/Log in to earn points/)).toBeTruthy();
    expect(screen.queryByText(/\(you\)/)).toBeNull();
  });

  it('shows a loading message first', () => {
    vi.mocked(fetchLeaderboard).mockReturnValue(new Promise(() => {}));
    render(<LeaderboardPage user={testUser} {...handlers} />);
    expect(screen.getByRole('status').textContent).toContain('Loading the leaderboard');
    expect((screen.getByRole('button', { name: 'Update leaderboard' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('shows an error when it can not load, and Update tries again', async () => {
    vi.mocked(fetchLeaderboard).mockRejectedValueOnce(new ApiError(0, 'offline'));
    render(<LeaderboardPage user={testUser} {...handlers} />);
    expect((await screen.findByRole('alert')).textContent).toContain("Couldn't load the leaderboard");
    fireEvent.click(screen.getByRole('button', { name: 'Update leaderboard' }));
    expect(await screen.findByText('amy')).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('shows the server message when refreshing too often', async () => {
    vi.mocked(fetchLeaderboard).mockRejectedValue(new ApiError(429, 'Too many requests. Wait a minute and try again.'));
    render(<LeaderboardPage user={testUser} {...handlers} />);
    expect((await screen.findByRole('alert')).textContent).toBe('Too many requests. Wait a minute and try again.');
  });

  it('Update leaderboard loads the latest scores', async () => {
    render(<LeaderboardPage user={testUser} {...handlers} />);
    await screen.findByText('amy');
    vi.mocked(fetchLeaderboard).mockResolvedValue({
      ...board,
      week: { ...board.week, top: [{ rank: 1, username: 'newcomer', points: 90, correct: 9, streak: 4 }] },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Update leaderboard' }));
    expect(await screen.findByText('newcomer')).toBeTruthy();
    expect(fetchLeaderboard).toHaveBeenCalledTimes(2);
  });

  it('shows usernames as plain text', async () => {
    const sneaky = '<img src=x onerror=alert(1)>';
    vi.mocked(fetchLeaderboard).mockResolvedValue({
      ...board,
      week: { top: [{ rank: 1, username: sneaky, points: 10, correct: 1, streak: 1 }], you: null },
    });
    render(<LeaderboardPage user={testUser} {...handlers} />);
    expect(await screen.findByText(sneaky)).toBeTruthy();
    // the header has a logo image, so only look inside the table
    expect(screen.getByRole('region', { name: 'Rankings' }).querySelector('img')).toBeNull();
  });

  it('never says coming soon or shows the old plan', async () => {
    render(<LeaderboardPage user={testUser} {...handlers} />);
    await screen.findByText('amy');
    expect(document.body.textContent).not.toMatch(/coming soon|will work|once quizzes/i);
  });

  it('goes back to the feed', () => {
    render(<LeaderboardPage user={testUser} {...handlers} />);
    fireEvent.click(screen.getByRole('button', { name: 'Back to feed' }));
    expect(handlers.onBack).toHaveBeenCalledTimes(1);
  });

  it('keeps the profile menu so the user can go somewhere else', () => {
    render(<LeaderboardPage user={testUser} {...handlers} />);
    fireEvent.click(screen.getByText('Profile'));
    fireEvent.click(screen.getByRole('button', { name: 'Your videos' }));
    expect(handlers.onShowLikedVideos).toHaveBeenCalledTimes(1);
  });

  it('offers the Admin page to admins from the profile menu', () => {
    const onShowAdmin = vi.fn();
    render(<LeaderboardPage user={{ ...testUser, admin: true }} {...handlers} onShowAdmin={onShowAdmin} />);
    fireEvent.click(screen.getByText('Profile'));
    fireEvent.click(screen.getByRole('button', { name: 'Admin' }));
    expect(onShowAdmin).toHaveBeenCalledTimes(1);
  });
});
