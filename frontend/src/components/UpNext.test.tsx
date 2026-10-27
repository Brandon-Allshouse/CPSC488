// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import UpNext from './UpNext';

const videos = Array.from({ length: 8 }, (_, i) => ({
  youtubeId: `video${String(i + 1).padStart(6, '0')}`,
  title: `Test video ${i + 1}`,
  channelTitle: 'Test Channel',
}));

afterEach(cleanup);

describe('UpNext', () => {
  it('shows the next five videos after the current one', () => {
    render(<UpNext videos={videos} currentIndex={1} loading={false} onSelect={vi.fn()} />);

    expect(screen.getByText('Video 2 of 8')).toBeTruthy();
    expect(screen.queryByText('Test video 2')).toBeNull();
    expect(screen.getByText('Test video 3')).toBeTruthy();
    expect(screen.getByText('Test video 7')).toBeTruthy();
    expect(screen.queryByText('Test video 8')).toBeNull();
  });

  it('scrolls the feed to a video when it is clicked', () => {
    const onSelect = vi.fn();
    render(<UpNext videos={videos} currentIndex={0} loading={false} onSelect={onSelect} />);

    fireEvent.click(screen.getByText('Test video 3'));
    expect(onSelect).toHaveBeenCalledWith(2);
  });

  it('says when it is loading and when there is nothing left', () => {
    const { rerender } = render(<UpNext videos={videos} currentIndex={7} loading={true} onSelect={vi.fn()} />);
    expect(screen.getByText('Loading more videos…')).toBeTruthy();

    rerender(<UpNext videos={videos} currentIndex={7} loading={false} onSelect={vi.fn()} />);
    expect(screen.getByText(/all caught up/)).toBeTruthy();
  });

  it('suggests subjects to add and adds the one clicked', async () => {
    const onAddTopic = vi.fn().mockResolvedValue(undefined);
    const suggestions = [
      { id: 2, name: 'Biology', parentId: null },
      { id: 3, name: 'History', parentId: null },
    ];
    render(<UpNext videos={videos} currentIndex={0} loading={false} onSelect={vi.fn()} suggestions={suggestions} onAddTopic={onAddTopic} />);

    expect(screen.getByRole('heading', { name: 'Explore more subjects' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Add Biology to your topics' }));
    expect(onAddTopic).toHaveBeenCalledWith(2);
    // the other buttons wait until this one is done, so two can't be added at once
    expect((screen.getByRole('button', { name: 'Add History to your topics' }) as HTMLButtonElement).disabled).toBe(true);
    await vi.waitFor(() => expect((screen.getByRole('button', { name: 'Add History to your topics' }) as HTMLButtonElement).disabled).toBe(false));
  });

  it('shows an error if adding a subject fails', async () => {
    const onAddTopic = vi.fn().mockRejectedValue(new Error('Could not save your interests.'));
    render(
      <UpNext
        videos={videos}
        currentIndex={0}
        loading={false}
        onSelect={vi.fn()}
        suggestions={[{ id: 2, name: 'Biology', parentId: null }]}
        onAddTopic={onAddTopic}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Add Biology to your topics' }));
    expect((await screen.findByRole('alert')).textContent).toBe('Could not save your interests.');
  });

  it('hides the suggestions when there are none', () => {
    render(<UpNext videos={videos} currentIndex={0} loading={false} onSelect={vi.fn()} suggestions={[]} onAddTopic={vi.fn()} />);
    expect(screen.queryByRole('heading', { name: 'Explore more subjects' })).toBeNull();
  });

  it('lists the keyboard shortcuts', () => {
    render(<UpNext videos={[]} currentIndex={0} loading={false} onSelect={vi.fn()} />);
    expect(screen.getByText('Next video')).toBeTruthy();
    expect(screen.getByText('Previous video')).toBeTruthy();
  });
});
