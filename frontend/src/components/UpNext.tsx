import { useState } from 'react';
import { Compass, Keyboard, Loader2, Plus } from 'lucide-react';
import type { FeedVideo, Topic } from '../api/feed';
import { Button } from '@/components/ui/button';

const THUMBNAIL_URL = 'https://i.ytimg.com/vi/';
// Enough to fill the column on a laptop without turning it into a second feed.
const SHOWN = 5;

interface Props {
  videos: FeedVideo[];
  /** Index of the video on screen right now. */
  currentIndex: number;
  /** True while more videos are being loaded. */
  loading: boolean;
  /** Scrolls the feed to this video. */
  onSelect: (index: number) => void;
  /** Subjects the user hasn't picked, for "Explore more subjects". Empty or left out hides it. */
  suggestions?: Topic[];
  /** Adds a subject to the user's topics (the feed then starts over with it). Throws on failure. */
  onAddTopic?: (topicId: number) => Promise<void>;
}

// The right column on wide screens: the next few videos in the feed, so there's always something
// to look forward to, and the keyboard shortcuts. Hidden on smaller screens.
export default function UpNext({ videos, currentIndex, loading, onSelect, suggestions = [], onAddTopic }: Props) {
  const [adding, setAdding] = useState<number | null>(null);
  const [addError, setAddError] = useState<string | null>(null);

  async function addTopic(topicId: number) {
    if (!onAddTopic || adding !== null) return;
    setAdding(topicId);
    setAddError(null);
    try {
      await onAddTopic(topicId);
    } catch (err) {
      setAddError(err instanceof Error ? err.message : 'Could not add that subject.');
    } finally {
      // On success the feed usually starts over and this column goes away, but not in every case.
      setAdding(null);
    }
  }

  const upcoming = videos.slice(currentIndex + 1, currentIndex + 1 + SHOWN);

  return (
    <aside className="feed-up-next hidden w-80 shrink-0 flex-col gap-5 overflow-y-auto border-l bg-card/40 px-4 py-5 xl:flex" aria-label="Up next">
      <section className="flex flex-col gap-3" aria-labelledby="up-next-title">
        <div className="flex items-baseline justify-between">
          <h2 id="up-next-title" className="text-base font-semibold">
            Up next
          </h2>
          {videos.length > 0 && (
            <span className="text-xs text-muted-foreground">
              Video {Math.min(currentIndex + 1, videos.length)} of {videos.length}
            </span>
          )}
        </div>

        {upcoming.length > 0 ? (
          <ol className="flex flex-col gap-1">
            {upcoming.map((video, i) => (
              <li key={video.youtubeId}>
                <button
                  type="button"
                  className="group/next flex w-full cursor-pointer gap-3 rounded-lg p-1.5 text-left transition-colors duration-150 hover:bg-accent"
                  onClick={() => onSelect(currentIndex + 1 + i)}
                >
                  <img
                    src={`${THUMBNAIL_URL}${encodeURIComponent(video.youtubeId)}/mqdefault.jpg`}
                    alt=""
                    loading="lazy"
                    className="aspect-video w-28 shrink-0 rounded-md bg-muted object-cover"
                  />
                  <span className="flex min-w-0 flex-col gap-1">
                    {/* Titles come from YouTube: plain JSX text only. */}
                    <span className="line-clamp-2 text-sm leading-snug font-medium group-hover/next:text-primary">{video.title}</span>
                    <span className="truncate text-xs text-muted-foreground">{video.channelTitle}</span>
                  </span>
                </button>
              </li>
            ))}
          </ol>
        ) : (
          <p className="flex items-center gap-2 rounded-lg border border-dashed p-4 text-sm text-muted-foreground">
            {loading ? (
              <>
                <Loader2 aria-hidden="true" className="size-4 animate-spin" />
                Loading more videos…
              </>
            ) : (
              "You're all caught up. Add topics for more videos."
            )}
          </p>
        )}
      </section>

      {onAddTopic && suggestions.length > 0 && (
        <section className="feed-explore flex flex-col gap-3 border-t pt-5" aria-labelledby="explore-title">
          <div>
            <h2 id="explore-title" className="flex items-center gap-2 text-base font-semibold">
              <Compass aria-hidden="true" className="size-4 text-primary" />
              Explore more subjects
            </h2>
            <p className="text-xs text-muted-foreground">Add one to mix it into your feed.</p>
          </div>
          <ul className="flex flex-col gap-1.5">
            {suggestions.map((topic) => (
              <li key={topic.id} className="flex items-center justify-between gap-2 rounded-lg border bg-background px-3 py-2">
                <span className="truncate text-sm font-medium">{topic.name}</span>
                <Button
                  variant="ghost"
                  size="sm"
                  className="h-7 px-2 text-primary"
                  aria-label={`Add ${topic.name} to your topics`}
                  disabled={adding !== null}
                  onClick={() => void addTopic(topic.id)}
                >
                  {adding === topic.id ? <Loader2 aria-hidden="true" className="animate-spin" /> : <Plus aria-hidden="true" />}
                  Add
                </Button>
              </li>
            ))}
          </ul>
          {addError && (
            <p className="text-xs text-destructive" role="alert">
              {addError}
            </p>
          )}
        </section>
      )}

      <section className="mt-auto flex flex-col gap-2 rounded-xl border bg-background p-4 text-sm" aria-labelledby="shortcuts-title">
        <h2 id="shortcuts-title" className="flex items-center gap-2 font-semibold">
          <Keyboard aria-hidden="true" className="size-4 text-muted-foreground" />
          Shortcuts
        </h2>
        <dl className="grid grid-cols-[auto_1fr] items-center gap-x-3 gap-y-1.5 text-muted-foreground">
          <dt><Key>J</Key> <Key>↓</Key></dt>
          <dd>Next video</dd>
          <dt><Key>K</Key> <Key>↑</Key></dt>
          <dd>Previous video</dd>
        </dl>
      </section>
    </aside>
  );
}

function Key({ children }: { children: string }) {
  return <kbd className="inline-flex min-w-6 justify-center rounded border bg-muted px-1.5 py-0.5 font-sans text-xs font-medium text-foreground">{children}</kbd>;
}
