// Typed wrappers around /api/me/watch-history (see the README's "Watch history API" table).
// All of them need a login and only ever touch the signed-in user's own history.

import { request } from './client';

/** A video the user pressed play on. Same fields as a liked video, plus when it was watched. */
export interface WatchedVideo {
  youtubeId: string;
  title: string;
  creator: string;
  watchedAt: string;
}

export interface WatchHistory {
  videos: WatchedVideo[];
  /** How many videos the history keeps (older ones drop off). */
  limit: number;
}

/** The signed-in user's history, newest first. */
export async function fetchWatchHistory(): Promise<WatchHistory> {
  return request<WatchHistory>('/api/me/watch-history');
}

/** Records that the user pressed play, or moves the video back to the top if it's already there. */
export async function recordWatch(youtubeId: string): Promise<void> {
  await request<void>(`/api/me/watch-history/${encodeURIComponent(youtubeId)}`, { method: 'PUT' });
}

/** Takes one video out of the history. */
export async function removeFromWatchHistory(youtubeId: string): Promise<void> {
  await request<void>(`/api/me/watch-history/${encodeURIComponent(youtubeId)}`, { method: 'DELETE' });
}

/** Deletes the whole history. */
export async function clearWatchHistory(): Promise<void> {
  await request<void>('/api/me/watch-history', { method: 'DELETE' });
}
