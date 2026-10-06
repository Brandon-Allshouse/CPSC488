import { useEffect, useState } from 'react';
import type { Video } from '../api/feed';
import {
  fetchSavedVideos,
  removeSavedVideo,
} from '../api/savedVideos';
import VideoCard from '../components/VideoCard';

interface Props {
  onBack: () => void;
}

export default function SavedVideosPage({ onBack }: Props) {
  const [videos, setVideos] = useState<Video[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchSavedVideos()
      .then(setVideos)
      .catch((err) => {
        setError(
          err instanceof Error
            ? err.message
            : 'Could not load saved videos.',
        );
      })
      .finally(() => setLoading(false));
  }, []);

  async function handleRemove(video: Video) {
    try {
      await removeSavedVideo(video.youtubeId);

      setVideos((current) =>
        current.filter(
          (saved) => saved.youtubeId !== video.youtubeId,
        ),
      );
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : 'Could not remove saved video.',
      );
    }
  }

  return (
    <div className="feed-page">
      <header className="feed-header">
        <p className="brand">Saved Videos</p>

        <div className="feed-actions">
          <button
            type="button"
            className="link-button"
            onClick={onBack}
          >
            Back to feed
          </button>
        </div>
      </header>

      <div className="feed-list">
        {loading && (
          <p className="feed-message muted">Loading…</p>
        )}

        {error && (
          <p className="feed-message form-error" role="alert">
            {error}
          </p>
        )}

        {!loading && !error && videos.length === 0 && (
          <div className="feed-message">
            <p>You haven't saved any videos yet.</p>
            <p className="muted">
              Save videos from your feed and they will appear here.
            </p>
          </div>
        )}

        {videos.map((video) => (
          <VideoCard
            key={video.youtubeId}
            video={video}
            canSave
            saved
            onToggleSaved={handleRemove}
          />
        ))}
      </div>
    </div>
  );
}
