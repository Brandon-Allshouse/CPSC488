import { type FormEvent, useEffect, useState } from 'react';
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
  type AdminAction,
  type AdminStats,
  type AdminUser,
  type AdminVideo,
  type BannedVideo,
} from '../api/admin';
import type { User } from '../api/auth';
import { ApiError } from '../api/client';
import FeedHeader from '../components/FeedHeader';
import { parseYoutubeId } from '../validation';

// Same limit as AdminController.MAX_REASON on the backend.
const MAX_REASON = 200;

const ACTION_LABELS: Record<AdminAction['action'], string> = {
  ban_user: 'Banned user',
  unban_user: 'Unbanned user',
  ban_video: 'Banned video',
  unban_video: 'Unbanned video',
};

interface Props {
  user: User | null;
  onBack: () => void;
  onEditInterests: () => void;
  onShowLikedVideos: () => void;
  onShowLeaderboard: () => void;
  onShowAdmin: () => void;
  onLogout: () => void;
}

function formatDate(value: string) {
  return new Date(value).toLocaleDateString();
}

// Asks for the reason before a ban goes through, so a stray click can't ban anyone and the
// admin log always says why.
function BanReasonForm({ label, onConfirm, onCancel }: {
  label: string;
  onConfirm: (reason: string) => Promise<void>;
  onCancel: () => void;
}) {
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      await onConfirm(reason.trim());
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="admin-ban-form" onSubmit={submit}>
      <input
        aria-label={`Reason for banning ${label}`}
        placeholder="Reason"
        value={reason}
        maxLength={MAX_REASON}
        onChange={(e) => setReason(e.target.value)}
        autoFocus
      />
      <button type="submit" className="btn-danger" disabled={busy || reason.trim() === ''}>
        Confirm ban
      </button>
      <button type="button" className="link-button" onClick={onCancel}>
        Cancel
      </button>
    </form>
  );
}

// Only reachable from the profile menu when user.admin is true. Hiding the menu item is just
// convenience: every request here is checked again by the backend, which answers 403 to non-admins.
export default function AdminPage({
  user,
  onBack,
  onEditInterests,
  onShowLikedVideos,
  onShowLeaderboard,
  onShowAdmin,
  onLogout,
}: Props) {
  const [stats, setStats] = useState<AdminStats | null>(null);
  const [userSearch, setUserSearch] = useState('');
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [videoSearch, setVideoSearch] = useState('');
  const [videos, setVideos] = useState<AdminVideo[]>([]);
  const [bannedVideos, setBannedVideos] = useState<BannedVideo[]>([]);
  const [actions, setActions] = useState<AdminAction[]>([]);
  // Which row's "Ban" button was pressed, e.g. "user:5" or "video:dQw4w9WgXcQ".
  const [pendingBan, setPendingBan] = useState<string | null>(null);
  const [videoLink, setVideoLink] = useState('');
  const [videoLinkReason, setVideoLinkReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  function handleError(err: unknown) {
    if (err instanceof ApiError && err.status === 401) {
      onLogout();
    } else if (err instanceof ApiError && err.status === 403) {
      setError('You need admin access to use this page.');
    } else {
      setError(err instanceof Error ? err.message : 'Something went wrong.');
    }
  }

  // The parts of the page every ban or unban changes.
  async function refreshSummary() {
    const [newStats, newBanned, newActions] = await Promise.all([
      fetchAdminStats(),
      fetchBannedVideos(),
      fetchAdminActions(),
    ]);
    setStats(newStats);
    setBannedVideos(newBanned);
    setActions(newActions);
  }

  useEffect(() => {
    if (!user?.admin) return;
    Promise.all([refreshSummary(), searchUsers('').then(setUsers), searchVideos('').then(setVideos)]).catch(
      handleError,
    );
    // Runs once when the page opens; the user doesn't change while it's open.
  }, [user]);

  // Runs a ban or unban, then reloads what it changed. Shows the outcome either way.
  async function runAction(action: () => Promise<void>, done: string, reload: () => Promise<void>) {
    setError(null);
    setNotice(null);
    try {
      await action();
      setPendingBan(null);
      setNotice(done);
      await Promise.all([refreshSummary(), reload()]);
    } catch (err) {
      handleError(err);
    }
  }

  const reloadUsers = () => searchUsers(userSearch).then(setUsers);
  const reloadVideos = () => searchVideos(videoSearch).then(setVideos);

  function submitUserSearch(e: FormEvent) {
    e.preventDefault();
    setError(null);
    reloadUsers().catch(handleError);
  }

  function submitVideoSearch(e: FormEvent) {
    e.preventDefault();
    setError(null);
    reloadVideos().catch(handleError);
  }

  async function submitVideoLink(e: FormEvent) {
    e.preventDefault();
    const youtubeId = parseYoutubeId(videoLink);
    if (!youtubeId) {
      setError('That is not a YouTube video link or id.');
      return;
    }
    await runAction(() => banVideo(youtubeId, videoLinkReason.trim()), `Banned video ${youtubeId}.`, async () => {
      setVideoLink('');
      setVideoLinkReason('');
      await reloadVideos();
    });
  }

  const header = (
    <FeedHeader
      user={user}
      onEditInterests={onEditInterests}
      onShowLikedVideos={onShowLikedVideos}
      onShowLeaderboard={onShowLeaderboard}
      onShowAdmin={onShowAdmin}
      onLogout={onLogout}
    />
  );

  if (!user?.admin) {
    return (
      <>
        {header}
        <main className="admin-page">
          <p className="form-error" role="alert">
            You need admin access to use this page.
          </p>
          <button type="button" className="link-button" onClick={onBack}>
            Back to feed
          </button>
        </main>
      </>
    );
  }

  return (
    <>
      {header}
      <main className="admin-page">
        <header className="admin-header">
          <h1>Admin</h1>
          <button type="button" className="link-button" onClick={onBack}>
            Back to feed
          </button>
        </header>

        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}
        {notice && (
          <p className="admin-notice" role="status">
            {notice}
          </p>
        )}

        <section className="admin-section" aria-labelledby="admin-overview">
          <h2 id="admin-overview">Overview</h2>
          {stats && (
            <dl className="admin-stats">
              <div>
                <dt>Users</dt>
                <dd>{stats.users}</dd>
              </div>
              <div>
                <dt>Banned users</dt>
                <dd>{stats.bannedUsers}</dd>
              </div>
              <div>
                <dt>Admins</dt>
                <dd>{stats.admins}</dd>
              </div>
              <div>
                <dt>Videos</dt>
                <dd>{stats.videos}</dd>
              </div>
              <div>
                <dt>Banned videos</dt>
                <dd>{stats.bannedVideos}</dd>
              </div>
            </dl>
          )}
        </section>

        <section className="admin-section" aria-labelledby="admin-users">
          <h2 id="admin-users">Users</h2>
          <form className="admin-search" onSubmit={submitUserSearch} role="search">
            <input
              aria-label="Search users"
              placeholder="Username or email"
              value={userSearch}
              maxLength={100}
              onChange={(e) => setUserSearch(e.target.value)}
            />
            <button type="submit" className="btn btn-secondary">
              Search
            </button>
          </form>
          <div className="admin-table-wrap">
            <table className="admin-table">
              <thead>
                <tr>
                  <th scope="col">Username</th>
                  <th scope="col">Email</th>
                  <th scope="col">Joined</th>
                  <th scope="col">Status</th>
                  <th scope="col">Action</th>
                </tr>
              </thead>
              <tbody>
                {users.length === 0 && (
                  <tr>
                    <td colSpan={5} className="muted">
                      No users found.
                    </td>
                  </tr>
                )}
                {users.map((row) => (
                  <tr key={row.id}>
                    <td>{row.username}</td>
                    <td>{row.email}</td>
                    <td>{formatDate(row.createdAt)}</td>
                    <td>
                      {row.admin ? 'Admin' : row.bannedAt ? `Banned: ${row.banReason}` : 'Active'}
                    </td>
                    <td>
                      {row.admin || row.id === user.id ? null : row.bannedAt ? (
                        <button
                          type="button"
                          className="link-button"
                          aria-label={`Unban ${row.username}`}
                          onClick={() => runAction(() => unbanUser(row.id), `Unbanned ${row.username}.`, reloadUsers)}
                        >
                          Unban
                        </button>
                      ) : pendingBan === `user:${row.id}` ? (
                        <BanReasonForm
                          label={row.username}
                          onCancel={() => setPendingBan(null)}
                          onConfirm={(reason) =>
                            runAction(() => banUser(row.id, reason), `Banned ${row.username}.`, reloadUsers)
                          }
                        />
                      ) : (
                        <button
                          type="button"
                          className="btn-danger"
                          aria-label={`Ban ${row.username}`}
                          onClick={() => setPendingBan(`user:${row.id}`)}
                        >
                          Ban
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>

        <section className="admin-section" aria-labelledby="admin-videos">
          <h2 id="admin-videos">Videos</h2>
          <form className="admin-search" onSubmit={submitVideoLink}>
            <input
              aria-label="YouTube link or video id"
              placeholder="YouTube link or video id"
              value={videoLink}
              onChange={(e) => setVideoLink(e.target.value)}
            />
            <input
              aria-label="Reason for banning this link"
              placeholder="Reason"
              value={videoLinkReason}
              maxLength={MAX_REASON}
              onChange={(e) => setVideoLinkReason(e.target.value)}
            />
            <button
              type="submit"
              className="btn-danger"
              disabled={videoLink.trim() === '' || videoLinkReason.trim() === ''}
            >
              Ban video
            </button>
          </form>
          <p className="muted admin-hint">Works for any YouTube video, even ones not in the feed yet.</p>
          <form className="admin-search" onSubmit={submitVideoSearch} role="search">
            <input
              aria-label="Search videos"
              placeholder="Title, channel or id"
              value={videoSearch}
              maxLength={100}
              onChange={(e) => setVideoSearch(e.target.value)}
            />
            <button type="submit" className="btn btn-secondary">
              Search
            </button>
          </form>
          <div className="admin-table-wrap">
            <table className="admin-table">
              <thead>
                <tr>
                  <th scope="col">Title</th>
                  <th scope="col">Channel</th>
                  <th scope="col">Topic</th>
                  <th scope="col">Action</th>
                </tr>
              </thead>
              <tbody>
                {videos.length === 0 && (
                  <tr>
                    <td colSpan={4} className="muted">
                      No videos found.
                    </td>
                  </tr>
                )}
                {videos.map((video) => (
                  <tr key={video.youtubeId}>
                    <td>{video.title}</td>
                    <td>{video.channelTitle}</td>
                    <td>{video.topic}</td>
                    <td>
                      {pendingBan === `video:${video.youtubeId}` ? (
                        <BanReasonForm
                          label={video.title}
                          onCancel={() => setPendingBan(null)}
                          onConfirm={(reason) =>
                            runAction(() => banVideo(video.youtubeId, reason), `Banned "${video.title}".`, reloadVideos)
                          }
                        />
                      ) : (
                        <button
                          type="button"
                          className="btn-danger"
                          aria-label={`Ban ${video.title}`}
                          onClick={() => setPendingBan(`video:${video.youtubeId}`)}
                        >
                          Ban
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>

        <section className="admin-section" aria-labelledby="admin-banned-videos">
          <h2 id="admin-banned-videos">Banned videos</h2>
          <div className="admin-table-wrap">
            <table className="admin-table">
              <thead>
                <tr>
                  <th scope="col">Video</th>
                  <th scope="col">Reason</th>
                  <th scope="col">Banned by</th>
                  <th scope="col">Date</th>
                  <th scope="col">Action</th>
                </tr>
              </thead>
              <tbody>
                {bannedVideos.length === 0 && (
                  <tr>
                    <td colSpan={5} className="muted">
                      No banned videos.
                    </td>
                  </tr>
                )}
                {bannedVideos.map((video) => (
                  <tr key={video.youtubeId}>
                    <td>{video.title ?? video.youtubeId}</td>
                    <td>{video.reason}</td>
                    <td>{video.bannedBy ?? 'Deleted account'}</td>
                    <td>{formatDate(video.bannedAt)}</td>
                    <td>
                      <button
                        type="button"
                        className="link-button"
                        aria-label={`Unban ${video.title ?? video.youtubeId}`}
                        onClick={() =>
                          runAction(() => unbanVideo(video.youtubeId), `Unbanned ${video.title ?? video.youtubeId}.`, reloadVideos)
                        }
                      >
                        Unban
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="muted admin-hint">An unbanned video comes back the next time its topic is refreshed.</p>
        </section>

        <section className="admin-section" aria-labelledby="admin-actions">
          <h2 id="admin-actions">Recent admin actions</h2>
          <div className="admin-table-wrap">
            <table className="admin-table">
              <thead>
                <tr>
                  <th scope="col">Date</th>
                  <th scope="col">Admin</th>
                  <th scope="col">Action</th>
                  <th scope="col">Target</th>
                  <th scope="col">Reason</th>
                </tr>
              </thead>
              <tbody>
                {actions.length === 0 && (
                  <tr>
                    <td colSpan={5} className="muted">
                      Nothing yet.
                    </td>
                  </tr>
                )}
                {actions.map((entry) => (
                  <tr key={entry.id}>
                    <td>{formatDate(entry.createdAt)}</td>
                    <td>{entry.admin ?? 'Deleted account'}</td>
                    <td>{ACTION_LABELS[entry.action]}</td>
                    <td>{entry.target}</td>
                    <td>{entry.reason ?? ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      </main>
    </>
  );
}
