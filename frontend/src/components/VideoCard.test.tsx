// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Video, VideoFeedback } from '../api/feed';
import { scrollInto, useFakeIntersectionObserver } from '../test/fakeIntersectionObserver';
import VideoCard, { videoCardId } from './VideoCard';

const video: Video = {
  youtubeId: 'dQw4w9WgXcQ',
  title: 'How Plants Grow',
  channelTitle: 'Test Channel',
  topicId: 1,
  publishedAt: '2026-01-15T10:00:00Z',
};
const onFeedbackChange = vi.fn().mockResolvedValue(undefined);

function renderVideoCard(videoToRender = video, feedback: VideoFeedback | null = null) {
  return render(<VideoCard video={videoToRender} feedback={feedback} onFeedbackChange={onFeedbackChange} />);
}

beforeEach(() => {
  vi.clearAllMocks();
  useFakeIntersectionObserver();
});

afterEach(() => {
  cleanup();
});

describe('VideoCard', () => {
  it('shows the title, channel and thumbnail, but no player yet', () => {
    const { container } = renderVideoCard();
    expect(screen.getByText('How Plants Grow')).toBeTruthy();
    expect(screen.getByText('Test Channel')).toBeTruthy();
    const summaryBox = screen.getByRole('region', { name: 'Video summary' });
    expect(summaryBox).toBeTruthy();
    expect(screen.getByText('Video Summary').tagName).toBe('STRONG');
    expect(summaryBox.textContent).not.toMatch(/unable to be rendered/i);
    expect(summaryBox.textContent!.indexOf('Test Channel')).toBeLessThan(
      summaryBox.textContent!.indexOf('Video Summary'),
    );
    expect(container.querySelector('img')?.getAttribute('src')).toBe('https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg');
    expect(container.querySelector('iframe')).toBeNull();
  });

  it('shows the saved choice and sends toggle changes to its handler', async () => {
    const result = renderVideoCard();
    const like = screen.getByRole('button', { name: 'I liked this video' });
    const dislike = screen.getByRole<HTMLButtonElement>('button', { name: "I didn't really like this video" });

    expect(screen.getByRole('tooltip', { name: 'I liked this video' })).toBeTruthy();
    expect(screen.getByRole('tooltip', { name: "I didn't really like this video" })).toBeTruthy();
    expect(like.getAttribute('aria-pressed')).toBe('false');
    expect(dislike.getAttribute('aria-pressed')).toBe('false');

    fireEvent.click(like);
    expect(onFeedbackChange).toHaveBeenLastCalledWith(video.youtubeId, 'liked');
    await waitFor(() => expect(dislike.disabled).toBe(false));
    result.rerender(
      <VideoCard video={video} feedback="liked" onFeedbackChange={onFeedbackChange} />,
    );
    expect(screen.getByRole('button', { name: 'I liked this video' }).getAttribute('aria-pressed')).toBe('true');
    fireEvent.click(screen.getByRole('button', { name: "I didn't really like this video" }));
    await waitFor(() => expect(onFeedbackChange).toHaveBeenLastCalledWith(video.youtubeId, 'disliked'));
    result.rerender(
      <VideoCard video={video} feedback="disliked" onFeedbackChange={onFeedbackChange} />,
    );
    expect(screen.getByRole('button', { name: 'I liked this video' }).getAttribute('aria-pressed')).toBe('false');
    fireEvent.click(screen.getByRole('button', { name: "I didn't really like this video" }));
    await waitFor(() => expect(onFeedbackChange).toHaveBeenLastCalledWith(video.youtubeId, null));
  });

  it('keeps a separate thumbs choice for each video', () => {
    // the feed shows lots of cards at once, so liking one shouldn't touch the others
    render(
      <>
        <VideoCard video={video} feedback={null} onFeedbackChange={onFeedbackChange} />
        <VideoCard
          video={{ ...video, youtubeId: 'abcdefghijk', title: 'How Rocks Form' }}
          feedback={null}
          onFeedbackChange={onFeedbackChange}
        />
      </>,
    );
    const [firstLike, secondLike] = screen.getAllByRole('button', { name: 'I liked this video' });

    fireEvent.click(firstLike);
    expect(onFeedbackChange).toHaveBeenCalledWith(video.youtubeId, 'liked');
    expect(onFeedbackChange).not.toHaveBeenCalledWith('abcdefghijk', 'liked');
    expect(firstLike.getAttribute('aria-pressed')).toBe('false');
    expect(secondLike.getAttribute('aria-pressed')).toBe('false');
    // each tooltip needs its own id so screen readers read the right one
    expect(firstLike.getAttribute('aria-describedby')).not.toBe(secondLike.getAttribute('aria-describedby'));
  });

  it('loads the privacy-enhanced YouTube player when play is pressed', () => {
    const { container } = renderVideoCard();
    fireEvent.click(screen.getByRole('button', { name: 'Play: How Plants Grow' }));

    const iframe = container.querySelector('iframe')!;
    expect(iframe.getAttribute('src')).toBe('https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ?autoplay=1&rel=0');
    expect(iframe.getAttribute('sandbox')).toContain('allow-scripts');
    expect(iframe.getAttribute('sandbox')).not.toContain('allow-top-navigation');
    expect(iframe.getAttribute('referrerpolicy')).toBe('strict-origin-when-cross-origin');
  });

  it('stops the video when it scrolls out of view', () => {
    const { container } = renderVideoCard();
    fireEvent.click(screen.getByRole('button', { name: /Play/ }));
    expect(container.querySelector('iframe')).not.toBeNull();

    scrollInto(container.querySelector('article')!, 0);
    expect(container.querySelector('iframe')).toBeNull();
  });

  it('keeps playing while still mostly on screen', () => {
    const { container } = renderVideoCard();
    fireEvent.click(screen.getByRole('button', { name: /Play/ }));

    scrollInto(container.querySelector('article')!, 0.8);
    expect(container.querySelector('iframe')).not.toBeNull();
  });

  it('shows titles with HTML in them as plain text', () => {
    // titles come from YouTube, so they should never be treated as HTML
    const { container } = renderVideoCard({ ...video, title: '<b>not bold</b>' });
    expect(screen.getByText('<b>not bold</b>')).toBeTruthy();
    expect(container.querySelector('b')).toBeNull();
  });

  it('shows channel names with HTML in them as plain text', () => {
    // channel names come from YouTube too
    const { container } = renderVideoCard({ ...video, channelTitle: '<i>Test Channel</i>' });
    expect(screen.getByText('<i>Test Channel</i>')).toBeTruthy();
    expect(container.querySelector('i')).toBeNull();
  });

  it('gives each card an id made from its video id, so the feed can scroll to it', () => {
    const { container } = renderVideoCard();
    expect(container.querySelector('article')!.id).toBe('video-dQw4w9WgXcQ');
    expect(videoCardId('dQw4w9WgXcQ')).toBe('video-dQw4w9WgXcQ');
  });

  it('encodes anything unusual in the id', () => {
    // the backend only stores real YouTube ids, so this is a second safeguard
    expect(videoCardId('a"b c')).toBe('video-a%22b%20c');
  });

  it('outlines the card only while highlighted', () => {
    const { container, rerender } = renderVideoCard();
    const article = container.querySelector('article')!;
    expect(article.hasAttribute('data-highlighted')).toBe(false);

    rerender(<VideoCard video={video} feedback={null} onFeedbackChange={onFeedbackChange} highlighted />);
    expect(article.getAttribute('data-highlighted')).toBe('true');
    expect(container.querySelector('.video-frame')!.classList.contains('ring-primary')).toBe(true);
  });
});
