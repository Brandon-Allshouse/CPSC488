// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { addSuggestedTopic, suggestTopics, type TopicSuggestions } from '../api/topics';
import TopicFinder, { EXAMPLES, MAX_REQUEST } from './TopicFinder';

vi.mock('../api/topics', () => ({ suggestTopics: vi.fn(), addSuggestedTopic: vi.fn() }));

const suggestions: TopicSuggestions = {
  matches: [{ id: 57, name: 'Stars and Galaxies', parentId: 6 }],
  ideas: [
    { key: 'key-1', name: 'Black Holes', subject: 'Space', newSubject: false },
    { key: 'key-2', name: 'Baking Bread', subject: 'Cooking', newSubject: true },
  ],
};

const onToggle = vi.fn();
const onAdded = vi.fn();

function renderFinder(selected = new Set<number>()) {
  return render(<TopicFinder selected={selected} onToggle={onToggle} onAdded={onAdded} />);
}

function search(text: string) {
  fireEvent.change(screen.getByLabelText('What do you want to learn?'), { target: { value: text } });
  fireEvent.click(screen.getByRole('button', { name: 'Find topics' }));
}

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(suggestTopics).mockResolvedValue(suggestions);
});

afterEach(() => {
  cleanup();
});

describe('TopicFinder', () => {
  it("can't search with an empty box, and caps how much can be typed", () => {
    renderFinder();
    expect((screen.getByRole('button', { name: 'Find topics' }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByLabelText('What do you want to learn?').getAttribute('maxLength')).toBe(String(MAX_REQUEST));
  });

  it('example buttons fill in the box', () => {
    renderFinder();
    fireEvent.click(screen.getByRole('button', { name: EXAMPLES[0] }));
    expect((screen.getByLabelText('What do you want to learn?') as HTMLInputElement).value).toBe(EXAMPLES[0]);
  });

  it('sends the trimmed text and shows matches and new ideas', async () => {
    renderFinder();
    search('  black holes  ');
    expect(await screen.findByText('Already on BrainFeed')).toBeTruthy();
    expect(suggestTopics).toHaveBeenCalledWith('black holes');
    expect(screen.getByRole('button', { name: 'Stars and Galaxies' })).toBeTruthy();
    expect(screen.getByText('Black Holes')).toBeTruthy();
    expect(screen.getByText(/in Cooking \(new subject\)/)).toBeTruthy();
  });

  it('picking a match uses the normal pick toggle', async () => {
    renderFinder(new Set([57]));
    search('black holes');
    const match = await screen.findByRole('button', { name: 'Stars and Galaxies' });
    expect(match.getAttribute('aria-pressed')).toBe('true');
    fireEvent.click(match);
    expect(onToggle).toHaveBeenCalledWith(57);
  });

  it('adds a new topic by its key and passes it up', async () => {
    const added = [{ id: 121, name: 'Black Holes', parentId: 6, ai: true }];
    vi.mocked(addSuggestedTopic).mockResolvedValue(added);
    renderFinder();
    search('black holes');
    fireEvent.click(await screen.findByRole('button', { name: 'Add Black Holes' }));
    expect(await screen.findByRole('button', { name: 'Black Holes added' })).toBeTruthy();
    expect(addSuggestedTopic).toHaveBeenCalledWith('key-1');
    expect(onAdded).toHaveBeenCalledWith(added);
  });

  it('shows the backend message when adding fails', async () => {
    vi.mocked(addSuggestedTopic).mockRejectedValue(new ApiError(429, "You've added 10 topics today. Try again tomorrow."));
    renderFinder();
    search('black holes');
    fireEvent.click(await screen.findByRole('button', { name: 'Add Black Holes' }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.getByText(/added 10 topics today/)).toBeTruthy();
    expect(onAdded).not.toHaveBeenCalled();
  });

  it('shows the backend message when the finder is down', async () => {
    vi.mocked(suggestTopics).mockRejectedValue(new ApiError(503, "The AI topic finder isn't running right now."));
    renderFinder();
    search('black holes');
    expect(await screen.findByText(/isn't running right now/)).toBeTruthy();
  });

  it('says so when nothing was found', async () => {
    vi.mocked(suggestTopics).mockResolvedValue({ matches: [], ideas: [] });
    renderFinder();
    search('asdfgh');
    expect(await screen.findByText(/No topics for that/)).toBeTruthy();
  });

  it('shows AI names as plain text, not HTML', async () => {
    vi.mocked(suggestTopics).mockResolvedValue({
      matches: [],
      ideas: [{ key: 'k', name: '<img src=x onerror=alert(1)>', subject: 'Space', newSubject: false }],
    });
    const { container } = renderFinder();
    search('anything');
    expect(await screen.findByText('<img src=x onerror=alert(1)>')).toBeTruthy();
    expect(container.querySelector('img')).toBeNull();
  });

  it('says it is looking and locks the box while the AI works', async () => {
    let finish: (value: typeof suggestions) => void = () => {};
    vi.mocked(suggestTopics).mockReturnValue(new Promise((resolve) => (finish = resolve)));
    renderFinder();
    search('black holes');

    expect(screen.getByRole('status').textContent).toContain('Looking for topics');
    expect((screen.getByLabelText('What do you want to learn?') as HTMLInputElement).disabled).toBe(true);
    expect((screen.getByRole('button', { name: 'Looking…' }) as HTMLButtonElement).disabled).toBe(true);

    finish(suggestions);
    expect(await screen.findByText('Already on BrainFeed')).toBeTruthy();
    expect(screen.queryByRole('status')).toBeNull();
  });
});
