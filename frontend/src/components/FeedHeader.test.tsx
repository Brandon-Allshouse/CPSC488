// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import FeedHeader from './FeedHeader';

const testUser = { id: 1, email: 'test@sru.edu', username: 'testuser', createdAt: '2026-10-05T00:00:00Z', admin: false };
const handlers = {
  onGoHome: vi.fn(),
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
    fireEvent.click(screen.getByRole('button', { name: 'Liked videos' }));
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

  it('closes the menu with Escape or a click outside it, but not a click inside it', () => {
    const { container } = render(<FeedHeader user={testUser} {...handlers} />);
    const menu = container.querySelector('details')!;

    menu.open = true;
    fireEvent.keyDown(document.body, { key: 'Enter' });
    expect(menu.open).toBe(true);
    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(menu.open).toBe(false);

    menu.open = true;
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Interests' }));
    expect(menu.open).toBe(true);
    fireEvent.pointerDown(document.body);
    expect(menu.open).toBe(false);
  });

  it('closes the menu after an item is picked', () => {
    const { container } = render(<FeedHeader user={testUser} {...handlers} />);
    const menu = container.querySelector('details')!;
    menu.open = true;

    fireEvent.click(screen.getByRole('button', { name: 'Leaderboard' }));
    expect(menu.open).toBe(false);
    expect(handlers.onShowLeaderboard).toHaveBeenCalledTimes(1);
  });

  it('stops listening for Escape and outside clicks once it is gone', () => {
    const removeListener = vi.spyOn(document, 'removeEventListener');
    const { unmount } = render(<FeedHeader user={testUser} {...handlers} />);
    unmount();
    expect(removeListener).toHaveBeenCalledWith('keydown', expect.any(Function));
    expect(removeListener).toHaveBeenCalledWith('pointerdown', expect.any(Function));
    removeListener.mockRestore();
  });

  it('goes to the feed when the BrainFeed logo or name is clicked', () => {
    render(<FeedHeader user={testUser} {...handlers} />);
    const home = screen.getByRole('button', { name: 'BrainFeed, go to your feed' });
    // the logo is inside the same button, so clicking either one works
    expect(home.querySelector('img.feed-brand-logo')).toBeTruthy();
    expect(home.textContent).toBe('BrainFeed');

    fireEvent.click(home);
    expect(handlers.onGoHome).toHaveBeenCalledTimes(1);
  });

  it('makes the logo a real button, so it works from the keyboard too', () => {
    render(<FeedHeader user={null} {...handlers} />);
    const home = screen.getByRole('button', { name: 'BrainFeed, go to your feed' });
    expect(home.tagName).toBe('BUTTON');
    expect(home.getAttribute('type')).toBe('button');
  });
});
