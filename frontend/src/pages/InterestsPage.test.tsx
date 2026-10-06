// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchTopics } from '../api/feed';
import InterestsPage from './InterestsPage';

vi.mock('../api/feed', () => ({ fetchTopics: vi.fn() }));

const topics = [
  { id: 1, name: 'Math', parentId: null },
  { id: 2, name: 'History', parentId: null },
  { id: 3, name: 'Biology', parentId: null },
  { id: 4, name: 'Algebra 2', parentId: 1 },
];

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(fetchTopics).mockResolvedValue(topics);
});

afterEach(() => {
  cleanup();
});

function chip(name: string) {
  return screen.getByRole('button', { name });
}

describe('InterestsPage', () => {
  it('shows the brain logo in the top-left corner', () => {
    render(<InterestsPage initial={[]} onSave={vi.fn()} isGuest={false} />);
    expect(screen.getByRole('img', { name: 'BrainFeed brain logo' }).className).toBe('auth-logo');
  });

  it('lists the topics from the backend', async () => {
    render(<InterestsPage initial={[]} onSave={vi.fn()} isGuest={false} />);
    expect(await screen.findByRole('button', { name: 'All of Math' })).toBeTruthy();
    expect(chip('All of History')).toBeTruthy();
    expect(chip('All of Biology')).toBeTruthy();
  });

  it('puts sub-subjects under their subject', async () => {
    const { container } = render(<InterestsPage initial={[]} onSave={vi.fn()} isGuest={false} />);
    await screen.findByRole('button', { name: 'All of Math' });

    // one section per subject, and Algebra 2 sits in the Math one
    const sections = container.querySelectorAll('details');
    expect(sections.length).toBe(3);
    const algebra = chip('Algebra 2');
    expect(algebra.closest('details')!.querySelector('summary')!.textContent).toBe('Math');
  });

  it('can pick just a sub-subject and save it', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    render(<InterestsPage initial={[]} onSave={onSave} isGuest={false} />);
    await screen.findByRole('button', { name: 'Algebra 2' });

    fireEvent.click(chip('Algebra 2'));
    expect(chip('All of Math').getAttribute('aria-pressed')).toBe('false');
    fireEvent.click(chip('Show my feed'));

    expect(onSave).toHaveBeenCalledWith([4]);
  });

  it('shows how many are picked in each subject', async () => {
    const { container } = render(<InterestsPage initial={[]} onSave={vi.fn()} isGuest={false} />);
    await screen.findByRole('button', { name: 'Algebra 2' });

    fireEvent.click(chip('All of Math'));
    fireEvent.click(chip('Algebra 2'));
    expect(container.querySelector('details summary')!.textContent).toBe('Math · 2 picked');
  });

  it('opens the subjects that already have picks', async () => {
    const { container } = render(<InterestsPage initial={[4]} onSave={vi.fn()} isGuest={false} />);
    await screen.findByRole('button', { name: 'Algebra 2' });

    const [math, history] = container.querySelectorAll('details');
    expect(math.open).toBe(true);
    expect(history.open).toBe(false);
  });

  it('cannot save until a topic is picked', async () => {
    render(<InterestsPage initial={[]} onSave={vi.fn()} isGuest={false} />);
    await screen.findByRole('button', { name: 'All of Math' });
    expect((chip('Pick at least one topic') as HTMLButtonElement).disabled).toBe(true);

    fireEvent.click(chip('All of Math'));
    expect((chip('Show my feed') as HTMLButtonElement).disabled).toBe(false);
  });

  it('clicking a topic toggles it on and off', async () => {
    render(<InterestsPage initial={[]} onSave={vi.fn()} isGuest={false} />);
    await screen.findByRole('button', { name: 'All of Math' });

    fireEvent.click(chip('All of Math'));
    expect(chip('All of Math').getAttribute('aria-pressed')).toBe('true');
    fireEvent.click(chip('All of Math'));
    expect(chip('All of Math').getAttribute('aria-pressed')).toBe('false');
  });

  it('starts with the current interests picked', async () => {
    render(<InterestsPage initial={[2]} onSave={vi.fn()} isGuest={false} />);
    expect((await screen.findByRole('button', { name: 'All of History' })).getAttribute('aria-pressed')).toBe('true');
    expect(chip('All of Math').getAttribute('aria-pressed')).toBe('false');
  });

  it('saves the picked topics', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    render(<InterestsPage initial={[]} onSave={onSave} isGuest={false} />);
    await screen.findByRole('button', { name: 'All of Math' });

    fireEvent.click(chip('All of Math'));
    fireEvent.click(chip('All of Biology'));
    fireEvent.click(chip('Show my feed'));

    expect(onSave).toHaveBeenCalledWith([1, 3]);
  });

  it('shows an error if saving fails', async () => {
    const onSave = vi.fn().mockRejectedValue(new Error('Not logged in.'));
    render(<InterestsPage initial={[1]} onSave={onSave} isGuest={false} />);
    await screen.findByRole('button', { name: 'All of Math' });

    fireEvent.click(chip('Show my feed'));
    expect((await screen.findByRole('alert')).textContent).toBe('Not logged in.');
  });

  it('shows an error if the topics cannot be loaded', async () => {
    vi.mocked(fetchTopics).mockRejectedValue(new Error('down'));
    render(<InterestsPage initial={[]} onSave={vi.fn()} isGuest={false} />);
    expect((await screen.findByRole('alert')).textContent).toContain('Could not load topics');
  });

  it('only shows cancel when there is something to go back to', async () => {
    const onCancel = vi.fn();
    const { rerender } = render(<InterestsPage initial={[1]} onSave={vi.fn()} onCancel={onCancel} isGuest={false} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Cancel' }));
    expect(onCancel).toHaveBeenCalled();

    rerender(<InterestsPage initial={[]} onSave={vi.fn()} isGuest={false} />);
    expect(screen.queryByRole('button', { name: 'Cancel' })).toBeNull();
  });

  it('tells guests their picks are not saved', async () => {
    render(<InterestsPage initial={[]} onSave={vi.fn()} isGuest={true} />);
    expect(await screen.findByText(/as a guest they reset/)).toBeTruthy();
  });
});
