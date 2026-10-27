// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchTopics } from '../api/feed';
import ProductPitch from './ProductPitch';

vi.mock('../api/feed', () => ({ fetchTopics: vi.fn() }));

// 12 subjects with one sub-topic each, so there are more than the 10 shown as chips.
const topics = Array.from({ length: 12 }, (_, i) => [
  { id: i + 1, name: `Subject ${i + 1}`, parentId: null },
  { id: 100 + i, name: `Part of ${i + 1}`, parentId: i + 1 },
]).flat();

beforeEach(() => {
  vi.mocked(fetchTopics).mockReset().mockResolvedValue(topics);
});

afterEach(cleanup);

describe('ProductPitch', () => {
  it('says what BrainFeed does', () => {
    render(<ProductPitch />);
    expect(screen.getByRole('heading', { level: 2, name: 'Learn with short videos' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'A feed from your topics' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Study notes' })).toBeTruthy();
  });

  it('counts the real subjects and topics and shows the first ten', async () => {
    render(<ProductPitch />);
    expect(await screen.findByText('12 subjects and 12 topics to choose from')).toBeTruthy();

    const chips = screen.getByRole('list', { name: 'Subjects' }).querySelectorAll('li');
    expect(chips).toHaveLength(11);
    expect(chips[10].textContent).toBe('+ 2 more');
  });

  it('leaves the subject list out if topics fail to load', async () => {
    vi.mocked(fetchTopics).mockRejectedValue(new Error('down'));
    render(<ProductPitch />);
    await vi.waitFor(() => expect(fetchTopics).toHaveBeenCalled());
    expect(screen.queryByRole('list', { name: 'Subjects' })).toBeNull();
    // the rest of the pitch still shows
    expect(screen.getByRole('heading', { name: 'Playlists and history' })).toBeTruthy();
  });
});
