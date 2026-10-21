import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  fetchFeed,
  fetchMyInterests,
  fetchMyVideoFeedback,
  fetchTopics,
  saveMyInterests,
  saveMyVideoFeedback,
} from './feed';

function fakeFetch(body: unknown) {
  const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(body), { status: 200 }));
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('feed api', () => {
  it('fetchTopics returns the topic list', async () => {
    const topics = [
      { id: 1, name: 'Math', parentId: null },
      { id: 2, name: 'Algebra 2', parentId: 1 },
    ];
    fakeFetch({ topics });
    expect(await fetchTopics()).toEqual(topics);
  });

  it('fetchMyInterests returns the saved topic ids', async () => {
    const fetchMock = fakeFetch({ topicIds: [1, 2] });
    expect(await fetchMyInterests()).toEqual([1, 2]);
    expect(fetchMock.mock.calls[0][0]).toBe('/api/me/interests');
  });

  it('saveMyInterests sends a PUT with the topic ids', async () => {
    const fetchMock = fakeFetch({ topicIds: [3, 1] });
    expect(await saveMyInterests([3, 1])).toEqual([3, 1]);
    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe('/api/me/interests');
    expect(init.method).toBe('PUT');
    expect(JSON.parse(init.body)).toEqual({ topicIds: [3, 1] });
  });

  it('fetchFeed puts the topics, seed and page in the URL', async () => {
    const fetchMock = fakeFetch({ videos: [], nextPage: null });
    expect(await fetchFeed([1, 2], 42, 3)).toEqual({ videos: [], nextPage: null });
    expect(fetchMock.mock.calls[0][0]).toBe('/api/feed?topics=1%2C2&seed=42&page=3');
  });

  it('fetchMyVideoFeedback returns the signed-in user video lists', async () => {
    const feedback = {
      likedVideos: [{ youtubeId: 'dQw4w9WgXcQ', title: 'Title', creator: 'Creator' }],
      dislikedVideos: [],
    };
    const fetchMock = fakeFetch(feedback);
    expect(await fetchMyVideoFeedback()).toEqual(feedback);
    expect(fetchMock.mock.calls[0][0]).toBe('/api/me/video-feedback');
  });

  it('saves a feedback choice and deletes it when cleared', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);

    await saveMyVideoFeedback('dQw4w9WgXcQ', 'liked');
    await saveMyVideoFeedback('dQw4w9WgXcQ', null);

    expect(fetchMock.mock.calls[0][0]).toBe('/api/me/video-feedback/dQw4w9WgXcQ');
    expect(fetchMock.mock.calls[0][1].method).toBe('PUT');
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ feedback: 'liked' });
    expect(fetchMock.mock.calls[1][1].method).toBe('DELETE');
  });
});
