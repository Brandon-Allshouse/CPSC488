import { useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { Bookmark, CircleAlert, History, ListVideo, Loader2, Plus, ThumbsDown, ThumbsUp, Trash2, X } from 'lucide-react';
import { ApiError } from '../api/client';
import { fetchMyVideoFeedback, type MyVideoFeedback, type SavedVideo } from '../api/feed';
import type { Playlist } from '../api/playlists';
import PageLayout, { type PageNavigation } from '@/components/layout/PageLayout';
import { Alert } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { cn } from '@/lib/utils';

type Tab = 'saved' | 'liked' | 'disliked';

interface Props extends PageNavigation {
  /** Goes back to the feed and shows this video there. */
  onOpenVideo: (video: SavedVideo) => void;
  /** The user's playlists, kept by FeedPage so the feed's Save buttons stay in step. null while loading. */
  playlists: Playlist[] | null;
  /** Set if the playlists couldn't be loaded, so the Saved tab shows it instead of loading forever. */
  playlistsError?: string | null;
  onCreatePlaylist: (name: string) => Promise<void>;
  onDeletePlaylist: (playlistId: number) => Promise<void>;
  onRemoveFromPlaylist: (playlistId: number, youtubeId: string) => Promise<void>;
}

// "Your videos": saved playlists, and liked and disliked videos. All saved on the server, so
// signed-in users only. Watch history has its own page (WatchHistoryPage), linked from here.
export default function YourVideosPage({
  onOpenVideo,
  playlists,
  playlistsError = null,
  onCreatePlaylist,
  onDeletePlaylist,
  onRemoveFromPlaylist,
  ...navigation
}: Props) {
  const { user, onLogout } = navigation;
  const [tab, setTab] = useState<Tab>('saved');
  const [feedback, setFeedback] = useState<MyVideoFeedback | null>(null);
  const [loading, setLoading] = useState(user !== null);
  const [error, setError] = useState<string | null>(null);
  const [newName, setNewName] = useState('');
  const [playlistError, setPlaylistError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

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

  // Runs a playlist change and shows its error (e.g. "name already used") on the Saved tab.
  async function runPlaylistAction(action: () => Promise<void>) {
    setBusy(true);
    setPlaylistError(null);
    try {
      await action();
    } catch (err) {
      setPlaylistError(err instanceof Error ? err.message : 'Something went wrong.');
    } finally {
      setBusy(false);
    }
  }

  function handleCreate(e: FormEvent) {
    e.preventDefault();
    const name = newName.trim();
    if (!name) return;
    void runPlaylistAction(async () => {
      await onCreatePlaylist(name);
      setNewName('');
    });
  }

  function renderVideos(videos: SavedVideo[], emptyMessage: string, action?: (video: SavedVideo) => ReactNode) {
    return (
      <Table className="your-videos-table">
        <TableHeader>
          <TableRow>
            <TableHead scope="col">Title</TableHead>
            <TableHead scope="col">Creator</TableHead>
            {action && (
              <TableHead scope="col" className="w-12">
                <span className="sr-only">Actions</span>
              </TableHead>
            )}
          </TableRow>
        </TableHeader>
        <TableBody>
          {videos.length === 0 ? (
            <TableRow>
              <TableCell colSpan={action ? 3 : 2} className="py-8 text-center text-muted-foreground">
                {emptyMessage}
              </TableCell>
            </TableRow>
          ) : (
            videos.map((video) => (
              <TableRow key={video.youtubeId}>
                {/* Titles and creators come from YouTube: plain JSX text only. */}
                <TableCell className="font-medium">
                  <button
                    type="button"
                    className="cursor-pointer text-left underline-offset-4 transition-colors duration-150 hover:text-primary hover:underline"
                    title="Watch in your feed"
                    onClick={() => onOpenVideo(video)}
                  >
                    {video.title}
                  </button>
                </TableCell>
                <TableCell className="text-muted-foreground">{video.creator}</TableCell>
                {action && <TableCell className="text-right">{action(video)}</TableCell>}
              </TableRow>
            ))
          )}
        </TableBody>
      </Table>
    );
  }

  function signInMessage(what: string) {
    return <p className="py-6 text-center text-sm text-muted-foreground">Sign in to see your {what}.</p>;
  }

  function loadingMessage(text: string) {
    return (
      <p className="flex items-center justify-center gap-2 py-6 text-sm text-muted-foreground">
        <Loader2 aria-hidden="true" className="size-4 animate-spin" />
        {text}
      </p>
    );
  }

  function errorAlert(message: string) {
    return (
      <Alert variant="destructive" role="alert">
        <CircleAlert aria-hidden="true" />
        {message}
      </Alert>
    );
  }

  function section(id: string, title: ReactNode, icon: ReactNode, count: number | undefined, body: ReactNode, actions?: ReactNode) {
    return (
      <Card key={id} className="your-videos-section" aria-labelledby={id} role="region">
        <CardHeader className="flex-row items-center gap-2">
          {icon}
          {/* Playlist names are typed by the user: plain JSX text only. */}
          <h2 id={id} className="truncate text-lg">
            {title}
          </h2>
          {count !== undefined && <Badge variant="secondary" className="ml-auto">{count}</Badge>}
          {actions}
        </CardHeader>
        <CardContent>{body}</CardContent>
      </Card>
    );
  }

  function feedbackSection(kind: 'liked' | 'disliked') {
    const videos = kind === 'liked' ? feedback?.likedVideos : feedback?.dislikedVideos;
    let body: ReactNode;
    if (!user) body = signInMessage(`${kind} videos`);
    else if (loading) body = loadingMessage('Loading saved videos…');
    else if (error) body = errorAlert(error);
    else body = renderVideos(videos ?? [], kind === 'liked' ? 'No liked videos yet.' : 'No disliked videos yet.');
    return section(
      `${kind}-videos-title`,
      kind === 'liked' ? 'Liked videos' : 'Disliked videos',
      kind === 'liked' ? (
        <ThumbsUp aria-hidden="true" className="size-5 text-primary" />
      ) : (
        <ThumbsDown aria-hidden="true" className="size-5 text-destructive" />
      ),
      videos?.length,
      body,
    );
  }

  function savedTab() {
    if (playlists === null) {
      let body: ReactNode;
      if (!user) body = signInMessage('saved videos');
      else if (playlistsError) body = errorAlert(playlistsError);
      else body = loadingMessage('Loading playlists…');
      return section('saved-videos-title', 'Saved videos', <Bookmark aria-hidden="true" className="size-5 text-primary" />, undefined, body);
    }
    return (
      <>
        <form className="flex gap-2" onSubmit={handleCreate}>
          <Input
            placeholder="New playlist name"
            aria-label="New playlist name"
            maxLength={50}
            value={newName}
            onChange={(e) => setNewName(e.target.value)}
          />
          <Button type="submit" variant="outline" disabled={busy || !newName.trim()}>
            <Plus aria-hidden="true" />
            Create playlist
          </Button>
        </form>
        {playlistError && errorAlert(playlistError)}
        {playlists.map((playlist) =>
          section(
            `playlist-${playlist.id}-title`,
            playlist.name,
            playlist.watchLater ? (
              <Bookmark aria-hidden="true" className="size-5 shrink-0 text-primary" />
            ) : (
              <ListVideo aria-hidden="true" className="size-5 shrink-0 text-primary" />
            ),
            playlist.videos.length,
            renderVideos(
              playlist.videos,
              playlist.watchLater ? 'Nothing saved for later yet. Use the bookmark button on a video.' : 'This playlist is empty.',
              (video) => (
                <Button
                  variant="ghost"
                  size="icon"
                  aria-label={`Remove ${video.title} from ${playlist.name}`}
                  disabled={busy}
                  onClick={() => void runPlaylistAction(() => onRemoveFromPlaylist(playlist.id, video.youtubeId))}
                >
                  <X aria-hidden="true" />
                </Button>
              ),
            ),
            !playlist.watchLater && (
              <Button
                variant="ghost"
                size="icon"
                aria-label={`Delete playlist ${playlist.name}`}
                disabled={busy}
                onClick={() => {
                  if (window.confirm(`Delete the playlist "${playlist.name}"? The videos stay in your feed.`)) {
                    void runPlaylistAction(() => onDeletePlaylist(playlist.id));
                  }
                }}
              >
                <Trash2 aria-hidden="true" />
              </Button>
            ),
          ),
        )}
      </>
    );
  }

  const tabs: { key: Tab; label: string; count?: number }[] = [
    { key: 'saved', label: 'Saved', count: playlists?.reduce((sum, p) => sum + p.videos.length, 0) },
    { key: 'liked', label: 'Liked', count: feedback?.likedVideos.length },
    { key: 'disliked', label: 'Disliked', count: feedback?.dislikedVideos.length },
  ];

  return (
    <PageLayout
      {...navigation}
      name="your-videos"
      title="Your videos"
      description="Only you can see these lists. Click a title to watch it in your feed."
      actions={
        navigation.onShowHistory && (
          <Button variant="outline" size="sm" onClick={navigation.onShowHistory}>
            <History aria-hidden="true" />
            Watch history
          </Button>
        )
      }
    >
      <div role="tablist" aria-label="Your videos" className="flex gap-1 overflow-x-auto border-b">
        {tabs.map(({ key, label, count }) => (
          <button
            key={key}
            type="button"
            role="tab"
            id={`your-videos-tab-${key}`}
            aria-selected={tab === key}
            aria-controls="your-videos-panel"
            className={cn(
              '-mb-px cursor-pointer border-b-2 border-transparent px-4 py-2 text-sm font-medium whitespace-nowrap text-muted-foreground transition-colors duration-150 hover:text-foreground',
              tab === key && 'border-primary text-primary hover:text-primary',
            )}
            onClick={() => setTab(key)}
          >
            {label}
            {count !== undefined && <span className="ml-1.5 text-xs opacity-70">({count})</span>}
          </button>
        ))}
      </div>
      <div id="your-videos-panel" role="tabpanel" aria-labelledby={`your-videos-tab-${tab}`} className="flex flex-col gap-4">
        {tab === 'saved' && savedTab()}
        {tab === 'liked' && feedbackSection('liked')}
        {tab === 'disliked' && feedbackSection('disliked')}
      </div>
    </PageLayout>
  );
}
