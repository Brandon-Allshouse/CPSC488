// Typed wrappers around the backend's /api/me/playlists endpoints (see the README's
// "Playlists API" table). All of them need a login.

import { request } from './client';
import type { SavedVideo } from './feed';

// Matches Playlist.java. watchLater is true for the built-in "Watch later" playlist, which every
// user has and which can't be deleted. Videos are newest-added first.
export interface Playlist {
  id: number;
  name: string;
  watchLater: boolean;
  videos: SavedVideo[];
}

/** The logged-in user's playlists: Watch later first, then the rest by name. */
export async function fetchMyPlaylists(): Promise<Playlist[]> {
  const { playlists } = await request<{ playlists: Playlist[] }>('/api/me/playlists');
  return playlists;
}

/** Makes a new, empty playlist. Fails with 409 if the name is already used. */
export async function createPlaylist(name: string): Promise<Playlist> {
  const { playlist } = await request<{ playlist: Playlist }>('/api/me/playlists', {
    method: 'POST',
    body: JSON.stringify({ name }),
  });
  return playlist;
}

export async function deletePlaylist(playlistId: number): Promise<void> {
  await request<void>(`/api/me/playlists/${playlistId}`, { method: 'DELETE' });
}

/** Adds a video to a playlist. Adding one that's already there is fine. */
export async function addToPlaylist(playlistId: number, youtubeId: string): Promise<void> {
  await request<void>(`/api/me/playlists/${playlistId}/videos/${encodeURIComponent(youtubeId)}`, { method: 'PUT' });
}

export async function removeFromPlaylist(playlistId: number, youtubeId: string): Promise<void> {
  await request<void>(`/api/me/playlists/${playlistId}/videos/${encodeURIComponent(youtubeId)}`, {
    method: 'DELETE',
  });
}