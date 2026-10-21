// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import FeedHeader from './FeedHeader';

const testUser = { id: 1, email: 'test@sru.edu', username: 'testuser', createdAt: '2026-10-05T00:00:00Z', admin: false };
const handlers = {
  onEditInterests: vi.fn(),
  onShowLikedVideos: vi.fn(),
  onShowLeaderboard: vi.fn(),
  onLogout: vi.fn(),
};

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('FeedHeader', () => {
  it('shows the username and a sign out action for a signed-in user', () => {
    render(<FeedHeader user={testUser} {...handlers} />);
    fireEvent.click(screen.getByText('Profile'));

    expect(screen.getByText('testuser')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(handlers.onLogout).toHaveBeenCalledTimes(1);
  });

  it('shows Guest and a log in action for guests', () => {
    render(<FeedHeader user={null} {...handlers} />);
    fireEvent.click(screen.getByText('Profile'));

    expect(screen.getByText('Guest')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Log in' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Sign out' })).toBeNull();
  });

  it('sends each menu button to its own handler', () => {
    render(<FeedHeader user={testUser} {...handlers} />);
    fireEvent.click(screen.getByText('Profile'));

    fireEvent.click(screen.getByRole('button', { name: 'Interests' }));
    fireEvent.click(screen.getByRole('button', { name: 'View Liked videos' }));
    fireEvent.click(screen.getByRole('button', { name: 'Leaderboard' }));

    expect(handlers.onEditInterests).toHaveBeenCalledTimes(1);
    expect(handlers.onShowLikedVideos).toHaveBeenCalledTimes(1);
    expect(handlers.onShowLeaderboard).toHaveBeenCalledTimes(1);
    expect(handlers.onLogout).not.toHaveBeenCalled();
  });

  it('shows the Admin item only to admins', () => {
    const onShowAdmin = vi.fn();
    const { rerender } = render(<FeedHeader user={testUser} {...handlers} onShowAdmin={onShowAdmin} />);
    fireEvent.click(screen.getByText('Profile'));
    expect(screen.queryByRole('button', { name: 'Admin' })).toBeNull();

    rerender(<FeedHeader user={{ ...testUser, admin: true }} {...handlers} onShowAdmin={onShowAdmin} />);
    fireEvent.click(screen.getByRole('button', { name: 'Admin' }));
    expect(onShowAdmin).toHaveBeenCalledTimes(1);
  });

  it('never shows the Admin item to guests', () => {
    render(<FeedHeader user={null} {...handlers} onShowAdmin={vi.fn()} />);
    fireEvent.click(screen.getByText('Profile'));
    expect(screen.queryByRole('button', { name: 'Admin' })).toBeNull();
  });
});
