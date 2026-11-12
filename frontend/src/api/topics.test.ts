import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, request } from './client';
import { addTopic } from './topics';

vi.mock('./client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./client')>()),
  request: vi.fn(),
}));
beforeEach(() => vi.resetAllMocks());

const blackHoles = { topic: { id: 121, name: 'Black Holes', parentId: 6, ai: true }, created: true, similar: false };

describe('topics api', () => {
  it('sends just the typed name', async () => {
    vi.mocked(request).mockResolvedValue(blackHoles);
    expect(await addTopic('Black Holes')).toEqual(blackHoles);
    expect(request).toHaveBeenCalledWith('/api/me/topics', { method: 'POST', body: '{"name":"Black Holes"}' });
  });

  it('only sends asTyped when asked to skip close matches', async () => {
    vi.mocked(request).mockResolvedValue(blackHoles);
    await addTopic('Generics', true);
    expect(request).toHaveBeenCalledWith('/api/me/topics', { method: 'POST', body: '{"name":"Generics","asTyped":true}' });
  });

  it('passes backend errors on, so the box can show them', async () => {
    vi.mocked(request).mockRejectedValue(new ApiError(429, "You've added 10 new topics today. Try again tomorrow."));
    await expect(addTopic('Comets')).rejects.toMatchObject({ status: 429, message: expect.stringContaining('Try again tomorrow') });
  });

  it('sends names with quotes in them as proper JSON', async () => {
    vi.mocked(request).mockResolvedValue(blackHoles);
    await addTopic('"} , "admin": true');
    const body = vi.mocked(request).mock.calls[0][1]!.body as string;
    expect(JSON.parse(body)).toEqual({ name: '"} , "admin": true' });
  });
});
