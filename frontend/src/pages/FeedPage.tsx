import { useEffect, useRef, useState } from 'react';
import type { User } from '../api/auth';
import { fetchFeed, type Video } from '../api/feed';
import brainLogo from '../assets/brainfeed-logo.png';
import VideoCard from '../components/VideoCard';

interface Props {
  topicIds: number[];
  user: User | null;
  onEditInterests: () => void;
  onLogout: () => void;
}

// The scrolling video feed. Loads 10 videos at a time as the user nears the bottom.
// App.tsx remounts this page when the interests change, which starts a fresh feed.
export default function FeedPage({ topicIds, user, onEditInterests, onLogout }: Props) {
  // A random seed per visit gives a new shuffle each time, but stays fixed while scrolling so
  // pages don't repeat videos.
  const [seed] = useState(() => Math.floor(Math.random() * 1_000_000_000));
  const [videos, setVideos] = useState<Video[]>([]);
  const [nextPage, setNextPage] = useState<number | null>(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

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

  function scrollFeed(direction: -1 | 1) {
    const list = listRef.current;
    if (list) {
      list.scrollBy({ top: direction * list.clientHeight, behavior: 'smooth' });
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
      <img className="feed-logo" src={brainLogo} alt="BrainFeed brain logo" />
      <header className="feed-header">
        <div className="feed-brand">
          <img className="feed-brand-logo" src={brainLogo} alt="" />
          <p className="brand">BrainFeed</p>
        </div>
        <div className="feed-actions">
          <span className="muted feed-user">{user ? user.username : 'Guest'}</span>
          <button type="button" className="link-button" onClick={onEditInterests}>
            Interests
          </button>
          <button type="button" className="link-button" onClick={onLogout}>
            {user ? 'Log out' : 'Log in'}
          </button>
        </div>
      </header>

      <nav className="feed-scroll-controls" aria-label="Feed navigation">
        <button type="button" aria-label="Scroll up" onClick={() => scrollFeed(-1)}>
          ↑
        </button>
        <button type="button" aria-label="Scroll down" onClick={() => scrollFeed(1)}>
          ↓
        </button>
      </nav>

      <div ref={listRef} className="feed-list">
        {videos.map((video) => (
          <VideoCard key={video.youtubeId} video={video} />
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
