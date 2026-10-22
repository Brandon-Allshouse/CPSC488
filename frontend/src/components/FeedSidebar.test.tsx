// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import FeedSidebar from './FeedSidebar';

const testUser = { id: 1, email: 'test@sru.edu', username: 'testuser', createdAt: '2026-10-05T00:00:00Z', admin: false };
const handlers = {
  onGoHome: vi.fn(),
  onEditInterests: vi.fn(),
  onShowLikedVideos: vi.fn(),
  onShowHistory: vi.fn(),
  onShowLeaderboard: vi.fn(),
  onShowAdmin: vi.fn(),
  onLogin: vi.fn(),
};

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('FeedSidebar', () => {
  it('marks Feed as the current page and sends each link to its handler', () => {
    render(<FeedSidebar user={testUser} topicNames={[]} {...handlers} />);

    expect(screen.getByRole('button', { name: 'Feed' }).getAttribute('aria-current')).toBe('page');
    fireEvent.click(screen.getByRole('button', { name: 'Your videos' }));
    fireEvent.click(screen.getByRole('button', { name: 'Watch history' }));
    fireEvent.click(screen.getByRole('button', { name: 'Leaderboard' }));

    expect(handlers.onShowLikedVideos).toHaveBeenCalledTimes(1);
    expect(handlers.onShowHistory).toHaveBeenCalledTimes(1);
    expect(handlers.onShowLeaderboard).toHaveBeenCalledTimes(1);
  });

  it('lists the picked topics and lets the user edit them', () => {
    render(<FeedSidebar user={testUser} topicNames={['Math', 'Algebra 2']} {...handlers} />);

    expect(screen.getByText('Math')).toBeTruthy();
    expect(screen.getByText('Algebra 2')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Edit topics' }));
    expect(handlers.onEditInterests).toHaveBeenCalledTimes(1);
  });

  it('shows the Admin link only to admins', () => {
    const { rerender } = render(<FeedSidebar user={testUser} topicNames={[]} {...handlers} />);
    expect(screen.queryByRole('button', { name: 'Admin' })).toBeNull();

    rerender(<FeedSidebar user={{ ...testUser, admin: true }} topicNames={[]} {...handlers} />);
    fireEvent.click(screen.getByRole('button', { name: 'Admin' }));
    expect(handlers.onShowAdmin).toHaveBeenCalledTimes(1);
  });

  it('asks guests to log in, and not signed-in users', () => {
    const { rerender } = render(<FeedSidebar user={null} topicNames={[]} {...handlers} />);
    fireEvent.click(screen.getByRole('button', { name: 'Log in or sign up' }));
    expect(handlers.onLogin).toHaveBeenCalledTimes(1);

    rerender(<FeedSidebar user={testUser} topicNames={[]} {...handlers} />);
    expect(screen.queryByRole('button', { name: 'Log in or sign up' })).toBeNull();
  });
});
