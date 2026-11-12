// The Study notebook's videos (see the README's "Study notebook API"). All need a login.

import { request } from './client';
import type { Video } from './feed';

/** The videos in the signed-in user's Study notebook. */
export async function fetchSavedVideos(): Promise<Video[]> {
  const result = await request<{ videos: Video[] }>('/api/me/saved-videos');
  return result.videos;
}

/** Adds a video to the Study notebook. */
export async function saveVideo(youtubeId: string): Promise<void> {
  await request<void>(
    `/api/me/saved-videos/${encodeURIComponent(youtubeId)}`,
    {
      method: 'POST',
    },
  );
}

/** Takes a video out of the Study notebook, along with its note. */
export async function removeSavedVideo(youtubeId: string): Promise<void> {
  await request<void>(
    `/api/me/saved-videos/${encodeURIComponent(youtubeId)}`,
    {
      method: 'DELETE',
    },
  );
}
