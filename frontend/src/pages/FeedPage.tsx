import { useEffect, useRef, useState, type ReactNode } from 'react';
import { ChevronDown, ChevronUp, CircleAlert, Inbox, Loader2 } from 'lucide-react';
import type { User } from '../api/auth';
import { ApiError } from '../api/client';
import {
  fetchFeed,
  fetchMyVideoFeedback,
  fetchTopics,
  saveMyVideoFeedback,
  type FeedVideo,
  type SavedVideo,
  type Topic,
  type VideoFeedback,
} from '../api/feed';
import { addToPlaylist, createPlaylist, deletePlaylist, fetchMyPlaylists, removeFromPlaylist, type Playlist } from '../api/playlists';
import FeedHeader from '../components/FeedHeader';
import FeedSidebar from '../components/FeedSidebar';
import UpNext from '../components/UpNext';
import VideoCard, { videoCardId } from '../components/VideoCard';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { fetchWatchHistory, recordWatch } from '../api/history';
import { countWatchedToday, formatCapped } from '@/lib/activity';
import AdminPage from './AdminPage';
import LeaderboardPage from './LeaderboardPage';
import YourVideosPage from './YourVideosPage';
import WatchHistoryPage from './WatchHistoryPage';
import { fetchSavedVideos, removeSavedVideo, saveVideo } from '../api/savedVideos';

// How long to keep checking for videos for brand-new topics: every 3 seconds, for up to a minute.
const FINDING_CHECK_MS = 3000;
const MAX_FINDING_CHECKS = 20;
// How long a video opened from "Your videos" stays outlined.
const HIGHLIGHT_MS = 3000;

interface Props {
  topicIds: number[];
  user: User | null;
  onEditInterests: () => void;
  /** Adds one topic to the user's interests, from "Explore more subjects". Throws on failure. */
  onAddTopic?: (topicId: number) => Promise<void>;
  onLogout: () => void;
}

// How many subjects "Explore more subjects" suggests.
const SUGGESTIONS = 4;

/**
 * Subjects the feed isn't built from yet, to suggest adding. Starts at a spot picked by the feed's
 * seed so each visit suggests different ones, but they don't jump around while scrolling.
 */
export function suggestSubjects(topics: Topic[], topicIds: number[], seed: number, count = SUGGESTIONS) {
  const picked = new Set(topicIds);
  const subjects = topics.filter((topic) => topic.parentId === null && !picked.has(topic.id));
  if (subjects.length <= count) return subjects;
  const start = seed % subjects.length;
  return [...subjects.slice(start), ...subjects.slice(0, start)].slice(0, count);
}

// The scrolling video feed. Loads 10 videos at a time as the user nears the bottom.
// App.tsx remounts this page when the interests change, which starts a fresh feed.
export default function FeedPage({ topicIds, user, onEditInterests, onAddTopic, onLogout }: Props) {
  // A random seed per visit gives a new shuffle each time, but stays fixed while scrolling so
  // pages don't repeat videos.
  const [seed] = useState(() => Math.floor(Math.random() * 1_000_000_000));
  const [videos, setVideos] = useState<FeedVideo[]>([]);
  // A video opened from "Your videos": outlined for a moment, and scrolled to once.
  const [openedVideoId, setOpenedVideoId] = useState<string | null>(null);
  const scrollToOpened = useRef(false);
  const [nextPage, setNextPage] = useState<number | null>(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Which video fills the feed right now, for the "Up next" column.
  const [currentIndex, setCurrentIndex] = useState(0);
  // Topic names for the sidebar and the tag on each video. Empty until loaded (or if that fails).
  const [topics, setTopics] = useState<Topic[]>([]);

  useEffect(() => {
    let cancelled = false;
    fetchTopics()
      .then((loaded) => {
        if (!cancelled) setTopics(loaded);
      })
      // Only labels depend on this, so the feed works fine without it.
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);

  // When the user's recent videos were watched, for "Today" in the sidebar's progress box. null
  // until loaded; if loading fails it stays null and the box shows a dash.
  const [watchTimes, setWatchTimes] = useState<{ youtubeId: string; watchedAt: string }[] | null>(null);
  const [historyLimit, setHistoryLimit] = useState(25);

  useEffect(() => {
    if (!user) return;
    let cancelled = false;
    fetchWatchHistory()
      .then((history) => {
        if (!cancelled) {
          setWatchTimes(history.videos);
          setHistoryLimit(history.limit);
        }
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [user]);

  // Videos in the Study notebook, so each card's bookmark shows whether it's saved there.
  const [savedIds, setSavedIds] = useState<Set<string>>(new Set());

  useEffect(() => {
    if (!user) {
      setSavedIds(new Set());
      return;
    }
    let cancelled = false;
    fetchSavedVideos()
      .then((saved) => {
        if (!cancelled) setSavedIds(new Set(saved.map((video) => video.youtubeId)));
      })
      .catch(() => {
        // The feed still works even if saved videos fail to load.
        if (!cancelled) setSavedIds(new Set());
      });
    return () => {
      cancelled = true;
    };
  }, [user]);

  const [feedbackError, setFeedbackError] = useState<string | null>(null);
  const [feedbackByVideo, setFeedbackByVideo] = useState<Record<string, VideoFeedback>>({});
  const feedbackChanges = useRef<Record<string, VideoFeedback | null>>({});
  const [playlists, setPlaylists] = useState<Playlist[] | null>(null);
  const [playlistsError, setPlaylistsError] = useState<string | null>(null);
  // Which screen is showing. FeedPage stays mounted, so the loaded videos are still there on the way back.
  const [view, setView] = useState<'feed' | 'liked' | 'history' | 'leaderboard' | 'admin'>('feed');

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

  useEffect(() => {
    if (!user) {
      setPlaylists(null);
      setPlaylistsError(null);
      return;
    }
    let cancelled = false;
    fetchMyPlaylists()
      .then((loaded) => {
        if (!cancelled) {
          setPlaylists(loaded);
          setPlaylistsError(null);
        }
      })
      .catch((err: unknown) => {
        // The feed's Save buttons stay hidden; "Your videos" shows the error instead of loading forever.
        if (cancelled) return;
        if (err instanceof ApiError && err.status === 401) {
          onLogout();
        } else {
          setPlaylistsError(err instanceof Error ? err.message : 'Could not load playlists.');
        }
      });
    return () => {
      cancelled = true;
    };
  }, [user]);

  // Makes a playlist change, then reloads the playlists so every Save button and "Your videos"
  // show the same thing. Errors are thrown on to whoever shows them (SaveMenu or YourVideosPage).
  async function changePlaylists(change: () => Promise<unknown>) {
    try {
      await change();
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        onLogout();
        return;
      }
      // Part of it may have worked (playlist made, but adding the video failed), so reload anyway.
      fetchMyPlaylists().then(setPlaylists, () => {});
      throw err;
    }
    setPlaylists(await fetchMyPlaylists());
  }

  const togglePlaylistSaved = (youtubeId: string, playlistId: number, save: boolean) =>
    changePlaylists(() => (save ? addToPlaylist(playlistId, youtubeId) : removeFromPlaylist(playlistId, youtubeId)));

  const createPlaylistAndSave = (youtubeId: string, name: string) =>
    changePlaylists(async () => {
      const playlist = await createPlaylist(name);
      await addToPlaylist(playlist.id, youtubeId);
    });

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

  // Used by Your videos so removing a notebook entry also updates the feed bookmark.
  async function removeNotebookVideo(youtubeId: string) {
    await removeSavedVideo(youtubeId);
    setSavedIds((current) => {
      const next = new Set(current);
      next.delete(youtubeId);
      return next;
    });
  }

  // Adds the video to the Study notebook, or takes it out if it's already there.
  async function toggleSaved(video: FeedVideo) {
    if (!user) return;
    const save = !savedIds.has(video.youtubeId);
    try {
      await (save ? saveVideo(video.youtubeId) : removeSavedVideo(video.youtubeId));
      setSavedIds((current) => {
        const next = new Set(current);
        if (save) next.add(video.youtubeId);
        else next.delete(video.youtubeId);
        return next;
      });
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) onLogout();
      // The save menu shows this error. A failed save must not stop feed pagination.
      throw err;
    }
  }

  // Each video is exactly as tall as the list, so the scroll position says which one is showing.
  function handleFeedScroll() {
    const list = listRef.current;
    if (list && list.clientHeight > 0) setCurrentIndex(Math.round(list.scrollTop / list.clientHeight));
  }

  function scrollToVideo(index: number) {
    const list = listRef.current;
    if (list) list.scrollTo({ top: index * list.clientHeight, behavior: 'smooth' });
  }

  // Each video is as tall as the list, so scrolling by the list's height moves exactly one video.
  function scrollFeed(direction: -1 | 1) {
    const list = listRef.current;
    if (list) {
      list.scrollBy({ top: direction * list.clientHeight, behavior: 'smooth' });
    }
  }

  // Keyboard shortcuts while the feed is showing: ↓ or J for the next video, ↑ or K for the
  // previous one. Ignored while typing in a field or when a modifier key is held.
  useEffect(() => {
    if (view !== 'feed') return;
    function handleKeyDown(event: KeyboardEvent) {
      const typing = event.target instanceof Element && event.target.closest('input, textarea, select, [contenteditable]');
      if (event.altKey || event.ctrlKey || event.metaKey || typing) {
        return;
      }
      if (event.key === 'ArrowDown' || event.key === 'j') {
        event.preventDefault();
        scrollFeed(1);
      } else if (event.key === 'ArrowUp' || event.key === 'k') {
        event.preventDefault();
        scrollFeed(-1);
      }
    }
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [view]);

  // Shows a video picked on "Your videos" in the feed. If it isn't in the pages loaded so far, it's
  // added at the top; loadPage skips videos already shown, so it never appears twice.
  function openSavedVideo(saved: SavedVideo) {
    setVideos((current) =>
      current.some((video) => video.youtubeId === saved.youtubeId)
        ? current
        : [{ youtubeId: saved.youtubeId, title: saved.title, channelTitle: saved.creator }, ...current],
    );
    scrollToOpened.current = true;
    setOpenedVideoId(saved.youtubeId);
    setView('feed');
  }

  // Once the feed is back on screen, scroll to the opened video (only once, so coming back to the
  // feed later doesn't jump there again).
  useEffect(() => {
    if (view !== 'feed' || !openedVideoId || !scrollToOpened.current) return;
    scrollToOpened.current = false;
    // getElementById, not querySelector: the id is never parsed as a CSS selector.
    document.getElementById(videoCardId(openedVideoId))?.scrollIntoView({ block: 'start' });
  }, [view, openedVideoId]);

  // The outline on the opened video fades after a few seconds.
  useEffect(() => {
    if (!openedVideoId) return;
    const timer = setTimeout(() => setOpenedVideoId(null), HIGHLIGHT_MS);
    return () => clearTimeout(timer);
  }, [openedVideoId]);

  // Pressing play adds the video to a signed-in user's watch history. It's done in the background:
  // if saving fails, the video still plays, and the history just misses that one.
  function recordPlay(youtubeId: string) {
    if (user) {
      recordWatch(youtubeId)
        .then(() =>
          // Same as the backend: a rewatched video moves to the top instead of counting twice.
          setWatchTimes((current) => [
            { youtubeId, watchedAt: new Date().toISOString() },
            ...(current ?? []).filter((watched) => watched.youtubeId !== youtubeId),
          ]),
        )
        .catch(() => undefined);
    }
  }

  // The BrainFeed logo: back to the first video when already on the feed.
  function scrollToTop() {
    listRef.current?.scrollTo({ top: 0, behavior: 'smooth' });
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
    // loadPage isn't listed here because the only thing it uses that changes is nextPage. view is,
    // because leaving the feed removes the marker and coming back draws a new one to watch.
  }, [nextPage, error, view]);

  const topicName = new Map(topics.map((topic) => [topic.id, topic.name]));
  const pickedTopicNames = topicIds.flatMap((id) => topicName.get(id) ?? []);

  // A video in the notebook and a playlist counts once, same as the Saved tab on "Your videos".
  const savedCount = new Set([...savedIds, ...(playlists ?? []).flatMap((playlist) => playlist.videos.map((video) => video.youtubeId))]).size;
  const activity = user
    ? {
        watchedToday: watchTimes ? formatCapped(countWatchedToday(watchTimes), historyLimit) : '–',
        saved: savedCount,
        liked: Object.values(feedbackByVideo).filter((choice) => choice === 'liked').length,
      }
    : undefined;

  const isEmpty = videos.length === 0 && nextPage === null && !loading && !error && !finding;

  const navigation = {
    user,
    onBack: () => setView('feed'),
    onEditInterests,
    onShowLikedVideos: () => setView('liked'),
    onShowLeaderboard: () => setView('leaderboard'),
    onShowHistory: () => setView('history'),
    onShowAdmin: () => setView('admin'),
    onLogout,
  };

  if (view === 'liked') {
    return (
      <YourVideosPage
        {...navigation}
        onOpenVideo={openSavedVideo}
        onRemoveNotebookVideo={removeNotebookVideo}
        playlists={playlists}
        playlistsError={playlistsError}
        onCreatePlaylist={(name) => changePlaylists(() => createPlaylist(name))}
        onDeletePlaylist={(playlistId) => changePlaylists(() => deletePlaylist(playlistId))}
        onRemoveFromPlaylist={(playlistId, youtubeId) => changePlaylists(() => removeFromPlaylist(playlistId, youtubeId))}
      />
    );
  }
  if (view === 'history') return <WatchHistoryPage {...navigation} onOpenVideo={openSavedVideo} />;
  if (view === 'leaderboard') return <LeaderboardPage {...navigation} />;
  if (view === 'admin') return <AdminPage {...navigation} />;

  return (
    <div id="feed-page" className="feed-page flex h-dvh flex-col">
      <FeedHeader
        user={user}
        onGoHome={scrollToTop}
        onEditInterests={onEditInterests}
        onShowLikedVideos={navigation.onShowLikedVideos}
        onShowLeaderboard={navigation.onShowLeaderboard}
        onShowHistory={navigation.onShowHistory}
        onShowAdmin={navigation.onShowAdmin}
        onLogout={onLogout}
      />

      <div className="flex min-h-0 flex-1">
        <FeedSidebar
          user={user}
          topicNames={pickedTopicNames}
          onGoHome={scrollToTop}
          onEditInterests={onEditInterests}
          onShowLikedVideos={navigation.onShowLikedVideos}
          onShowHistory={navigation.onShowHistory}
          onShowLeaderboard={navigation.onShowLeaderboard}
          onShowAdmin={navigation.onShowAdmin}
          onLogin={onLogout}
          activity={activity}
        />

        <main className="relative min-w-0 flex-1">
          <nav
            // Hidden on phones, where people swipe instead and the buttons would cover the video.
            className="feed-scroll-controls absolute top-1/2 right-4 z-10 hidden -translate-y-1/2 flex-col gap-2 sm:flex"
            aria-label="Feed navigation"
          >
            <Button variant="outline" size="icon" className="rounded-full bg-card/80 shadow-md backdrop-blur" aria-label="Scroll up" title="Previous video (↑ or K)" onClick={() => scrollFeed(-1)}>
              <ChevronUp aria-hidden="true" />
            </Button>
            <Button variant="outline" size="icon" className="rounded-full bg-card/80 shadow-md backdrop-blur" aria-label="Scroll down" title="Next video (↓ or J)" onClick={() => scrollFeed(1)}>
              <ChevronDown aria-hidden="true" />
            </Button>
          </nav>

          <div ref={listRef} id="feed-list" className="feed-list h-full overflow-y-auto" onScroll={handleFeedScroll}>
            {feedbackError && (
              <div className="mx-auto max-w-3xl px-4 pt-4 sm:px-6">
                <Alert variant="destructive" role="alert">
                  <CircleAlert aria-hidden="true" />
                  {feedbackError}
                </Alert>
              </div>
            )}
            {videos.map((video) => (
              <VideoCard
                key={video.youtubeId}
                video={video}
                topicName={video.topicId !== undefined ? topicName.get(video.topicId) : undefined}
                canSave={user !== null}
                saved={savedIds.has(video.youtubeId)}
                onToggleSaved={toggleSaved}
                feedback={feedbackByVideo[video.youtubeId] ?? null}
                onFeedbackChange={changeVideoFeedback}
                highlighted={video.youtubeId === openedVideoId}
                onPlay={recordPlay}
                playlists={playlists ?? undefined}
                onTogglePlaylist={togglePlaylistSaved}
                onCreatePlaylist={createPlaylistAndSave}
              />
            ))}

            {isEmpty && (
              <FeedMessage icon={<Inbox />}>
                <p className="font-medium">No videos for these topics yet.</p>
                <p className="text-sm text-muted-foreground">New videos are fetched from YouTube regularly. Try more topics, or check back later.</p>
              </FeedMessage>
            )}
            {error && (
              <FeedMessage icon={<CircleAlert className="text-destructive" />}>
                <p className="font-medium text-destructive" role="alert">
                  {error}
                </p>
                <Button variant="outline" className="mt-2" onClick={() => nextPage !== null && loadPage(nextPage)}>
                  Try again
                </Button>
              </FeedMessage>
            )}
            {finding && (
              <FeedMessage icon={<Loader2 className="animate-spin text-primary" />} role="status">
                <p className="font-medium">Finding videos for these topics…</p>
                <p className="text-sm text-muted-foreground">This usually takes a few seconds.</p>
              </FeedMessage>
            )}
            {loading && !finding && (
              <p className="feed-message flex items-center justify-center gap-2 py-8 text-sm text-muted-foreground">
                <Loader2 aria-hidden="true" className="size-4 animate-spin" />
                Loading…
              </p>
            )}

            <div ref={sentinelRef} className="feed-sentinel h-px" />
          </div>
        </main>

        <UpNext
          videos={videos}
          currentIndex={currentIndex}
          loading={loading || finding}
          onSelect={scrollToVideo}
          suggestions={suggestSubjects(topics, topicIds, seed)}
          onAddTopic={onAddTopic}
        />
      </div>
    </div>
  );
}

// A centered message in the middle of the feed (empty, error, or still searching YouTube).
function FeedMessage({ icon, role, children }: { icon: ReactNode; role?: string; children: ReactNode }) {
  return (
    <div
      className="feed-message mx-auto flex max-w-md animate-fade-in-up flex-col items-center gap-2 px-6 py-16 text-center"
      role={role}
    >
      <span aria-hidden="true" className="mb-2 flex size-14 items-center justify-center rounded-full bg-muted [&_svg]:size-6">
        {icon}
      </span>
      {children}
    </div>
  );
}
