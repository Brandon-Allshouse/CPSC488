import { useEffect, useRef, useState } from 'react';
import type { User } from '../api/auth';
import { fetchFeed, type Video } from '../api/feed';
import brainLogo from '../assets/brainfeed-logo.png';
import VideoCard from '../components/VideoCard';
import {fetchSavedVideos, removeSavedVideo, saveVideo,} from '../api/savedVideos';

// How long to keep checking for videos for brand-new topics: every 3 seconds, for up to a minute.
const FINDING_CHECK_MS = 3000;
const MAX_FINDING_CHECKS = 20;

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
    .then((saved) => {
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
  // The observer can fire once more with an old page number before React replaces it, so check
  // against this to never load the same page twice (which would show duplicate videos).
  const nextPageRef = useRef<number | null>(0);
  // True while the backend is still finding videos for topics nobody has picked before.
  const [finding, setFinding] = useState(false);
  const findingChecks = useRef(0);
  const findingTimer = useRef<ReturnType<typeof setTimeout>>(undefined);

  // Don't keep checking after the user leaves the feed.
  useEffect(() => () => clearTimeout(findingTimer.current), []);

  async function loadPage(page: number) {
    if (loadingRef.current || page !== nextPageRef.current) return;
    loadingRef.current = true;
    setLoading(true);
    setError(null);
    try {
      const result = await fetchFeed(topicIds, seed, page);
      if (page === 0 && result.videos.length === 0 && result.fetching && findingChecks.current < MAX_FINDING_CHECKS) {
        // Nothing yet, but YouTube is being searched right now. Ask again in a few seconds.
        findingChecks.current++;
        setFinding(true);
        findingTimer.current = setTimeout(() => void loadPage(0), FINDING_CHECK_MS);
        return;
      }
      setFinding(false);
      nextPageRef.current = result.nextPage;
      // New videos can be added while someone scrolls, which shifts the order, so skip repeats.
      setVideos((current) => {
        const shown = new Set(current.map((video) => video.youtubeId));
        return [...current, ...result.videos.filter((video) => !shown.has(video.youtubeId))];
      });
      setNextPage(result.nextPage);
    } catch (err) {
      setFinding(false);
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
  // Each video is as tall as the list, so scrolling by the list's height moves exactly one video.
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

  const isEmpty = videos.length === 0 && nextPage === null && !loading && !error && !finding;

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
          <VideoCard
            key={video.youtubeId}
            video={video}
            canSave={user !== null}
            saved={savedIds.has(video.youtubeId)}
            onToggleSaved={toggleSaved}
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
        {finding && (
          <div className="feed-message" role="status">
            <p>Finding videos for these topics…</p>
            <p className="muted">This usually takes a few seconds.</p>
          </div>
        )}
        {loading && !finding && <p className="feed-message muted">Loading…</p>}

        <div ref={sentinelRef} className="feed-sentinel" />
      </div>
    </div>
  );
}
