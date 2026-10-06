import { useEffect, useRef, useState } from 'react';
import type { Video } from '../api/feed';

// youtube-nocookie.com is YouTube's "privacy mode" player, which doesn't set tracking cookies
// until you hit play. If you change either domain, update the CSP in vite.config.ts too.
const EMBED_URL = 'https://www.youtube-nocookie.com/embed/';
const THUMBNAIL_URL = 'https://i.ytimg.com/vi/';

interface Props {
  video: Video;
}

// One video in the feed. Shows the thumbnail until the user presses play, so scrolling past
// videos doesn't load a YouTube player (and its trackers) for every one of them.
export default function VideoCard({ video }: Props) {
  const [playing, setPlaying] = useState(false);
  // Thumbs up/down only lives on screen for now. It isn't sent to the backend, so it resets on
  // reload. Pressing the same button again clears it.
  const [feedback, setFeedback] = useState<'liked' | 'disliked' | null>(null);
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
      <section className="video-summary" aria-label="Video summary">
        {/* These come from YouTube. Normal JSX text is safe; never use dangerouslySetInnerHTML here. */}
        <div className="video-title-row">
          <h2 className="video-title">{video.title}</h2>
          <div className="video-feedback">
            <button
              type="button"
              className="video-feedback-button video-feedback-like"
              aria-label="I liked this video"
              aria-describedby={`like-tooltip-${id}`}
              aria-pressed={feedback === 'liked'}
              onClick={() => setFeedback((current) => (current === 'liked' ? null : 'liked'))}
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M1 21h4V9H1v12zM23 10c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L14.17 1 7.59 7.59C7.22 7.95 7 8.45 7 9v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-2z" />
              </svg>
              <span id={`like-tooltip-${id}`} className="video-feedback-tooltip" role="tooltip">
                I liked this video
              </span>
            </button>
            <button
              type="button"
              className="video-feedback-button video-feedback-dislike"
              aria-label="I didn't really like this video"
              aria-describedby={`dislike-tooltip-${id}`}
              aria-pressed={feedback === 'disliked'}
              onClick={() => setFeedback((current) => (current === 'disliked' ? null : 'disliked'))}
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M1 21h4V9H1v12zM23 10c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L14.17 1 7.59 7.59C7.22 7.95 7 8.45 7 9v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-2z" />
              </svg>
              <span id={`dislike-tooltip-${id}`} className="video-feedback-tooltip" role="tooltip">
                I didn't really like this video
              </span>
            </button>
          </div>
        </div>
        <p className="muted">{video.channelTitle}</p>
        {/* Placeholder heading. The summary text will come from the LLM summaries on the roadmap. */}
        <p className="video-summary-label">
          <strong>Video Summary</strong>
        </p>
      </section>
    </article>
  );
}
