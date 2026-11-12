import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from './client';
import { answerVideoQuestion, fetchVideoStudy } from './study';

vi.mock('./client', () => ({ request: vi.fn() }));
beforeEach(() => vi.resetAllMocks());

describe('study api', () => {
  it('asks for one video summary through the shared request helper', async () => {
    vi.mocked(request).mockResolvedValue({ status: 'pending' });
    expect(await fetchVideoStudy('dQw4w9WgXcQ')).toEqual({ status: 'pending' });
    expect(request).toHaveBeenCalledWith('/api/videos/dQw4w9WgXcQ/study');
  });

  it('encodes the video id so it cannot change the path', async () => {
    vi.mocked(request).mockResolvedValue({ status: 'unavailable' });
    await fetchVideoStudy('../admin/x');
    expect(request).toHaveBeenCalledWith('/api/videos/..%2Fadmin%2Fx/study');
  });

  it('passes errors on to the summary box', async () => {
    vi.mocked(request).mockRejectedValue(new Error('offline'));
    await expect(fetchVideoStudy('dQw4w9WgXcQ')).rejects.toThrow('offline');
  });
});

describe('answering a quiz question', () => {
  it('posts the question and choice as JSON', async () => {
    const result = { correct: true, answerIndex: 1, explanation: 'Because.', signedIn: true, counted: true, pointsEarned: 10, streak: 1 };
    vi.mocked(request).mockResolvedValue(result);
    expect(await answerVideoQuestion('dQw4w9WgXcQ', 0, 1)).toEqual(result);
    expect(request).toHaveBeenCalledWith('/api/videos/dQw4w9WgXcQ/study/answers', {
      method: 'POST',
      body: JSON.stringify({ question: 0, choice: 1 }),
    });
  });

  it('encodes the video id here too', async () => {
    vi.mocked(request).mockResolvedValue({});
    await answerVideoQuestion('../me/x', 0, 0);
    expect(request).toHaveBeenCalledWith('/api/videos/..%2Fme%2Fx/study/answers', expect.anything());
  });

  it('passes errors on so the quiz can say so', async () => {
    vi.mocked(request).mockRejectedValue(new Error('too fast'));
    await expect(answerVideoQuestion('dQw4w9WgXcQ', 0, 1)).rejects.toThrow('too fast');
  });
});
