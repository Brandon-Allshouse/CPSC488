// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import type { Topic } from '../api/feed';
import { addTopic } from '../api/topics';
import TopicFinder from './TopicFinder';

vi.mock('../api/topics', () => ({ addTopic: vi.fn() }));

const topics: Topic[] = [
  { id: 1, name: 'Math', parentId: null },
  { id: 2, name: 'Space', parentId: null },
  { id: 3, name: 'Algebra 2', parentId: 1 },
  { id: 4, name: 'Stars and Galaxies', parentId: 2 },
  { id: 5, name: 'Genetics', parentId: null },
];

const onPick = vi.fn();
const onUnpick = vi.fn();
const onAdded = vi.fn();
const onQueryChange = vi.fn();

function renderFinder({ selected = new Set<number>(), isGuest = false } = {}) {
  return render(
    <TopicFinder
      topics={topics}
      selected={selected}
      onPick={onPick}
      onUnpick={onUnpick}
      onAdded={onAdded}
      onQueryChange={onQueryChange}
      isGuest={isGuest}
    />,
  );
}

function box() {
  return screen.getByRole('combobox', { name: 'Type a topic' }) as HTMLInputElement;
}

function type(text: string) {
  fireEvent.change(box(), { target: { value: text } });
}

function enter(text: string) {
  type(text);
  fireEvent.submit(box().closest('form')!);
}

beforeEach(() => {
  vi.resetAllMocks();
});

afterEach(() => {
  cleanup();
});

describe('TopicFinder', () => {
  it('suggests topics we have while typing, without asking the backend', () => {
    renderFinder();
    type('alg');
    expect(screen.getByRole('option', { name: /Algebra 2/ }).textContent).toContain('in Math');
    expect(box().getAttribute('aria-expanded')).toBe('true');
    expect(onQueryChange).toHaveBeenLastCalledWith('alg');
    expect(addTopic).not.toHaveBeenCalled();
  });

  it('picks a topic we have right away on Enter', () => {
    renderFinder();
    enter('algebra 2');
    expect(onPick).toHaveBeenCalledWith(3);
    expect(screen.getByRole('status').textContent).toContain('Added Algebra 2 to your topics.');
    expect(box().value).toBe('');
    expect(addTopic).not.toHaveBeenCalled();
  });

  it('says so when you already follow it', () => {
    renderFinder({ selected: new Set([3]) });
    enter('Algebra 2');
    expect(onPick).not.toHaveBeenCalled();
    expect(screen.getByRole('status').textContent).toContain('You already follow Algebra 2.');
  });

  it('clicking a suggestion picks it', () => {
    renderFinder();
    type('stars');
    fireEvent.click(screen.getByRole('option', { name: /Stars and Galaxies/ }));
    expect(onPick).toHaveBeenCalledWith(4);
  });

  it('arrow keys choose a suggestion and Enter picks it', () => {
    renderFinder();
    type('a');
    fireEvent.keyDown(box(), { key: 'ArrowDown' });
    const first = screen.getAllByRole('option')[0];
    expect(first.getAttribute('aria-selected')).toBe('true');
    expect(box().getAttribute('aria-activedescendant')).toBe(first.id);
    fireEvent.submit(box().closest('form')!);
    expect(onPick).toHaveBeenCalledTimes(1);
  });

  it('adds a new topic through the backend and passes it up', async () => {
    const blackHoles = { id: 121, name: 'Black Holes', parentId: 2, ai: true };
    vi.mocked(addTopic).mockResolvedValue({ topic: blackHoles, created: true, similar: false });
    renderFinder();
    enter('  black   holes ');
    expect(addTopic).toHaveBeenCalledWith('black holes', false);
    expect(await screen.findByText(/Added Black Holes to your topics in Space\. Finding its videos now\./)).toBeTruthy();
    expect(onAdded).toHaveBeenCalledWith(blackHoles);
  });

  it('a close match can be swapped for what was typed', async () => {
    vi.mocked(addTopic)
      .mockResolvedValueOnce({ topic: topics[4], created: false, similar: true })
      .mockResolvedValueOnce({ topic: { id: 130, name: 'Generics', parentId: 1, ai: true }, created: true, similar: false });
    renderFinder();
    enter('Generics');
    expect(await screen.findByText('Added Genetics to your topics.')).toBeTruthy();
    expect(onPick).toHaveBeenCalledWith(5);

    fireEvent.click(screen.getByRole('button', { name: 'Add “Generics” instead' }));
    expect(onUnpick).toHaveBeenCalledWith(5);
    expect(addTopic).toHaveBeenLastCalledWith('Generics', true);
    expect(await screen.findByText(/Added Generics to your topics/)).toBeTruthy();
  });

  it('checks the name before sending anything', () => {
    renderFinder();
    enter('one two three four');
    expect(screen.getByRole('alert').textContent).toContain('one to three words');
    enter('<b>');
    expect(screen.getByRole('alert').textContent).toContain('Use letters');
    expect(addTopic).not.toHaveBeenCalled();
  });

  it('the Add button is off while the box is empty', () => {
    renderFinder();
    expect((screen.getByRole('button', { name: 'Add' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('shows the backend message when a limit is hit', async () => {
    vi.mocked(addTopic).mockRejectedValue(new ApiError(429, "You've added 10 new topics today. Try again tomorrow."));
    renderFinder();
    enter('Comets');
    expect((await screen.findByRole('alert')).textContent).toContain('Try again tomorrow');
    expect(onAdded).not.toHaveBeenCalled();
    // what they typed stays, so they can fix it or try later
    expect(box().value).toBe('Comets');
  });

  it('shows the backend message for a blocked topic or a network problem', async () => {
    vi.mocked(addTopic).mockRejectedValueOnce(new ApiError(409, "That topic can't be added."));
    renderFinder();
    enter('Something Bad');
    expect((await screen.findByRole('alert')).textContent).toContain("can't be added");

    vi.mocked(addTopic).mockRejectedValueOnce(new ApiError(0, "Couldn't reach BrainFeed. Check your connection and try again."));
    enter('Comets');
    expect((await screen.findByRole('alert')).textContent).toContain("Couldn't reach BrainFeed");
  });

  it('says it is adding and blocks a second submit while waiting', async () => {
    let finish: (value: Awaited<ReturnType<typeof addTopic>>) => void = () => {};
    vi.mocked(addTopic).mockReturnValue(new Promise((resolve) => (finish = resolve)));
    renderFinder();
    enter('Comets');
    expect(screen.getByRole('status').textContent).toContain('Adding the topic');
    expect((screen.getByRole('button', { name: 'Adding…' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.submit(box().closest('form')!);
    expect(addTopic).toHaveBeenCalledTimes(1);

    finish({ topic: { id: 140, name: 'Comets', parentId: 2, ai: true }, created: true, similar: false });
    expect(await screen.findByText(/Added Comets/)).toBeTruthy();
  });

  it('guests can pick topics we have but are asked to log in for new ones', () => {
    renderFinder({ isGuest: true });
    enter('math');
    expect(onPick).toHaveBeenCalledWith(1);
    enter('Comets');
    expect(screen.getByRole('alert').textContent).toContain('Log in to add new topics');
    expect(addTopic).not.toHaveBeenCalled();
  });

  it('shows typed and backend names as plain text, not HTML', async () => {
    vi.mocked(addTopic).mockResolvedValue({
      topic: { id: 150, name: '<img src=x onerror=alert(1)>', parentId: 2, ai: true },
      created: true,
      similar: false,
    });
    const { container } = renderFinder();
    enter('Comets');
    expect(await screen.findByText(/<img src=x onerror=alert\(1\)>/)).toBeTruthy();
    expect(container.querySelector('img')).toBeNull();
  });
});
