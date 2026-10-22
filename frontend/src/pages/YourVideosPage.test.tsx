// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { fetchMyVideoFeedback } from '../api/feed';
import type { Playlist } from '../api/playlists';
import { fetchSavedVideos } from '../api/savedVideos';
import { fetchStudyNotes } from '../api/studyNotes';
import YourVideosPage from './YourVideosPage';

vi.mock('../api/feed', () => ({ fetchMyVideoFeedback: vi.fn() }));
vi.mock('../api/savedVideos', () => ({ fetchSavedVideos: vi.fn() }));
vi.mock('../api/studyNotes', () => ({ fetchStudyNotes: vi.fn(), saveStudyNote: vi.fn() }));

const testUser = { id: 1, email: 'test@sru.edu', username: 'testuser', createdAt: '2026-10-05T00:00:00Z', admin: false };
const handlers = {
  onBack: vi.fn(),
  onEditInterests: vi.fn(),
  onShowLikedVideos: vi.fn(),
  onShowLeaderboard: vi.fn(),
  onLogout: vi.fn(),
  onOpenVideo: vi.fn(),
  onCreatePlaylist: vi.fn(),
  onDeletePlaylist: vi.fn(),
  onRemoveFromPlaylist: vi.fn(),
  onRemoveNotebookVideo: vi.fn(),
};

const savedVideo = { youtubeId: 'video000003', title: 'Saved title', creator: 'Saved creator' };
const testPlaylists: Playlist[] = [
  { id: 1, name: 'Watch later', watchLater: true, videos: [savedVideo] },
  { id: 2, name: 'Exam prep', watchLater: false, videos: [] },
];

function renderPage(user = testUser as typeof testUser | null, playlists: Playlist[] | null = testPlaylists) {
  return render(<YourVideosPage user={user} {...handlers} playlists={playlists} />);
}

function openTab(name: RegExp) {
  fireEvent.click(screen.getByRole('tab', { name }));
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(fetchMyVideoFeedback).mockReset().mockResolvedValue({ likedVideos: [], dislikedVideos: [] });
  vi.mocked(fetchSavedVideos).mockReset().mockResolvedValue([]);
  vi.mocked(fetchStudyNotes).mockReset().mockResolvedValue([]);
  handlers.onRemoveNotebookVideo.mockReset().mockResolvedValue(undefined);
  handlers.onCreatePlaylist.mockResolvedValue(undefined);
  handlers.onDeletePlaylist.mockResolvedValue(undefined);
  handlers.onRemoveFromPlaylist.mockResolvedValue(undefined);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('YourVideosPage', () => {
  it('shows three tabs and starts on Saved', async () => {
    vi.mocked(fetchMyVideoFeedback).mockResolvedValue({ likedVideos: [], dislikedVideos: [] });
    renderPage();

    expect(screen.getAllByRole('tab').map((tab) => tab.textContent?.replace(/\s*\(\d+\)/, ''))).toEqual([
      'Saved',
      'Liked',
      'Disliked',
    ]);
    expect(screen.getByRole('tab', { name: /^Saved/ }).getAttribute('aria-selected')).toBe('true');
    expect(await screen.findByRole('heading', { name: 'Watch later' })).toBeTruthy();
  });

  // ---- Saved (playlists) ----

  it('lists each playlist with its videos, Watch later first', () => {
    vi.mocked(fetchMyVideoFeedback).mockResolvedValue({ likedVideos: [], dislikedVideos: [] });
    renderPage();

    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual(['Study notebook', 'Watch later', 'Exam prep']);
    expect(screen.getByText('Saved title')).toBeTruthy();
    expect(screen.getByText('This playlist is empty.')).toBeTruthy();
  });

  it('shows a loading message while the playlists load', () => {
    vi.mocked(fetchMyVideoFeedback).mockResolvedValue({ likedVideos: [], dislikedVideos: [] });
    renderPage(testUser, null);
    expect(screen.getByText('Loading playlists…')).toBeTruthy();
  });

  it("shows an error instead of loading forever when the playlists couldn't load", () => {
    vi.mocked(fetchMyVideoFeedback).mockResolvedValue({ likedVideos: [], dislikedVideos: [] });
    render(<YourVideosPage user={testUser} {...handlers} playlists={null} playlistsError="Server error" />);
    expect(screen.getByRole('alert').textContent).toBe('Server error');
    expect(screen.queryByText('Loading playlists…')).toBeNull();
  });

  it('creates a playlist from the name box', async () => {
    vi.mocked(fetchMyVideoFeedback).mockResolvedValue({ likedVideos: [], dislikedVideos: [] });
    renderPage();

    fireEvent.change(screen.getByRole('textbox', { name: 'New playlist name' }), { target: { value: '  Chemistry  ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create playlist' }));

    await vi.waitFor(() => expect(handlers.onCreatePlaylist).toHaveBeenCalledWith('Chemistry'));
    await vi.waitFor(() =>
      expect((screen.getByRole('textbox', { name: 'New playlist name' }) as HTMLInputElement).value).toBe(''),
    );
  });

  it('shows the error when a playlist name is already used', async () => {
    vi.mocked(fetchMyVideoFeedback).mockResolvedValue({ likedVideos: [], dislikedVideos: [] });
    handlers.onCreatePlaylist.mockRejectedValue(new ApiError(409, 'You already have a playlist with that name.'));
    renderPage();

    fireEvent.change(screen.getByRole('textbox', { name: 'New playlist name' }), { target: { value: 'Exam prep' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create playlist' }));

    expect((await screen.findByRole('alert')).textContent).toContain('You already have a playlist with that name.');
  });

  it('removes a video from a playlist', async () => {
    vi.mocked(fetchMyVideoFeedback).mockResolvedValue({ likedVideos: [], dislikedVideos: [] });
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Remove Saved title from Watch later' }));
    await vi.waitFor(() => expect(handlers.onRemoveFromPlaylist).toHaveBeenCalledWith(1, 'video000003'));
  });

  it('deletes a playlist after asking, but never Watch later', async () => {
    vi.mocked(fetchMyVideoFeedback).mockResolvedValue({ likedVideos: [], dislikedVideos: [] });
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    renderPage();

    expect(screen.queryByRole('button', { name: 'Delete playlist Watch later' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Delete playlist Exam prep' }));
    await vi.waitFor(() => expect(handlers.onDeletePlaylist).toHaveBeenCalledWith(2));
  });

  it("doesn't delete a playlist when the user cancels", () => {
    vi.mocked(fetchMyVideoFeedback).mockResolvedValue({ likedVideos: [], dislikedVideos: [] });
    vi.spyOn(window, 'confirm').mockReturnValue(false);
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Delete playlist Exam prep' }));
    expect(handlers.onDeletePlaylist).not.toHaveBeenCalled();
  });

  it('opens a saved video in the feed when its title is clicked', () => {
    vi.mocked(fetchMyVideoFeedback).mockResolvedValue({ likedVideos: [], dislikedVideos: [] });
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: 'Saved title' }));
    expect(handlers.onOpenVideo).toHaveBeenLastCalledWith(savedVideo);
  });

  it('shows playlist names with HTML in them as plain text', () => {
    vi.mocked(fetchMyVideoFeedback).mockResolvedValue({ likedVideos: [], dislikedVideos: [] });
    const { container } = renderPage(testUser, [{ id: 5, name: '<b>bold?</b>', watchLater: false, videos: [] }]);
    expect(screen.getByRole('heading', { name: '<b>bold?</b>' })).toBeTruthy();
    expect(container.querySelector('b')).toBeNull();
  });

  // ---- Liked / Disliked (her original tests, now behind tabs) ----

  it('shows a loading message until the lists arrive', async () => {
    vi.mocked(fetchMyVideoFeedback).mockResolvedValue({
      likedVideos: [{ youtubeId: 'video000001', title: 'Liked title', creator: 'Liked creator' }],
      dislikedVideos: [],
    });
    renderPage();
    openTab(/^Liked/);

    expect(screen.getByText('Loading saved videos…')).toBeTruthy();
    expect(await screen.findByText('Liked title')).toBeTruthy();
    expect(screen.queryByText('Loading saved videos…')).toBeNull();
  });

  it('shows empty messages when nothing has been rated yet', async () => {
    vi.mocked(fetchMyVideoFeedback).mockResolvedValue({ likedVideos: [], dislikedVideos: [] });
    renderPage();

    openTab(/^Liked/);
    expect(await screen.findByText('No liked videos yet.')).toBeTruthy();
    openTab(/^Disliked/);
    expect(screen.getByText('No disliked videos yet.')).toBeTruthy();
  });

  it('shows titles and creators with HTML in them as plain text', async () => {
    // these come from YouTube, so they should never be treated as HTML
    vi.mocked(fetchMyVideoFeedback).mockResolvedValue({
      likedVideos: [{ youtubeId: 'video000001', title: '<b>not bold</b>', creator: '<i>Test Channel</i>' }],
      dislikedVideos: [],
    });
    const { container } = renderPage();
    openTab(/^Liked/);

    expect(await screen.findByText('<b>not bold</b>')).toBeTruthy();
    expect(screen.getByText('<i>Test Channel</i>')).toBeTruthy();
    expect(container.querySelector('b')).toBeNull();
    expect(container.querySelector('i')).toBeNull();
  });

  it('shows an error if the lists could not be loaded', async () => {
    vi.mocked(fetchMyVideoFeedback).mockRejectedValue(new ApiError(500, 'Something went wrong on our end.'));
    renderPage();
    openTab(/^Liked/);

    expect((await screen.findByRole('alert')).textContent).toContain('Something went wrong on our end.');
    expect(handlers.onLogout).not.toHaveBeenCalled();
  });

  it('sends the user to log in again when their session has expired', async () => {
    vi.mocked(fetchMyVideoFeedback).mockRejectedValue(new ApiError(401, 'Not logged in.'));
    renderPage();

    await vi.waitFor(() => expect(handlers.onLogout).toHaveBeenCalledTimes(1));
  });

  it('does not ask the backend for anything when a guest opens it', () => {
    renderPage(null, null);

    expect(screen.getByText('Sign in to see your saved videos.')).toBeTruthy();
    openTab(/^Liked/);
    expect(screen.getByText('Sign in to see your liked videos.')).toBeTruthy();
    openTab(/^Disliked/);
    expect(screen.getByText('Sign in to see your disliked videos.')).toBeTruthy();
    expect(fetchMyVideoFeedback).not.toHaveBeenCalled();
    expect(fetchSavedVideos).not.toHaveBeenCalled();
    expect(fetchStudyNotes).not.toHaveBeenCalled();
  });

  it('goes back to the feed', async () => {
    vi.mocked(fetchMyVideoFeedback).mockResolvedValue({ likedVideos: [], dislikedVideos: [] });
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Back to feed' }));
    expect(handlers.onBack).toHaveBeenCalledTimes(1);
  });

  it('offers the Admin page to admins from the profile menu', () => {
    vi.mocked(fetchMyVideoFeedback).mockResolvedValue({ likedVideos: [], dislikedVideos: [] });
    const onShowAdmin = vi.fn();
    render(
      <YourVideosPage user={{ ...testUser, admin: true }} {...handlers} playlists={testPlaylists} onShowAdmin={onShowAdmin} />,
    );
    fireEvent.click(screen.getByText('Profile'));
    fireEvent.click(screen.getByRole('button', { name: 'Admin' }));
    expect(onShowAdmin).toHaveBeenCalledTimes(1);
  });

  it('opens a liked or disliked video in the feed when its title is clicked', async () => {
    const liked = { youtubeId: 'video000001', title: 'Liked title', creator: 'Liked creator' };
    const disliked = { youtubeId: 'video000002', title: 'Disliked title', creator: 'Disliked creator' };
    vi.mocked(fetchMyVideoFeedback).mockResolvedValue({ likedVideos: [liked], dislikedVideos: [disliked] });
    renderPage();

    openTab(/^Liked/);
    fireEvent.click(await screen.findByRole('button', { name: 'Liked title' }));
    expect(handlers.onOpenVideo).toHaveBeenLastCalledWith(liked);
    openTab(/^Disliked/);
    fireEvent.click(screen.getByRole('button', { name: 'Disliked title' }));
    expect(handlers.onOpenVideo).toHaveBeenLastCalledWith(disliked);
  });

  it("doesn't make the creator clickable", async () => {
    vi.mocked(fetchMyVideoFeedback).mockResolvedValue({
      likedVideos: [{ youtubeId: 'video000001', title: 'Liked title', creator: 'Liked creator' }],
      dislikedVideos: [],
    });
    renderPage();
    openTab(/^Liked/);
    await screen.findByRole('button', { name: 'Liked title' });
    expect(screen.queryByRole('button', { name: 'Liked creator' })).toBeNull();
  });

  it('goes to the feed from the BrainFeed logo', () => {
    vi.mocked(fetchMyVideoFeedback).mockResolvedValue({ likedVideos: [], dislikedVideos: [] });
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: 'BrainFeed, go to your feed' }));
    expect(handlers.onBack).toHaveBeenCalledTimes(1);
  });

  // ---- Watch history (its own page) ----

  it('links to the Watch history page', () => {
    vi.mocked(fetchMyVideoFeedback).mockResolvedValue({ likedVideos: [], dislikedVideos: [] });
    const onShowHistory = vi.fn();
    render(<YourVideosPage user={testUser} {...handlers} playlists={testPlaylists} onShowHistory={onShowHistory} />);
    // the Profile menu has a "Watch history" item too; this is the button on the page itself
    const pageButton = screen.getAllByRole('button', { name: 'Watch history' }).find((b) => !b.closest('header'))!;
    fireEvent.click(pageButton);
    expect(onShowHistory).toHaveBeenCalledTimes(1);
  });

  it('shows existing notebook videos alongside playlists in the Saved tab', async () => {
    vi.mocked(fetchSavedVideos).mockResolvedValue([
      { youtubeId: 'video000004', title: 'Notebook lesson', channelTitle: 'Study channel', topicId: 1, publishedAt: '2026-10-01T00:00:00Z' },
    ]);
    renderPage();
    const notebook = screen.getByRole('region', { name: 'Study notebook' });
    expect(await within(notebook).findByRole('button', { name: 'Notebook lesson' })).toBeTruthy();
    expect(screen.getByRole('region', { name: 'Watch later' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Your videos' })).toBeTruthy();
  });

  it('opens a notebook video using the existing watch-in-feed action', async () => {
    vi.mocked(fetchSavedVideos).mockResolvedValue([
      { youtubeId: 'video000004', title: 'Notebook lesson', channelTitle: 'Study channel', topicId: 1, publishedAt: '2026-10-01T00:00:00Z' },
    ]);
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Notebook lesson' }));
    expect(handlers.onOpenVideo).toHaveBeenCalledWith({ youtubeId: 'video000004', title: 'Notebook lesson', creator: 'Study channel' });
  });

  it('removes a notebook entry without changing playlist membership', async () => {
    vi.mocked(fetchSavedVideos).mockResolvedValue([
      { youtubeId: savedVideo.youtubeId, title: savedVideo.title, channelTitle: savedVideo.creator, topicId: 1, publishedAt: '2026-10-01T00:00:00Z' },
    ]);
    renderPage();
    const notebook = screen.getByRole('region', { name: 'Study notebook' });
    const remove = await within(notebook).findByRole<HTMLButtonElement>('button', { name: 'Remove Saved title from Study notebook' });
    await waitFor(() => expect(remove.disabled).toBe(false));
    fireEvent.click(remove);
    await waitFor(() => expect(within(notebook).queryByRole('button', { name: 'Saved title' })).toBeNull());
    expect(handlers.onRemoveNotebookVideo).toHaveBeenCalledWith(savedVideo.youtubeId);
    expect(within(screen.getByRole('region', { name: 'Watch later' })).getByRole('button', { name: 'Saved title' })).toBeTruthy();
    expect(handlers.onRemoveFromPlaylist).not.toHaveBeenCalled();
  });

  it('keeps a notebook entry visible when removing it fails', async () => {
    vi.mocked(fetchSavedVideos).mockResolvedValue([
      { youtubeId: 'video000004', title: 'Notebook lesson', channelTitle: 'Study channel', topicId: 1, publishedAt: '2026-10-01T00:00:00Z' },
    ]);
    handlers.onRemoveNotebookVideo.mockRejectedValue(new Error('Could not remove this entry.'));
    renderPage();
    const remove = await screen.findByRole<HTMLButtonElement>('button', { name: 'Remove Notebook lesson from Study notebook' });
    await waitFor(() => expect(remove.disabled).toBe(false));
    fireEvent.click(remove);
    expect((await screen.findByRole('alert')).textContent).toBe('Could not remove this entry.');
    expect(screen.getByRole('button', { name: 'Notebook lesson' })).toBeTruthy();
  });

  it('keeps playlists visible when loading the notebook fails', async () => {
    vi.mocked(fetchSavedVideos).mockRejectedValue(new Error('Study collection unavailable.'));
    renderPage();
    expect((await screen.findByRole('alert')).textContent).toBe('Study collection unavailable.');
    expect(within(screen.getByRole('region', { name: 'Watch later' })).getByRole('button', { name: 'Saved title' })).toBeTruthy();
  });

  it('counts a saved video once when it is in both the notebook and a playlist', async () => {
    vi.mocked(fetchSavedVideos).mockResolvedValue([
      { youtubeId: savedVideo.youtubeId, title: savedVideo.title, channelTitle: savedVideo.creator, topicId: 1, publishedAt: '2026-10-01T00:00:00Z' },
    ]);
    renderPage();
    await within(screen.getByRole('region', { name: 'Study notebook' })).findByRole('button', { name: 'Saved title' });
    expect(screen.getByRole('tab', { name: /^Saved/ }).textContent).toBe('Saved(1)');
  });


  it('keeps an unfinished notebook note when the user cancels changing tabs', async () => {
    vi.mocked(fetchSavedVideos).mockResolvedValue([
      { youtubeId: 'video000004', title: 'Notebook lesson', channelTitle: 'Study channel', topicId: 1, publishedAt: '2026-10-01T00:00:00Z' },
    ]);
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    renderPage();
    const edit = await screen.findByRole<HTMLButtonElement>('button', { name: 'Edit note for Notebook lesson' });
    await waitFor(() => expect(edit.disabled).toBe(false));
    fireEvent.click(edit);
    await act(async () => {
      fireEvent.change(screen.getByRole('textbox', { name: 'Personal study notes' }), { target: { value: 'Finish these notes later.' } });
    });
    await waitFor(() => expect(screen.getByText('Unsaved changes')).toBeTruthy());
    openTab(/^Liked/);
    expect(confirm).toHaveBeenCalled();
    expect(screen.getByRole('tab', { name: /^Saved/ }).getAttribute('aria-selected')).toBe('true');
    expect(screen.getByRole<HTMLTextAreaElement>('textbox', { name: 'Personal study notes' }).value).toBe('Finish these notes later.');
  });

});
