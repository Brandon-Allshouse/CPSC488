import { useEffect, useState } from 'react';
import type { FeedVideo, Video } from '../api/feed';
import {
  fetchSavedVideos,
  removeSavedVideo,
} from '../api/savedVideos';
import VideoCard from '../components/VideoCard';
import { Button } from '@/components/ui/button';

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

  async function handleRemove(video: FeedVideo) {
    // Let the shared save menu display failures and keep the checkbox unchanged.
    await removeSavedVideo(video.youtubeId);
    setVideos((current) => current.filter((saved) => saved.youtubeId !== video.youtubeId));
  }

  return (
    <div className="feed-page flex min-h-dvh flex-col">
      <header className="feed-header flex items-center justify-between gap-4 border-b px-4 py-3">
        <h1 className="brand text-lg font-bold">Saved Videos</h1>

        <div className="feed-actions flex items-center gap-2">
          <Button
            type="button"
            variant="outline"
            onClick={onBack}
          >
            Back to feed
          </Button>
        </div>
      </header>

      <div className="feed-list flex flex-1 flex-col gap-4 py-4">
        {loading && (
          <p className="feed-message p-6 text-center text-muted-foreground">Loading…</p>
        )}

        {error && (
          <p className="feed-message p-6 text-center text-destructive" role="alert">
            {error}
          </p>
        )}

        {!loading && !error && videos.length === 0 && (
          <div className="feed-message p-6 text-center">
            <p>You haven't saved any videos yet.</p>
            <p className="text-sm text-muted-foreground">
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
