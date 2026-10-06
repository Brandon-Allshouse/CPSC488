import { useEffect, useRef, useState } from 'react';
import type { Video } from '../api/feed';

// youtube-nocookie.com is YouTube's "privacy mode" player, which doesn't set tracking cookies
// until you hit play. If you change either domain, update the CSP in vite.config.ts too.
const EMBED_URL = 'https://www.youtube-nocookie.com/embed/';
const THUMBNAIL_URL = 'https://i.ytimg.com/vi/';

interface Props {
  video: Video;
  saved?: boolean;
  canSave?: boolean;
  onToggleSaved?: (video: Video) => void;
}

// One video in the feed. Shows the thumbnail until the user presses play, so scrolling past
// videos doesn't load a YouTube player (and its trackers) for every one of them.
export default function VideoCard({ 
  video,
  saved = false,
  canSave = false,
  onTogglesSaved,
}: Props) {
  const [playing, setPlaying] = useState(false);
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

  return (
    <article ref={cardRef} className="video-card">
      <div className="video-frame">
        {playing ? (
          <iframe
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
            className="video-poster"
            onClick={() => setPlaying(true)}
            aria-label={`Play: ${video.title}`}
          >
            <img src={`${THUMBNAIL_URL}${id}/hqdefault.jpg`} alt="" loading="lazy" />
            <span className="play-icon" aria-hidden="true">
              ▶
            </span>
          </button>
        )}
      </div>
      {/* These come from YouTube. Normal JSX text is safe; never use dangerouslySetInnerHTML here. */}
      <h2 className="video-title">{video.title}</h2>
      <p className="muted">{video.channelTitle}</p>
      {canSave && onToggleSaved && (
      <div className="video-action">
      <button
        type="button"
        className="btn btn-secondary save-button"
        onClick={() => onToggleSaved(video)}
        aria-pressed={saved}
      >
        {saved ? 'Saved' : 'Save'}
      </button>
      </div>
    </article>
  );
}
