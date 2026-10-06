// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchCurrentUser, logout } from './api/auth';
import { fetchFeed, fetchMyInterests, fetchTopics, saveMyInterests } from './api/feed';
import App from './App';
import { useFakeIntersectionObserver } from './test/fakeIntersectionObserver';

// Tests which page shows up for logged in users, guests and logged out visitors.

vi.mock('./api/auth', () => ({
  fetchCurrentUser: vi.fn(),
  logout: vi.fn(),
  login: vi.fn(),
  register: vi.fn(),
}));
vi.mock('./api/feed', () => ({
  fetchTopics: vi.fn(),
  fetchMyInterests: vi.fn(),
  saveMyInterests: vi.fn(),
  fetchFeed: vi.fn(),
}));

const testUser = { id: 1, email: 'test@sru.edu', username: 'testuser', createdAt: '2026-10-05T00:00:00Z' };

beforeEach(() => {
  vi.clearAllMocks();
  useFakeIntersectionObserver();
  vi.mocked(fetchTopics).mockResolvedValue([
    { id: 1, name: 'Math', parentId: null },
    { id: 2, name: 'History', parentId: null },
  ]);
  vi.mocked(fetchFeed).mockResolvedValue({ videos: [], nextPage: null, fetching: false });
  vi.mocked(logout).mockResolvedValue(undefined);
});

afterEach(() => {
  cleanup();
});

describe('App', () => {
  it('shows the login page when nobody is logged in', async () => {
    vi.mocked(fetchCurrentUser).mockResolvedValue(null);
    render(<App />);
    expect(await screen.findByText('Welcome back')).toBeTruthy();
  });

  it('shows the login page if the backend cannot be reached', async () => {
    vi.mocked(fetchCurrentUser).mockRejectedValue(new Error('down'));
    render(<App />);
    expect(await screen.findByText('Welcome back')).toBeTruthy();
  });

  it('goes straight to the feed for a logged in user with interests', async () => {
    vi.mocked(fetchCurrentUser).mockResolvedValue(testUser);
    vi.mocked(fetchMyInterests).mockResolvedValue([1]);
    render(<App />);
    expect(await screen.findByText('testuser')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Interests' })).toBeTruthy();
  });

  it('asks a new user to pick interests, saves them, then shows the feed', async () => {
    vi.mocked(fetchCurrentUser).mockResolvedValue(testUser);
    vi.mocked(fetchMyInterests).mockResolvedValue([]);
    vi.mocked(saveMyInterests).mockResolvedValue([2]);
    render(<App />);

    fireEvent.click(await screen.findByRole('button', { name: 'All of History' }));
    fireEvent.click(screen.getByRole('button', { name: 'Show my feed' }));

    expect(await screen.findByText('testuser')).toBeTruthy();
    expect(saveMyInterests).toHaveBeenCalledWith([2]);
  });

  it('lets a guest pick interests without saving them to the server', async () => {
    vi.mocked(fetchCurrentUser).mockResolvedValue(null);
    render(<App />);

    fireEvent.click(await screen.findByRole('button', { name: 'Continue as guest' }));
    fireEvent.click(await screen.findByRole('button', { name: 'All of Math' }));
    fireEvent.click(screen.getByRole('button', { name: 'Show my feed' }));

    expect(await screen.findByText('Guest')).toBeTruthy();
    expect(saveMyInterests).not.toHaveBeenCalled();
  });

  it('can edit interests from the feed and cancel', async () => {
    vi.mocked(fetchCurrentUser).mockResolvedValue(testUser);
    vi.mocked(fetchMyInterests).mockResolvedValue([1]);
    render(<App />);

    fireEvent.click(await screen.findByRole('button', { name: 'Interests' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Cancel' }));
    expect(await screen.findByText('testuser')).toBeTruthy();
  });

  it('logging out goes back to the login page', async () => {
    vi.mocked(fetchCurrentUser).mockResolvedValue(testUser);
    vi.mocked(fetchMyInterests).mockResolvedValue([1]);
    render(<App />);

    fireEvent.click(await screen.findByRole('button', { name: 'Log out' }));
    expect(await screen.findByText('Welcome back')).toBeTruthy();
    expect(logout).toHaveBeenCalled();
  });
});
