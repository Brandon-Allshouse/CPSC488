import { useEffect, useRef, useState } from 'react';
import { Check, ExternalLink, Link2, Play, Sparkles, ThumbsDown, ThumbsUp } from 'lucide-react';
import type { FeedVideo, VideoFeedback } from '../api/feed';
import type { Playlist } from '../api/playlists';
import { timeAgo } from '@/lib/format';
import { cn } from '@/lib/utils';
import SaveMenu from './SaveMenu';

// youtube-nocookie.com is YouTube's "privacy mode" player, which doesn't set tracking cookies
// until you hit play. If you change either domain, update the CSP in vite.config.ts too.
const EMBED_URL = 'https://www.youtube-nocookie.com/embed/';
const THUMBNAIL_URL = 'https://i.ytimg.com/vi/';
// The normal YouTube page, for "Watch on YouTube" and "Copy link". Opening it is a plain link, so
// it doesn't need a CSP entry.
const WATCH_URL = 'https://www.youtube.com/watch?v=';
// How long "Copied" shows before the button goes back to "Copy link".
const COPIED_MS = 2000;

interface Props {
  video: FeedVideo;
  /** Shown as a tag next to the channel, e.g. "Algebra 2". */
  topicName?: string;
  saved?: boolean;
  canSave?: boolean;
  /** The original private saved-video list, separate from playlists. */
  onToggleSaved?: (video: FeedVideo) => void | Promise<void>;
  feedback?: VideoFeedback | null;
  onFeedbackChange?: (youtubeId: string, feedback: VideoFeedback | null) => Promise<void>;
  /** Outlines the card, to show which video was just opened from "Your videos". */
  highlighted?: boolean;
  /** Called when the play button is pressed (for the watch history). Scrolling past doesn't count. */
  onPlay?: (youtubeId: string) => void;
  /** The user's playlists, for the bookmark menu. Leave the three playlist props out to hide it. */
  playlists?: Playlist[];
  /** Adds the video to a playlist (save true) or removes it. Throws on failure. */
  onTogglePlaylist?: (youtubeId: string, playlistId: number, save: boolean) => Promise<void>;
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
  topicName,
  saved = false,
  canSave = false,
  onToggleSaved,
  feedback = null,
  onFeedbackChange,
  highlighted = false,
  onPlay,
  playlists,
  onTogglePlaylist,
  onCreatePlaylist,
}: Props) {
  const notebook = canSave && onToggleSaved ? { saved, onToggle: () => onToggleSaved(video) } : undefined;
  const canUsePlaylists = playlists !== undefined && onTogglePlaylist !== undefined && onCreatePlaylist !== undefined;
  const published = timeAgo(video.publishedAt);
  const [playing, setPlaying] = useState(false);
  const [savingFeedback, setSavingFeedback] = useState(false);
  const cardRef = useRef<HTMLElement>(null);
  const [copyState, setCopyState] = useState<'idle' | 'copied' | 'failed'>('idle');

  useEffect(() => {
    if (copyState === 'idle') return;
    const timer = setTimeout(() => setCopyState('idle'), COPIED_MS);
    return () => clearTimeout(timer);
  }, [copyState]);

  // The clipboard API only exists on https and localhost, and the user can block it, so failing is
  // normal and just shows "Couldn't copy".
  async function copyLink() {
    try {
      await navigator.clipboard.writeText(watchUrl);
      setCopyState('copied');
    } catch {
      setCopyState('failed');
    }
  }

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
  const watchUrl = `${WATCH_URL}${id}`;

  async function changeFeedback(next: VideoFeedback | null) {
    if (!onFeedbackChange) return;
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
        <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
          <h2 className="video-title min-w-0 flex-1 line-clamp-2 text-base sm:text-lg">{video.title}</h2>
          <div className="video-feedback flex shrink-0 self-end gap-2 sm:self-auto">
            {onFeedbackChange && (
              <>
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
              </>
            )}
            {(notebook || canUsePlaylists) && (
              <SaveMenu
                youtubeId={video.youtubeId}
                idPrefix={`video-${id}`}
                notebook={notebook}
                playlists={canUsePlaylists ? playlists : undefined}
                onToggle={onTogglePlaylist ? (playlistId, save) => onTogglePlaylist(video.youtubeId, playlistId, save) : undefined}
                onCreate={onCreatePlaylist ? (name) => onCreatePlaylist(video.youtubeId, name) : undefined}
              />
            )}
          </div>
        </div>
        <div className="video-meta flex flex-wrap items-center gap-x-3 gap-y-2 text-sm text-muted-foreground">
          <span className="flex min-w-0 items-center gap-2 font-medium text-foreground">
            <span
              aria-hidden="true"
              className="flex size-7 shrink-0 items-center justify-center rounded-full bg-primary/15 text-xs font-semibold text-primary uppercase"
            >
              {video.channelTitle.charAt(0)}
            </span>
            <span className="truncate">{video.channelTitle}</span>
          </span>
          {topicName && <span className="rounded-full border bg-secondary px-2.5 py-0.5 text-xs font-medium text-secondary-foreground">{topicName}</span>}
          {published && <span className="text-xs">{published}</span>}
          <span className="video-links ml-auto flex items-center gap-1">
            <a
              href={watchUrl}
              // noopener + noreferrer: the new tab can't reach back into BrainFeed (reverse tabnabbing).
              target="_blank"
              rel="noopener noreferrer"
              aria-label="Watch on YouTube (opens in a new tab)"
              className="inline-flex items-center gap-1.5 rounded-md px-2 py-1 text-xs font-medium transition-colors duration-150 hover:bg-accent hover:text-foreground"
            >
              <ExternalLink aria-hidden="true" className="size-3.5" />
              YouTube
            </a>
            <button
              type="button"
              className="inline-flex cursor-pointer items-center gap-1.5 rounded-md px-2 py-1 text-xs font-medium transition-colors duration-150 hover:bg-accent hover:text-foreground"
              onClick={() => void copyLink()}
            >
              {copyState === 'copied' ? <Check aria-hidden="true" className="size-3.5 text-success" /> : <Link2 aria-hidden="true" className="size-3.5" />}
              {copyState === 'copied' ? 'Copied' : copyState === 'failed' ? "Couldn't copy" : 'Copy link'}
            </button>
          </span>
        </div>
        {/* Placeholder until the LLM summaries on the roadmap fill it in. Team rule: no "coming soon" wording. */}
        <div className="video-summary-label mt-1 flex flex-col gap-1 rounded-lg bg-muted/60 p-3 text-sm">
          <p className="flex items-center gap-2">
            <Sparkles aria-hidden="true" className="size-4 text-primary" />
            <strong>Video Summary</strong>
          </p>
          <p className="text-muted-foreground">No summary for this video.</p>
        </div>
      </section>
    </article>
  );
}
