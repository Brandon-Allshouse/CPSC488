import { afterEach, describe, expect, it, vi } from 'vitest';
import { fetchFeed, fetchMyInterests, fetchTopics, saveMyInterests } from './feed';

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
});
