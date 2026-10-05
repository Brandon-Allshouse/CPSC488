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
    expect(screen.getByRole('region', { name: 'Video summary' })).toBeTruthy();
    expect(screen.getByText('Video Summary').tagName).toBe('STRONG');
    expect(screen.getByText(/video summary unable to be rendered at this time/)).toBeTruthy();
    expect(container.querySelector('img')?.getAttribute('src')).toBe('https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg');
    expect(container.querySelector('iframe')).toBeNull();
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
});
