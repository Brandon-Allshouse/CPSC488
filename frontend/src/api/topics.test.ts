import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from './client';
import { addSuggestedTopic, suggestTopics } from './topics';

vi.mock('./client', () => ({ request: vi.fn() }));
beforeEach(() => vi.resetAllMocks());

describe('topics api', () => {
  it('sends only the typed text when asking for suggestions', async () => {
    vi.mocked(request).mockResolvedValue({ matches: [], ideas: [] });
    expect(await suggestTopics('black holes')).toEqual({ matches: [], ideas: [] });
    expect(request).toHaveBeenCalledWith('/api/me/topic-suggestions', { method: 'POST', body: '{"text":"black holes"}' });
  });

  it('adds a suggestion by its key only, never by name', async () => {
    const topic = { id: 121, name: 'Black Holes', parentId: 6, ai: true };
    vi.mocked(request).mockResolvedValue({ topics: [topic] });
    expect(await addSuggestedTopic('abc123')).toEqual([topic]);
    expect(request).toHaveBeenCalledWith('/api/me/topics', { method: 'POST', body: '{"key":"abc123"}' });
  });

  it('passes the backend error on, so the finder can show it', async () => {
    vi.mocked(request).mockRejectedValue(new Error('The topic finder is busy. Try again in a minute.'));
    await expect(suggestTopics('black holes')).rejects.toThrow('The topic finder is busy. Try again in a minute.');
  });

  it('sends text with quotes in it as proper JSON', async () => {
    vi.mocked(request).mockResolvedValue({ matches: [], ideas: [] });
    await suggestTopics('"} , "admin": true');
    const body = vi.mocked(request).mock.calls[0][1]!.body as string;
    expect(JSON.parse(body)).toEqual({ text: '"} , "admin": true' });
  });
});
