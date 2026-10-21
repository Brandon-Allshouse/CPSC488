import type { User } from '../api/auth';
import brainLogo from '../assets/brainfeed-logo.png';

interface Props {
  user: User | null;
  onEditInterests: () => void;
  onShowLikedVideos: () => void;
  onShowLeaderboard: () => void;
  // Only admins see this menu item. The backend still checks on every admin request.
  onShowAdmin?: () => void;
  onLogout: () => void;
}

export default function FeedHeader({
  user,
  onEditInterests,
  onShowLikedVideos,
  onShowLeaderboard,
  onShowAdmin,
  onLogout,
}: Props) {
  return (
    <header className="feed-header">
      <div className="feed-brand">
        <img className="feed-brand-logo" src={brainLogo} alt="" />
        <p className="brand">BrainFeed</p>
      </div>
      <details className="profile-menu">
        <summary>
          Profile
          <span className="profile-chevron" aria-hidden="true">
            ▸
          </span>
        </summary>
        <div className="profile-menu-content">
          <span className="muted">{user ? user.username : 'Guest'}</span>
          <button type="button" className="link-button" onClick={onEditInterests}>
            Interests
          </button>
          <button type="button" className="link-button" onClick={onShowLikedVideos}>
            View Liked videos
          </button>
          <button type="button" className="link-button" onClick={onShowLeaderboard}>
            Leaderboard
          </button>
          {user?.admin && onShowAdmin && (
            <button type="button" className="link-button" onClick={onShowAdmin}>
              Admin
            </button>
          )}
          <button type="button" className="link-button" onClick={onLogout}>
            {user ? 'Sign out' : 'Log in'}
          </button>
        </div>
      </details>
    </header>
  );
}
