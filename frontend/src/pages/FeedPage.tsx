import { useEffect, useRef, useState } from 'react';
import type { User } from '../api/auth';
import { ApiError } from '../api/client';
import {
  fetchFeed,
  fetchMyVideoFeedback,
  saveMyVideoFeedback,
  type Video,
  type VideoFeedback,
} from '../api/feed';
import brainLogo from '../assets/brainfeed-logo.png';
import FeedHeader from '../components/FeedHeader';
import VideoCard from '../components/VideoCard';
import LeaderboardPage from './LeaderboardPage';
import LikedVideosPage from './LikedVideosPage';

// How long to keep checking for videos for brand-new topics: every 3 seconds, for up to a minute.
const FINDING_CHECK_MS = 3000;
const MAX_FINDING_CHECKS = 20;

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
  const [feedbackError, setFeedbackError] = useState<string | null>(null);
  const [feedbackByVideo, setFeedbackByVideo] = useState<Record<string, VideoFeedback>>({});
  const feedbackChanges = useRef<Record<string, VideoFeedback | null>>({});
  const [showLeaderboard, setShowLeaderboard] = useState(false);
  const [showLikedVideos, setShowLikedVideos] = useState(false);

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

  useEffect(() => {
    if (!user) {
      setFeedbackByVideo({});
      feedbackChanges.current = {};
      return;
    }

    let cancelled = false;
    fetchMyVideoFeedback()
      .then((saved) => {
        if (!cancelled) {
          const feedback: Record<string, VideoFeedback> = {};
          saved.likedVideos.forEach((video) => {
            feedback[video.youtubeId] = 'liked';
          });
          saved.dislikedVideos.forEach((video) => {
            feedback[video.youtubeId] = 'disliked';
          });
          Object.entries(feedbackChanges.current).forEach(([youtubeId, choice]) => {
            if (choice === null) {
              delete feedback[youtubeId];
            } else {
              feedback[youtubeId] = choice;
            }
          });
          setFeedbackByVideo(feedback);
          setFeedbackError(null);
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          if (err instanceof ApiError && err.status === 401) {
            onLogout();
          } else {
            setFeedbackError(err instanceof Error ? err.message : 'Could not load saved video feedback.');
          }
        }
      });
    return () => {
      cancelled = true;
    };
  }, [user]);

  async function changeVideoFeedback(youtubeId: string, feedback: VideoFeedback | null) {
    if (!user) {
      onLogout();
      return;
    }
    setFeedbackError(null);
    try {
      await saveMyVideoFeedback(youtubeId, feedback);
      feedbackChanges.current[youtubeId] = feedback;
      setFeedbackByVideo((current) => {
        const next = { ...current };
        if (feedback === null) {
          delete next[youtubeId];
        } else {
          next[youtubeId] = feedback;
        }
        return next;
      });
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        onLogout();
      } else {
        setFeedbackError(err instanceof Error ? err.message : 'Could not save video feedback.');
      }
    }
  }

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

  const showFeed = () => {
    setShowLeaderboard(false);
    setShowLikedVideos(false);
  };

  const showLeaderboardPage = () => {
    setShowLikedVideos(false);
    setShowLeaderboard(true);
  };

  const showLikedVideosPage = () => {
    setShowLeaderboard(false);
    setShowLikedVideos(true);
  };

  if (showLikedVideos) {
    return (
      <LikedVideosPage
        user={user}
        onBack={showFeed}
        onEditInterests={onEditInterests}
        onShowLikedVideos={showLikedVideosPage}
        onShowLeaderboard={showLeaderboardPage}
        onLogout={onLogout}
      />
    );
  }

  if (showLeaderboard) {
    return (
      <LeaderboardPage
        user={user}
        onBack={showFeed}
        onEditInterests={onEditInterests}
        onShowLikedVideos={showLikedVideosPage}
        onShowLeaderboard={showLeaderboardPage}
        onLogout={onLogout}
      />
    );
  }

  return (
    <div className="feed-page">
      <img className="feed-logo" src={brainLogo} alt="BrainFeed brain logo" />
      <FeedHeader
        user={user}
        onEditInterests={onEditInterests}
        onShowLikedVideos={showLikedVideosPage}
        onShowLeaderboard={showLeaderboardPage}
        onLogout={onLogout}
      />

      <nav className="feed-scroll-controls" aria-label="Feed navigation">
        <button type="button" aria-label="Scroll up" onClick={() => scrollFeed(-1)}>
          ↑
        </button>
        <button type="button" aria-label="Scroll down" onClick={() => scrollFeed(1)}>
          ↓
        </button>
      </nav>

      <div ref={listRef} className="feed-list">
        {feedbackError && (
          <p className="feed-message form-error" role="alert">
            {feedbackError}
          </p>
        )}
        {videos.map((video) => (
          <VideoCard
            key={video.youtubeId}
            video={video}
            feedback={feedbackByVideo[video.youtubeId] ?? null}
            onFeedbackChange={changeVideoFeedback}
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
