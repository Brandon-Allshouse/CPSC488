// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { Video } from '../api/feed';
import { scrollInto, useFakeIntersectionObserver } from '../test/fakeIntersectionObserver';
import VideoCard from './VideoCard';

const video: Video = {
  youtubeId: 'dQw4w9WgXcQ',
  title: 'How Plants Grow',
  channelTitle: 'Test Channel',
  topicId: 1,
  publishedAt: '2026-01-15T10:00:00Z',
};

beforeEach(() => {
  useFakeIntersectionObserver();
});

afterEach(() => {
  cleanup();
});

describe('VideoCard', () => {
  it('shows the title, channel and thumbnail, but no player yet', () => {
    const { container } = render(<VideoCard video={video} />);
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

  it('shows separate feedback buttons with tooltips and toggleable pressed states', () => {
    render(<VideoCard video={video} />);
    const like = screen.getByRole('button', { name: 'I liked this video' });
    const dislike = screen.getByRole('button', { name: "I didn't really like this video" });

    expect(screen.getByRole('tooltip', { name: 'I liked this video' })).toBeTruthy();
    expect(screen.getByRole('tooltip', { name: "I didn't really like this video" })).toBeTruthy();
    expect(like.getAttribute('aria-pressed')).toBe('false');
    expect(dislike.getAttribute('aria-pressed')).toBe('false');

    fireEvent.click(like);
    expect(like.getAttribute('aria-pressed')).toBe('true');
    fireEvent.click(dislike);
    expect(like.getAttribute('aria-pressed')).toBe('false');
    expect(dislike.getAttribute('aria-pressed')).toBe('true');
    fireEvent.click(dislike);
    expect(dislike.getAttribute('aria-pressed')).toBe('false');
  });

  it('keeps a separate thumbs choice for each video', () => {
    // the feed shows lots of cards at once, so liking one shouldn't touch the others
    render(
      <>
        <VideoCard video={video} />
        <VideoCard video={{ ...video, youtubeId: 'abcdefghijk', title: 'How Rocks Form' }} />
      </>,
    );
    const [firstLike, secondLike] = screen.getAllByRole('button', { name: 'I liked this video' });

    fireEvent.click(firstLike);
    expect(firstLike.getAttribute('aria-pressed')).toBe('true');
    expect(secondLike.getAttribute('aria-pressed')).toBe('false');
    // each tooltip needs its own id so screen readers read the right one
    expect(firstLike.getAttribute('aria-describedby')).not.toBe(secondLike.getAttribute('aria-describedby'));
  });

  it('loads the privacy-enhanced YouTube player when play is pressed', () => {
    const { container } = render(<VideoCard video={video} />);
    fireEvent.click(screen.getByRole('button', { name: 'Play: How Plants Grow' }));

    const iframe = container.querySelector('iframe')!;
    expect(iframe.getAttribute('src')).toBe('https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ?autoplay=1&rel=0');
    expect(iframe.getAttribute('sandbox')).toContain('allow-scripts');
    expect(iframe.getAttribute('sandbox')).not.toContain('allow-top-navigation');
    expect(iframe.getAttribute('referrerpolicy')).toBe('strict-origin-when-cross-origin');
  });

  it('stops the video when it scrolls out of view', () => {
    const { container } = render(<VideoCard video={video} />);
    fireEvent.click(screen.getByRole('button', { name: /Play/ }));
    expect(container.querySelector('iframe')).not.toBeNull();

    scrollInto(container.querySelector('article')!, 0);
    expect(container.querySelector('iframe')).toBeNull();
  });

  it('keeps playing while still mostly on screen', () => {
    const { container } = render(<VideoCard video={video} />);
    fireEvent.click(screen.getByRole('button', { name: /Play/ }));

    scrollInto(container.querySelector('article')!, 0.8);
    expect(container.querySelector('iframe')).not.toBeNull();
  });

  it('shows titles with HTML in them as plain text', () => {
    // titles come from YouTube, so they should never be treated as HTML
    const { container } = render(<VideoCard video={{ ...video, title: '<b>not bold</b>' }} />);
    expect(screen.getByText('<b>not bold</b>')).toBeTruthy();
    expect(container.querySelector('b')).toBeNull();
  });

  it('shows channel names with HTML in them as plain text', () => {
    // channel names come from YouTube too
    const { container } = render(<VideoCard video={{ ...video, channelTitle: '<i>Test Channel</i>' }} />);
    expect(screen.getByText('<i>Test Channel</i>')).toBeTruthy();
    expect(container.querySelector('i')).toBeNull();
  });
});
