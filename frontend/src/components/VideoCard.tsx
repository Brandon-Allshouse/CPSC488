import { useEffect, useRef, useState } from 'react';
import { Play, ThumbsDown, ThumbsUp } from 'lucide-react';
import type { FeedVideo, VideoFeedback } from '../api/feed';
import type { Playlist } from '../api/playlists';
import { cn } from '@/lib/utils';
import SaveMenu from './SaveMenu';

// youtube-nocookie.com is YouTube's "privacy mode" player, which doesn't set tracking cookies
// until you hit play. If you change either domain, update the CSP in vite.config.ts too.
const EMBED_URL = 'https://www.youtube-nocookie.com/embed/';
const THUMBNAIL_URL = 'https://i.ytimg.com/vi/';

interface Props {
  video: FeedVideo;
  feedback: VideoFeedback | null;
  onFeedbackChange: (youtubeId: string, feedback: VideoFeedback | null) => Promise<void>;
  /** Outlines the card, to show which video was just opened from "Your videos". */
  highlighted?: boolean;
  /** Called when the play button is pressed (for the watch history). Scrolling past doesn't count. */
  onPlay?: (youtubeId: string) => void;
  /** The user's playlists, for the Save button. Leave the three save props out to hide it (guests). */
  playlists?: Playlist[];
  /** Adds the video to a playlist (save true) or removes it. Throws on failure. */
  onToggleSaved?: (youtubeId: string, playlistId: number, save: boolean) => Promise<void>;
  /** Makes a new playlist with this video in it. Throws on failure (e.g. name taken). */
  onCreatePlaylist?: (youtubeId: string, name: string) => Promise<void>;
}

/** The card's element id, so the feed can scroll to a video. YouTube ids are URL-safe already. */
export function videoCardId(youtubeId: string) {
  return `video-${encodeURIComponent(youtubeId)}`;
}

function FeedbackButton({ kind, label, tooltipId, pressed, disabled, onClick }: {
  kind: VideoFeedback;
  label: string;
  tooltipId: string;
  pressed: boolean;
  disabled: boolean;
  onClick: () => void;
}) {
  const Icon = kind === 'liked' ? ThumbsUp : ThumbsDown;
  return (
    <button
      type="button"
      className={cn(
        'group/feedback relative flex size-10 cursor-pointer items-center justify-center rounded-full border bg-card transition-colors duration-150 hover:bg-accent disabled:cursor-wait disabled:opacity-60',
        pressed && kind === 'liked' && 'border-primary/60 bg-primary/15 text-primary hover:bg-primary/20',
        pressed && kind === 'disliked' && 'border-destructive/60 bg-destructive/15 text-destructive hover:bg-destructive/20',
      )}
      aria-label={label}
      aria-describedby={tooltipId}
      aria-pressed={pressed}
      disabled={disabled}
      onClick={onClick}
    >
      <Icon aria-hidden="true" className={cn('size-[18px]', pressed && 'animate-pop fill-current')} />
      <span
        id={tooltipId}
        role="tooltip"
        className="pointer-events-none absolute right-0 bottom-full mb-2 w-max max-w-48 translate-y-1 rounded-md bg-popover px-2 py-1 text-xs text-popover-foreground opacity-0 shadow-lg transition-all duration-150 group-hover/feedback:translate-y-0 group-hover/feedback:opacity-100 group-focus-visible/feedback:translate-y-0 group-focus-visible/feedback:opacity-100"
      >
        {label}
      </span>
    </button>
  );
}

// One video in the feed. Shows the thumbnail until the user presses play, so scrolling past
// videos doesn't load a YouTube player (and its trackers) for every one of them.
export default function VideoCard({
  video,
  feedback,
  onFeedbackChange,
  highlighted = false,
  onPlay,
  playlists,
  onToggleSaved,
  onCreatePlaylist,
}: Props) {
  const [playing, setPlaying] = useState(false);
  const [savingFeedback, setSavingFeedback] = useState(false);
  const cardRef = useRef<HTMLElement>(null);

  // Stop the video once it's mostly scrolled out of view, so it doesn't keep playing off-screen.
  useEffect(() => {
    const card = cardRef.current;
    if (!playing || !card) return;
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.intersectionRatio < 0.25) setPlaying(false);
      },
      { threshold: 0.25 },
    );
    observer.observe(card);
    return () => observer.disconnect();
  }, [playing]);

  // The backend only stores ids matching YouTube's format; encoding is a second safeguard.
  const id = encodeURIComponent(video.youtubeId);

  async function changeFeedback(next: VideoFeedback | null) {
    setSavingFeedback(true);
    try {
      await onFeedbackChange(video.youtubeId, next);
    } finally {
      setSavingFeedback(false);
    }
  }

  return (
    <article
      ref={cardRef}
      id={videoCardId(video.youtubeId)}
      data-highlighted={highlighted || undefined}
      className="video-card mx-auto flex h-full w-full max-w-3xl shrink-0 flex-col justify-center gap-4 px-4 py-4 sm:px-6"
    >
      <div
        className={cn(
          'video-frame relative aspect-video w-full overflow-hidden rounded-xl border bg-black shadow-2xl ring-offset-4 ring-offset-background transition-shadow duration-500',
          highlighted && 'ring-2 ring-primary',
        )}
      >
        {playing ? (
          <iframe
            className="absolute inset-0 size-full"
            src={`${EMBED_URL}${id}?autoplay=1&rel=0`}
            title={video.title}
            allow="autoplay; encrypted-media; picture-in-picture; fullscreen"
            allowFullScreen
            // YouTube refuses to play embeds that send no referrer.
            referrerPolicy="strict-origin-when-cross-origin"
            // Limits what the embedded page can do (no navigating our page, no forms, etc.).
            sandbox="allow-scripts allow-same-origin allow-presentation allow-popups allow-popups-to-escape-sandbox"
          />
        ) : (
          <button
            type="button"
            className="video-poster group/poster absolute inset-0 cursor-pointer"
            onClick={() => {
              setPlaying(true);
              onPlay?.(video.youtubeId);
            }}
            aria-label={`Play: ${video.title}`}
          >
            <img
              src={`${THUMBNAIL_URL}${id}/hqdefault.jpg`}
              alt=""
              loading="lazy"
              className="size-full object-cover transition-transform duration-300 group-hover/poster:scale-[1.03]"
            />
            <span className="absolute inset-0 bg-gradient-to-t from-black/50 to-transparent" aria-hidden="true" />
            <span
              className="play-icon absolute top-1/2 left-1/2 flex size-16 -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-full bg-primary/90 text-primary-foreground shadow-xl ring-4 ring-white/15 transition-transform duration-200 group-hover/poster:scale-110"
              aria-hidden="true"
            >
              <Play className="ml-1 size-7 fill-current" />
            </span>
          </button>
        )}
      </div>

      <section className="video-summary flex flex-col gap-2 rounded-xl border bg-card p-4" aria-label="Video summary">
        {/* These come from YouTube. Normal JSX text is safe; never use dangerouslySetInnerHTML here. */}
        <div className="flex items-start justify-between gap-3">
          <h2 className="video-title line-clamp-2 text-base sm:text-lg">{video.title}</h2>
          <div className="video-feedback flex shrink-0 gap-2">
            <FeedbackButton
              kind="liked"
              label="I liked this video"
              tooltipId={`like-tooltip-${id}`}
              pressed={feedback === 'liked'}
              disabled={savingFeedback}
              onClick={() => void changeFeedback(feedback === 'liked' ? null : 'liked')}
            />
            <FeedbackButton
              kind="disliked"
              label="I didn't really like this video"
              tooltipId={`dislike-tooltip-${id}`}
              pressed={feedback === 'disliked'}
              disabled={savingFeedback}
              onClick={() => void changeFeedback(feedback === 'disliked' ? null : 'disliked')}
            />
            {playlists && onToggleSaved && onCreatePlaylist && (
              <SaveMenu
                youtubeId={video.youtubeId}
                idPrefix={`video-${id}`}
                playlists={playlists}
                onToggle={(playlistId, save) => onToggleSaved(video.youtubeId, playlistId, save)}
                onCreate={(name) => onCreatePlaylist(video.youtubeId, name)}
              />
            )}
          </div>
        </div>
        <p className="text-sm text-muted-foreground">{video.channelTitle}</p>
        {/* Placeholder heading. The summary text will come from the LLM summaries on the roadmap. */}
        <p className="video-summary-label mt-1 border-t pt-3 text-sm">
          <strong>Video Summary</strong>
        </p>
      </section>
    </article>
  );
}
