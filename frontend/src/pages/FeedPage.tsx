import { useEffect, useRef, useState } from 'react';
import type { User } from '../api/auth';
import { fetchFeed, type Video } from '../api/feed';
import VideoCard from '../components/VideoCard';
import {fetchSavedVideos, removeSavedVideo, saveVideo,} from '../api/savedVideos';

interface Props {
  topicIds: number[];
  user: User | null;
  onEditInterests: () => void;
  onLogout: () => void;
  onSavedVideos: () => void;
}

// The scrolling video feed. Loads 10 videos at a time as the user nears the bottom.
// App.tsx remounts this page when the interests change, which starts a fresh feed.
export default function FeedPage({ topicIds, user, onEditInterests, onLogout, onSavedVideos }: Props) {
  // A random seed per visit gives a new shuffle each time, but stays fixed while scrolling so
  // pages don't repeat videos.
  const [seed] = useState(() => Math.floor(Math.random() * 1_000_000_000));
  const [videos, setVideos] = useState<Video[]>([]);
  const [nextPage, setNextPage] = useState<number | null>(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  
  const [savedIds, setSavedIds] = useState<Set<string>>(new Set());

  useEffect(() => {
    if(!user) {
      setSavedIds(new Set());
      return;
    }

    fetchSavedVideos()
    .then(saved) => }
            setSavedIds(
    new Set(saved.map((video) => video.youtubeId)),
    );
})
.catch(() => {
  // The feed still works even if saved videos fail to load.
  setSavedIds(new Set());
});
}, [user]);

  const listRef = useRef<HTMLDivElement>(null);
  const sentinelRef = useRef<HTMLDivElement>(null);
  // A ref, not state, so two quick triggers can't both start loading the same page.
  const loadingRef = useRef(false);

  async function loadPage(page: number) {
    if (loadingRef.current) return;
    loadingRef.current = true;
    setLoading(true);
    setError(null);
    try {
      const result = await fetchFeed(topicIds, seed, page);
      setVideos((current) => [...current, ...result.videos]);
      setNextPage(result.nextPage);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load videos.');
    } finally {
      loadingRef.current = false;
      setLoading(false);
    }
  }

async function toggleSaved(video: Video) {
  if (!user) return;

  const currentlySaved = savedIds.has(video.youtubeId);

  try {
    if (currentlySaved) {
      await removeSavedVideo(video.youtubeId);

      setSavedIds((current) => {
        const next = new Set(current);
        next.delete(video.youtubeId);
        return next;
      });
    } else {
      await saveVideo(video.youtubeId);

      setSavedIds((current) => {
        const next = new Set(current);
        next.add(video.youtubeId);
        return next;
      });
    }
  } catch (err) {
    setError(
      err instanceof Error
        ? err.message
        : 'Could not update saved videos.',
    );
  }
}

  // Load the next page when the invisible marker under the last video comes within 600px.
  useEffect(() => {
    const sentinel = sentinelRef.current;
    if (!sentinel || nextPage === null || error) return;
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) void loadPage(nextPage);
      },
      { root: listRef.current, rootMargin: '0px 0px 600px 0px' },
    );
    observer.observe(sentinel);
    return () => observer.disconnect();
    // loadPage isn't listed here because the only thing it uses that changes is nextPage.
  }, [nextPage, error]);

  const isEmpty = videos.length === 0 && nextPage === null && !loading && !error;

  return (
    <div className="feed-page">
      <header className="feed-header">
        <p className="brand">BrainFeed</p>
        <div className="feed-actions">
          <span className="muted feed-user">{user ? user.username : 'Guest'}</span>
          {user && (
      <button
    type="button"
    className="link-button"
    onClick={onSavedVideos}
  >
    Saved
  </button>
)}
          <button type="button" className="link-button" onClick={onEditInterests}>
            Interests
          </button>
          <button type="button" className="link-button" onClick={onLogout}>
            {user ? 'Log out' : 'Log in'}
          </button>
        </div>
      </header>

      <div ref={listRef} className="feed-list">
        {videos.map((video) => (
          <VideoCard
            key={video.youtubeId}
            video={video}
            canSave={user !== null}
            saved={savedIds.has(video.youtubeId)}
            onToggleSAved={togglesaved}
            />
        ))}

        {isEmpty && (
          <div className="feed-message">
            <p>No videos for these topics yet.</p>
            <p className="muted">New videos are fetched from YouTube regularly. Try more topics, or check back later.</p>
          </div>
        )}
        {error && (
          <div className="feed-message">
            <p className="form-error" role="alert">
              {error}
            </p>
            <button type="button" className="btn btn-secondary" onClick={() => nextPage !== null && loadPage(nextPage)}>
              Try again
            </button>
          </div>
        )}
        {loading && <p className="feed-message muted">Loading…</p>}

        <div ref={sentinelRef} className="feed-sentinel" />
      </div>
    </div>
  );
}
