// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { fetchMyVideoFeedback } from '../api/feed';
import LikedVideosPage from './LikedVideosPage';

vi.mock('../api/feed', () => ({ fetchMyVideoFeedback: vi.fn() }));

const testUser = { id: 1, email: 'test@sru.edu', username: 'testuser', createdAt: '2026-10-05T00:00:00Z' };
const handlers = {
  onBack: vi.fn(),
  onEditInterests: vi.fn(),
  onShowLikedVideos: vi.fn(),
  onShowLeaderboard: vi.fn(),
  onLogout: vi.fn(),
};

function renderPage(user = testUser as typeof testUser | null) {
  return render(<LikedVideosPage user={user} {...handlers} />);
}

beforeEach(() => {
  vi.clearAllMocks();
});

afterEach(() => {
  cleanup();
});

describe('LikedVideosPage', () => {
  it('shows a loading message until the lists arrive', async () => {
    vi.mocked(fetchMyVideoFeedback).mockResolvedValue({
      likedVideos: [{ youtubeId: 'video000001', title: 'Liked title', creator: 'Liked creator' }],
      dislikedVideos: [],
    });
    renderPage();

    expect(screen.getAllByText('Loading saved videos…')).toHaveLength(2);
    expect(await screen.findByText('Liked title')).toBeTruthy();
    expect(screen.queryByText('Loading saved videos…')).toBeNull();
  });

  it('shows empty messages when nothing has been rated yet', async () => {
    vi.mocked(fetchMyVideoFeedback).mockResolvedValue({ likedVideos: [], dislikedVideos: [] });
    renderPage();

    expect(await screen.findByText('No liked videos yet.')).toBeTruthy();
    expect(screen.getByText('No disliked videos yet.')).toBeTruthy();
  });

  it('shows titles and creators with HTML in them as plain text', async () => {
    // these come from YouTube, so they should never be treated as HTML
    vi.mocked(fetchMyVideoFeedback).mockResolvedValue({
      likedVideos: [{ youtubeId: 'video000001', title: '<b>not bold</b>', creator: '<i>Test Channel</i>' }],
      dislikedVideos: [],
    });
    const { container } = renderPage();

    expect(await screen.findByText('<b>not bold</b>')).toBeTruthy();
    expect(screen.getByText('<i>Test Channel</i>')).toBeTruthy();
    expect(container.querySelector('b')).toBeNull();
    expect(container.querySelector('i')).toBeNull();
  });

  it('shows an error if the lists could not be loaded', async () => {
    vi.mocked(fetchMyVideoFeedback).mockRejectedValue(new ApiError(500, 'Something went wrong on our end.'));
    renderPage();

    expect(await screen.findAllByRole('alert')).toHaveLength(2);
    expect(screen.getAllByText('Something went wrong on our end.')).toHaveLength(2);
    expect(handlers.onLogout).not.toHaveBeenCalled();
  });

  it('sends the user to log in again when their session has expired', async () => {
    vi.mocked(fetchMyVideoFeedback).mockRejectedValue(new ApiError(401, 'Not logged in.'));
    renderPage();

    await vi.waitFor(() => expect(handlers.onLogout).toHaveBeenCalledTimes(1));
  });

  it('does not ask the backend for anything when a guest opens it', () => {
    renderPage(null);

    expect(screen.getAllByText('Sign in to view saved videos.')).toHaveLength(2);
    expect(fetchMyVideoFeedback).not.toHaveBeenCalled();
  });

  it('goes back to the feed', async () => {
    vi.mocked(fetchMyVideoFeedback).mockResolvedValue({ likedVideos: [], dislikedVideos: [] });
    renderPage();
    await screen.findByText('No liked videos yet.');

    fireEvent.click(screen.getByRole('button', { name: 'Back to feed' }));
    expect(handlers.onBack).toHaveBeenCalledTimes(1);
  });
});
