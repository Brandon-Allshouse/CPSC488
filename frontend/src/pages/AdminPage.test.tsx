// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  banUser,
  banVideo,
  fetchAdminActions,
  fetchAdminStats,
  fetchBannedVideos,
  searchUsers,
  searchVideos,
  unbanUser,
  unbanVideo,
  type AdminUser,
} from '../api/admin';
import { ApiError } from '../api/client';
import AdminPage from './AdminPage';

vi.mock('../api/admin', () => ({
  fetchAdminStats: vi.fn(),
  searchUsers: vi.fn(),
  banUser: vi.fn(),
  unbanUser: vi.fn(),
  searchVideos: vi.fn(),
  fetchBannedVideos: vi.fn(),
  banVideo: vi.fn(),
  unbanVideo: vi.fn(),
  fetchAdminActions: vi.fn(),
}));

const adminUser = { id: 1, email: 'admin@sru.edu', username: 'testadmin', createdAt: '2026-10-05T00:00:00Z', admin: true };
const regularUser = { ...adminUser, id: 2, email: 'test@sru.edu', username: 'testuser', admin: false };

function userRow(overrides: Partial<AdminUser> = {}): AdminUser {
  return {
    id: 2,
    username: 'testuser',
    email: 'test@sru.edu',
    createdAt: '2026-10-05T00:00:00Z',
    admin: false,
    bannedAt: null,
    banReason: null,
    signupIp: '192.0.2.10',
    lastLoginIp: '198.51.100.7',
    lastLoginAt: '2026-10-20T12:00:00Z',
    ...overrides,
  };
}

const handlers = {
  onBack: vi.fn(),
  onEditInterests: vi.fn(),
  onShowLikedVideos: vi.fn(),
  onShowLeaderboard: vi.fn(),
  onShowAdmin: vi.fn(),
  onLogout: vi.fn(),
};

function renderPage(user: typeof adminUser | null = adminUser) {
  return render(<AdminPage user={user} {...handlers} />);
}

function usersTable() {
  return screen.getByRole('region', { name: 'Users' });
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(fetchAdminStats).mockResolvedValue({ users: 3, bannedUsers: 0, admins: 1, videos: 12, bannedVideos: 1 });
  vi.mocked(searchUsers).mockResolvedValue([
    userRow({ id: 1, username: 'testadmin', email: 'admin@sru.edu', admin: true }),
    userRow(),
  ]);
  vi.mocked(searchVideos).mockResolvedValue([
    { youtubeId: 'video000001', title: 'How Plants Grow', channelTitle: 'Test Channel', topic: 'Biology' },
  ]);
  vi.mocked(fetchBannedVideos).mockResolvedValue([
    {
      youtubeId: 'video000009',
      title: 'Banned title',
      reason: 'Off-topic',
      bannedBy: 'testadmin',
      bannedAt: '2026-10-06T00:00:00Z',
    },
  ]);
  vi.mocked(fetchAdminActions).mockResolvedValue([
    {
      id: 1,
      admin: 'testadmin',
      action: 'ban_video',
      target: 'video000009',
      reason: 'Off-topic',
      ip: '203.0.113.5',
      createdAt: '2026-10-06T00:00:00Z',
    },
  ]);
  vi.mocked(banUser).mockResolvedValue(undefined);
  vi.mocked(unbanUser).mockResolvedValue(undefined);
  vi.mocked(banVideo).mockResolvedValue(undefined);
  vi.mocked(unbanVideo).mockResolvedValue(undefined);
});

afterEach(() => {
  cleanup();
});

describe('AdminPage', () => {
  it('turns away non-admins without asking the backend for anything', () => {
    renderPage(regularUser);
    expect(screen.getByRole('alert').textContent).toBe('You need admin access to use this page.');
    expect(fetchAdminStats).not.toHaveBeenCalled();
    expect(searchUsers).not.toHaveBeenCalled();
  });

  it('turns away guests too', () => {
    renderPage(null);
    expect(screen.getByRole('alert')).toBeTruthy();
    expect(fetchAdminStats).not.toHaveBeenCalled();
  });

  it('shows the overview, users, videos, banned videos and recent actions', async () => {
    renderPage();
    expect(await screen.findByText('testuser')).toBeTruthy();
    expect(screen.getByText('12')).toBeTruthy();
    expect(screen.getByText('How Plants Grow')).toBeTruthy();
    expect(screen.getAllByText('Off-topic')).toHaveLength(2);
    expect(screen.getByText('Banned video')).toBeTruthy();
  });

  it('asks for a reason before banning a user', async () => {
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Ban testuser' }));

    const confirm = screen.getByRole<HTMLButtonElement>('button', { name: 'Confirm ban' });
    expect(confirm.disabled).toBe(true);
    expect(banUser).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText('Reason for banning testuser'), { target: { value: '  Spamming  ' } });
    fireEvent.click(confirm);

    await vi.waitFor(() => expect(banUser).toHaveBeenCalledWith(2, 'Spamming'));
    expect((await screen.findByRole('status')).textContent).toBe('Banned testuser.');
    // the lists are reloaded so the page shows the new state
    expect(fetchAdminActions).toHaveBeenCalledTimes(2);
  });

  it('lets the admin back out of a ban', async () => {
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Ban testuser' }));
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(screen.queryByRole('button', { name: 'Confirm ban' })).toBeNull();
    expect(banUser).not.toHaveBeenCalled();
  });

  it('never offers to ban admins, including yourself', async () => {
    renderPage();
    await screen.findByText('testuser');
    const adminRow = within(usersTable()).getByText('testadmin').closest('tr')!;
    expect(within(adminRow).queryByRole('button')).toBeNull();
    expect(within(adminRow).getByText('Admin')).toBeTruthy();
  });

  it('shows why a user was banned and can unban them', async () => {
    vi.mocked(searchUsers).mockResolvedValue([userRow({ bannedAt: '2026-10-06T00:00:00Z', banReason: 'Spamming' })]);
    renderPage();

    expect(await screen.findByText('Banned: Spamming')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Unban testuser' }));
    await vi.waitFor(() => expect(unbanUser).toHaveBeenCalledWith(2));
  });

  it('searches users with what was typed', async () => {
    renderPage();
    await screen.findByText('testuser');
    fireEvent.change(screen.getByLabelText('Search users'), { target: { value: 'test' } });
    fireEvent.submit(screen.getByLabelText('Search users').closest('form')!);
    await vi.waitFor(() => expect(searchUsers).toHaveBeenLastCalledWith('test'));
  });

  it('bans a video from the search results', async () => {
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Ban How Plants Grow' }));
    fireEvent.change(screen.getByLabelText('Reason for banning How Plants Grow'), { target: { value: 'Wrong topic' } });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm ban' }));
    await vi.waitFor(() => expect(banVideo).toHaveBeenCalledWith('video000001', 'Wrong topic'));
  });

  it('bans a video from a pasted YouTube link', async () => {
    renderPage();
    await screen.findByText('testuser');
    fireEvent.change(screen.getByLabelText('YouTube link or video id'), {
      target: { value: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ' },
    });
    fireEvent.change(screen.getByLabelText('Reason for banning this link'), { target: { value: 'Not educational' } });
    fireEvent.click(screen.getByRole('button', { name: 'Ban video' }));
    await vi.waitFor(() => expect(banVideo).toHaveBeenCalledWith('dQw4w9WgXcQ', 'Not educational'));
  });

  it("doesn't send a link that isn't a YouTube video", async () => {
    renderPage();
    await screen.findByText('testuser');
    fireEvent.change(screen.getByLabelText('YouTube link or video id'), { target: { value: 'https://evil.example/x' } });
    fireEvent.change(screen.getByLabelText('Reason for banning this link'), { target: { value: 'x' } });
    fireEvent.click(screen.getByRole('button', { name: 'Ban video' }));

    expect((await screen.findByRole('alert')).textContent).toBe('That is not a YouTube video link or id.');
    expect(banVideo).not.toHaveBeenCalled();
  });

  it('unbans a video', async () => {
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Unban Banned title' }));
    await vi.waitFor(() => expect(unbanVideo).toHaveBeenCalledWith('video000009'));
  });

  it('shows YouTube titles and ban reasons as plain text', async () => {
    // titles come from YouTube and reasons are typed by people, so neither may become HTML
    vi.mocked(searchVideos).mockResolvedValue([
      { youtubeId: 'video000001', title: '<b>not bold</b>', channelTitle: 'Test Channel', topic: 'Biology' },
    ]);
    vi.mocked(searchUsers).mockResolvedValue([userRow({ bannedAt: '2026-10-06T00:00:00Z', banReason: '<i>x</i>' })]);
    const { container } = renderPage();

    expect(await screen.findByText('<b>not bold</b>')).toBeTruthy();
    expect(screen.getByText('Banned: <i>x</i>')).toBeTruthy();
    expect(container.querySelector('b')).toBeNull();
    expect(container.querySelector('i')).toBeNull();
  });

  it('shows a message if admin access was taken away', async () => {
    vi.mocked(fetchAdminStats).mockRejectedValue(new ApiError(403, 'Admins only.'));
    renderPage();
    expect((await screen.findByRole('alert')).textContent).toBe('You need admin access to use this page.');
  });

  it('sends the user to log in again when their session has expired', async () => {
    vi.mocked(fetchAdminStats).mockRejectedValue(new ApiError(401, 'Not logged in.'));
    renderPage();
    await vi.waitFor(() => expect(handlers.onLogout).toHaveBeenCalledTimes(1));
  });

  it('shows the backend message when a ban fails', async () => {
    vi.mocked(banUser).mockRejectedValue(new ApiError(400, 'Admins can\'t be banned. Remove their admin access first.'));
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Ban testuser' }));
    fireEvent.change(screen.getByLabelText('Reason for banning testuser'), { target: { value: 'x' } });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm ban' }));

    expect((await screen.findByRole('alert')).textContent).toBe(
      "Admins can't be banned. Remove their admin access first.",
    );
  });

  it('goes back to the feed', async () => {
    renderPage();
    await screen.findByText('testuser');
    fireEvent.click(screen.getByRole('button', { name: 'Back to feed' }));
    expect(handlers.onBack).toHaveBeenCalledTimes(1);
  });

  it('shows where each user signed up and last logged in from', async () => {
    renderPage();
    const row = (await within(usersTable()).findByText('testuser')).closest('tr')!;
    expect(within(row).getByText('192.0.2.10')).toBeTruthy();
    expect(within(row).getByText('198.51.100.7')).toBeTruthy();
  });

  it('says when an older account has no recorded IPs', async () => {
    vi.mocked(searchUsers).mockResolvedValue([userRow({ signupIp: null, lastLoginIp: null, lastLoginAt: null })]);
    renderPage();
    const row = (await within(usersTable()).findByText('testuser')).closest('tr')!;
    expect(within(row).getAllByText('Unknown IP')).toHaveLength(2);
    expect(within(row).getByText('Never')).toBeTruthy();
  });

  it('shows which IP each admin action came from', async () => {
    renderPage();
    const actions = await screen.findByRole('region', { name: 'Recent admin actions' });
    expect(within(actions).getByRole('columnheader', { name: 'IP' })).toBeTruthy();
    expect(await within(actions).findByText('203.0.113.5')).toBeTruthy();
  });

  it('shows Unknown for admin actions from before IPs were recorded', async () => {
    vi.mocked(fetchAdminActions).mockResolvedValue([
      { id: 1, admin: 'testadmin', action: 'unban_user', target: 'testuser (#2)', reason: null, ip: null, createdAt: '2026-10-06T00:00:00Z' },
    ]);
    renderPage();
    const actions = await screen.findByRole('region', { name: 'Recent admin actions' });
    expect(await within(actions).findByText('Unknown')).toBeTruthy();
  });

  it('can search users by IP address', async () => {
    renderPage();
    await screen.findByText('testuser');
    const search = screen.getByLabelText('Search users');
    expect(search.getAttribute('placeholder')).toContain('IP');
    fireEvent.change(search, { target: { value: '198.51.100.7' } });
    fireEvent.submit(search.closest('form')!);
    await vi.waitFor(() => expect(searchUsers).toHaveBeenLastCalledWith('198.51.100.7'));
  });
});

