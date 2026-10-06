import { request } from './client';
import type { Video } from './feed';

export async function fetchSavedVideos(): Promise<Video[]> {
  const result = await request<{ videos: Video[] }>('/api/me/saved-videos');
  return result.videos;
}

export async function saveVideo(youtubeId: string): Promise<void> {
  await request<void>(
    `/api/me/saved-videos/${encodeURIComponent(youtubeId)}`,
    {
      method: 'POST',
    },
  );
}

export async function removeSavedVideo(youtubeId: string): Promise<void> {
  await request<void>(
    `/api/me/saved-videos/${encodeURIComponent(youtubeId)}`,
    {
      method: 'DELETE',
    },
  );
}
