// Typed wrappers around the backend's topic, interest and feed endpoints (see the README's
// "Feed API" table).

import { request } from './client';

// Matches Topic.java on the backend.
export interface Topic {
  id: number;
  name: string;
}

// Matches Video.java. title and channelTitle come from YouTube: only ever render them as plain
// text (normal JSX does this), never as HTML.
export interface Video {
  youtubeId: string;
  title: string;
  channelTitle: string;
  topicId: number;
  publishedAt: string;
}

export interface FeedPage {
  videos: Video[];
  /** null when there are no more videos. */
  nextPage: number | null;
}

export async function fetchTopics(): Promise<Topic[]> {
  const { topics } = await request<{ topics: Topic[] }>('/api/topics');
  return topics;
}

/** The logged-in user's saved topic ids (empty if they haven't picked any yet). */
export async function fetchMyInterests(): Promise<number[]> {
  const { topicIds } = await request<{ topicIds: number[] }>('/api/me/interests');
  return topicIds;
}

export async function saveMyInterests(topicIds: number[]): Promise<number[]> {
  const res = await request<{ topicIds: number[] }>('/api/me/interests', {
    method: 'PUT',
    body: JSON.stringify({ topicIds }),
  });
  return res.topicIds;
}

/**
 * One page of the feed. Keep the same `seed` while scrolling so pages don't repeat; a new seed
 * gives a new shuffle.
 */
export async function fetchFeed(topicIds: number[], seed: number, page: number): Promise<FeedPage> {
  const params = new URLSearchParams({ topics: topicIds.join(','), seed: String(seed), page: String(page) });
  return request<FeedPage>(`/api/feed?${params}`);
}
