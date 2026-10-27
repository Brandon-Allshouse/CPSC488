// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import LeaderboardPage from './LeaderboardPage';

const testUser = { id: 1, email: 'test@sru.edu', username: 'testuser', createdAt: '2026-10-05T00:00:00Z', admin: false };
const handlers = {
  onBack: vi.fn(),
  onEditInterests: vi.fn(),
  onShowLikedVideos: vi.fn(),
  onShowLeaderboard: vi.fn(),
  onLogout: vi.fn(),
};

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('LeaderboardPage', () => {
  it('shows the leaderboard heading and empty panel', () => {
    render(<LeaderboardPage user={testUser} {...handlers} />);
    expect(screen.getByRole('heading', { name: 'Leaderboard' })).toBeTruthy();
    expect(screen.getByRole('region', { name: 'Leaderboard information' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Update leaderboard' })).toBeTruthy();
  });

  it('explains how the leaderboard will work, in order', () => {
    render(<LeaderboardPage user={testUser} {...handlers} />);
    // the team doesn't want 'coming soon' wording anywhere in the app
    expect(screen.queryByText(/coming soon/i)).toBeNull();
    expect(screen.getByRole('button', { name: 'Update leaderboard' }).getAttribute('title')).toBeNull();
    const steps = screen.getByRole('list', { name: 'How the leaderboard will work' });
    expect([...steps.querySelectorAll('h2')].map((heading) => heading.textContent)).toEqual([
      'Watch',
      'Quiz yourself',
      'Climb the ranks',
    ]);
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
