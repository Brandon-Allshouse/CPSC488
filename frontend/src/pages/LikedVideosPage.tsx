import { useEffect, useState, type ReactNode } from 'react';
import { CircleAlert, Loader2, ThumbsDown, ThumbsUp } from 'lucide-react';
import { ApiError } from '../api/client';
import { fetchMyVideoFeedback, type MyVideoFeedback, type SavedVideo } from '../api/feed';
import PageLayout, { type PageNavigation } from '@/components/layout/PageLayout';
import { Alert } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader } from '@/components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';

interface Props extends PageNavigation {
  /** Goes back to the feed and shows this video there. */
  onOpenVideo: (video: SavedVideo) => void;
}

export default function LikedVideosPage({ onOpenVideo, ...navigation }: Props) {
  const { user, onLogout } = navigation;
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
      <Table className="liked-videos-table">
        <TableHeader>
          <TableRow>
            <TableHead scope="col">Title</TableHead>
            <TableHead scope="col">Creator</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {videos.length === 0 ? (
            <TableRow>
              <TableCell colSpan={2} className="py-8 text-center text-muted-foreground">
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
              </TableRow>
            ))
          )}
        </TableBody>
      </Table>
    );
  }

  function renderList(videos: SavedVideo[], emptyMessage: string) {
    if (!user) return <p className="py-6 text-center text-sm text-muted-foreground">Sign in to view saved videos.</p>;
    if (loading) {
      return (
        <p className="flex items-center justify-center gap-2 py-6 text-sm text-muted-foreground">
          <Loader2 aria-hidden="true" className="size-4 animate-spin" />
          Loading saved videos…
        </p>
      );
    }
    if (error) {
      return (
        <Alert variant="destructive" role="alert">
          <CircleAlert aria-hidden="true" />
          {error}
        </Alert>
      );
    }
    return renderVideos(videos, emptyMessage);
  }

  function section(id: string, title: string, icon: ReactNode, videos: SavedVideo[] | undefined, emptyMessage: string) {
    return (
      <Card className="liked-videos-section" aria-labelledby={id} role="region">
        <CardHeader className="flex-row items-center gap-2">
          {icon}
          <h2 id={id} className="text-lg">
            {title}
          </h2>
          {videos && <Badge variant="secondary" className="ml-auto">{videos.length}</Badge>}
        </CardHeader>
        <CardContent>{renderList(videos ?? [], emptyMessage)}</CardContent>
      </Card>
    );
  }

  return (
    <PageLayout {...navigation} name="liked-videos" title="Your videos" description="Only you can see these lists. Click a title to watch it in your feed.">
      {section(
        'liked-videos-title',
        'Liked videos',
        <ThumbsUp aria-hidden="true" className="size-5 text-primary" />,
        feedback?.likedVideos,
        'No liked videos yet.',
      )}
      {section(
        'disliked-videos-title',
        'Disliked videos',
        <ThumbsDown aria-hidden="true" className="size-5 text-destructive" />,
        feedback?.dislikedVideos,
        'No disliked videos yet.',
      )}
    </PageLayout>
  );
}
