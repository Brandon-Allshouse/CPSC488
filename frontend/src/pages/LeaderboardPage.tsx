import type { User } from '../api/auth';
import FeedHeader from '../components/FeedHeader';

interface Props {
  user: User | null;
  onBack: () => void;
  onEditInterests: () => void;
  onShowLikedVideos: () => void;
  onShowLeaderboard: () => void;
  onShowAdmin?: () => void;
  onLogout: () => void;
}

export default function LeaderboardPage({
  user,
  onBack,
  onEditInterests,
  onShowLikedVideos,
  onShowLeaderboard,
  onShowAdmin,
  onLogout,
}: Props) {
  return (
    <>
      <FeedHeader
        user={user}
        onEditInterests={onEditInterests}
        onShowLikedVideos={onShowLikedVideos}
        onShowLeaderboard={onShowLeaderboard}
        onShowAdmin={onShowAdmin}
        onLogout={onLogout}
      />
      <main className="leaderboard-page">
        <header className="leaderboard-header">
          <h1>Leaderboard</h1>
          <button type="button" className="link-button" onClick={onBack}>
            Back to feed
          </button>
        </header>
        <section className="leaderboard-panel" aria-label="Leaderboard information" />
        <div className="leaderboard-actions">
          <button type="button" className="btn btn-primary">
            Update leaderboard
          </button>
        </div>
      </main>
    </>
  );
}
