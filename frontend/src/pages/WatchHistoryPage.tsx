import { useEffect, useState } from 'react';
import { CalendarDays, CircleAlert, History, Loader2, Play, Star, Trash2, Users, X } from 'lucide-react';
import { countWatchedToday, formatCapped, groupByDay, topCreators } from '@/lib/activity';
import StatTile from '@/components/StatTile';
import { ApiError } from '../api/client';
import { clearWatchHistory, fetchWatchHistory, removeFromWatchHistory, type WatchedVideo } from '../api/history';
import PageLayout, { type PageNavigation } from '@/components/layout/PageLayout';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';

// Same server as VideoCard; it's the only image domain the CSP allows.
const THUMBNAIL_URL = 'https://i.ytimg.com/vi/';

interface Props extends PageNavigation {
  /** Goes back to the feed and shows this video there. */
  onOpenVideo: (video: WatchedVideo) => void;
}

const UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ['day', 86_400_000],
  ['hour', 3_600_000],
  ['minute', 60_000],
];

/** "5 minutes ago", "yesterday", or "just now" for under a minute. */
export function timeAgo(date: string, now = Date.now()) {
  const elapsed = now - new Date(date).getTime();
  const format = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });
  for (const [unit, ms] of UNITS) {
    if (elapsed >= ms) return format.format(-Math.floor(elapsed / ms), unit);
  }
  return 'just now';
}

export default function WatchHistoryPage({ onOpenVideo, ...navigation }: Props) {
  const { user, onLogout } = navigation;
  const [videos, setVideos] = useState<WatchedVideo[]>([]);
  const [limit, setLimit] = useState<number | null>(null);
  const [loading, setLoading] = useState(user !== null);
  const [error, setError] = useState<string | null>(null);
  const [confirmingClear, setConfirmingClear] = useState(false);

  function handleError(err: unknown, fallback: string) {
    if (err instanceof ApiError && err.status === 401) {
      onLogout();
    } else {
      setError(err instanceof Error ? err.message : fallback);
    }
  }

  useEffect(() => {
    if (!user) {
      setLoading(false);
      return;
    }
    let cancelled = false;
    fetchWatchHistory()
      .then((history) => {
        if (!cancelled) {
          setVideos(history.videos);
          setLimit(history.limit);
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) handleError(err, 'Could not load your watch history.');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [user]);

  async function removeVideo(youtubeId: string) {
    setError(null);
    try {
      await removeFromWatchHistory(youtubeId);
      setVideos((current) => current.filter((video) => video.youtubeId !== youtubeId));
    } catch (err) {
      handleError(err, 'Could not remove that video.');
    }
  }

  async function clearAll() {
    setError(null);
    try {
      await clearWatchHistory();
      setVideos([]);
      setConfirmingClear(false);
    } catch (err) {
      handleError(err, 'Could not clear your watch history.');
    }
  }

  const clearActions =
    user && videos.length > 0 ? (
      confirmingClear ? (
        <div className="flex animate-fade-in-up items-center gap-2">
          <span className="text-sm text-muted-foreground">Clear all?</span>
          <Button size="sm" variant="destructive" onClick={() => void clearAll()}>
            Yes, clear
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setConfirmingClear(false)}>
            Cancel
          </Button>
        </div>
      ) : (
        <Button size="sm" variant="destructive-outline" onClick={() => setConfirmingClear(true)}>
          <Trash2 aria-hidden="true" />
          Clear history
        </Button>
      )
    ) : null;

  function renderContent() {
    if (!user) {
      return <p className="py-10 text-center text-sm text-muted-foreground">Sign in to keep a watch history.</p>;
    }
    if (loading) {
      return (
        <p className="flex items-center justify-center gap-2 py-10 text-sm text-muted-foreground">
          <Loader2 aria-hidden="true" className="size-4 animate-spin" />
          Loading your watch history…
        </p>
      );
    }
    if (videos.length === 0) {
      return (
        <div className="flex flex-col items-center gap-2 py-12 text-center">
          <span className="flex size-12 items-center justify-center rounded-full bg-muted">
            <History aria-hidden="true" className="size-6 text-muted-foreground" />
          </span>
          <p className="font-medium">Nothing here yet</p>
          <p className="text-sm text-muted-foreground">Videos you press play on will show up here.</p>
          <Button size="sm" className="mt-2" onClick={navigation.onBack}>
            <Play aria-hidden="true" />
            Start watching
          </Button>
        </div>
      );
    }
    return (
      <div id="watch-history-list">
        {groupByDay(videos).map((group) => (
          <section key={group.label} aria-labelledby={`history-${group.label}`} className="border-b last:border-b-0">
            <h2
              id={`history-${group.label}`}
              className="bg-muted/40 px-4 py-2 text-xs font-semibold tracking-wider text-muted-foreground uppercase"
            >
              {group.label}
            </h2>
            <ol className="divide-y">{group.videos.map(renderVideo)}</ol>
          </section>
        ))}
      </div>
    );
  }

  function renderVideo(video: WatchedVideo) {
    return (
      <li key={video.youtubeId} className="flex items-center gap-4 px-4 py-3 transition-colors duration-150 hover:bg-muted/40">
        <img
          src={`${THUMBNAIL_URL}${encodeURIComponent(video.youtubeId)}/mqdefault.jpg`}
          alt=""
          loading="lazy"
          className="aspect-video w-28 shrink-0 rounded-md border object-cover sm:w-36"
        />
        <div className="min-w-0 flex-1">
          {/* Titles and creators come from YouTube: plain JSX text only. */}
          <button
            type="button"
            className="line-clamp-2 cursor-pointer text-left font-medium underline-offset-4 transition-colors duration-150 hover:text-primary hover:underline"
            title="Watch in your feed"
            onClick={() => onOpenVideo(video)}
          >
            {video.title}
          </button>
          <p className="truncate text-sm text-muted-foreground">{video.creator}</p>
          <p className="text-xs text-muted-foreground/80">
            <time dateTime={video.watchedAt}>{timeAgo(video.watchedAt)}</time>
          </p>
        </div>
        <Button
          variant="ghost"
          size="icon"
          aria-label={`Remove ${video.title} from your history`}
          title="Remove from history"
          onClick={() => void removeVideo(video.youtubeId)}
        >
          <X aria-hidden="true" />
        </Button>
      </li>
    );
  }

  const channels = topCreators(videos, 1);
  const stats =
    user && !loading && videos.length > 0 ? (
      <section aria-label="Watch history summary" className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatTile icon={<Play />} label="Videos watched" value={formatCapped(videos.length, limit ?? 25)} hint="kept in your history" />
        <StatTile icon={<CalendarDays />} label="Watched today" value={String(countWatchedToday(videos))} />
        <StatTile icon={<Users />} label="Channels" value={String(new Set(videos.map((video) => video.creator)).size)} hint="different creators" />
        {/* Channel names come from YouTube: StatTile renders them as plain text. */}
        <StatTile
          icon={<Star />}
          label="Top channel"
          value={channels[0].creator}
          hint={`${channels[0].count} ${channels[0].count === 1 ? 'video' : 'videos'}`}
        />
      </section>
    ) : null;

  return (
    <PageLayout
      {...navigation}
      name="history"
      title="Watch history"
      description={`The last ${limit ?? 25} videos you pressed play on. Only you can see this.`}
      actions={clearActions}
    >
      {error && (
        <Alert variant="destructive" role="alert">
          <CircleAlert aria-hidden="true" />
          {error}
        </Alert>
      )}
      {stats}
      <Card role="region" aria-label="Watched videos" className="gap-0 overflow-hidden py-0">
        {renderContent()}
      </Card>
    </PageLayout>
  );
}
