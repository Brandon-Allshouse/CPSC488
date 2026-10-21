import { useEffect, useState } from 'react';
import type { User } from '../api/auth';
import { ApiError } from '../api/client';
import { fetchMyVideoFeedback, type MyVideoFeedback, type SavedVideo } from '../api/feed';
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

export default function LikedVideosPage({
  user,
  onBack,
  onEditInterests,
  onShowLikedVideos,
  onShowLeaderboard,
  onShowAdmin,
  onLogout,
}: Props) {
  const [feedback, setFeedback] = useState<MyVideoFeedback | null>(null);
  const [loading, setLoading] = useState(user !== null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!user) {
      setLoading(false);
      return;
    }

    let cancelled = false;
    setLoading(true);
    fetchMyVideoFeedback()
      .then((saved) => {
        if (!cancelled) {
          setFeedback(saved);
          setError(null);
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          if (err instanceof ApiError && err.status === 401) {
            onLogout();
          } else {
            setError(err instanceof Error ? err.message : 'Could not load saved videos.');
          }
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [user]);

  function renderVideos(videos: SavedVideo[], emptyMessage: string) {
    return (
      <table className="liked-videos-table">
        <thead>
          <tr>
            <th scope="col">Title</th>
            <th scope="col">Creator</th>
          </tr>
        </thead>
        <tbody>
          {videos.length === 0 ? (
            <tr>
              <td colSpan={2} className="muted">
                {emptyMessage}
              </td>
            </tr>
          ) : (
            videos.map((video) => (
              <tr key={video.youtubeId}>
                <td>{video.title}</td>
                <td>{video.creator}</td>
              </tr>
            ))
          )}
        </tbody>
      </table>
    );
  }

  function renderList(videos: SavedVideo[], emptyMessage: string) {
    if (!user) return <p className="muted">Sign in to view saved videos.</p>;
    if (loading) return <p className="muted">Loading saved videos…</p>;
    if (error) {
      return (
        <p className="form-error" role="alert">
          {error}
        </p>
      );
    }
    return renderVideos(videos, emptyMessage);
  }

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
      <main className="liked-videos-page">
        <header className="liked-videos-header">
          <h1>View Liked videos</h1>
          <button type="button" className="link-button" onClick={onBack}>
            Back to feed
          </button>
        </header>
        <section className="liked-videos-section" aria-labelledby="liked-videos-title">
          <h2 id="liked-videos-title">Liked videos</h2>
          <div className="liked-videos-panel">
            {renderList(feedback?.likedVideos ?? [], 'No liked videos yet.')}
          </div>
        </section>
        <section className="liked-videos-section" aria-labelledby="disliked-videos-title">
          <h2 id="disliked-videos-title">Disliked videos</h2>
          <div className="liked-videos-panel">
            {renderList(feedback?.dislikedVideos ?? [], 'No disliked videos yet.')}
          </div>
        </section>
      </main>
    </>
  );
}
