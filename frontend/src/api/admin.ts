// Typed wrappers around the backend's /api/admin endpoints (see the README's "Admin API" table).
// Every one of them returns 403 unless the logged-in user is an admin.

import { request } from './client';

export interface AdminStats {
  users: number;
  bannedUsers: number;
  admins: number;
  videos: number;
  bannedVideos: number;
}

export interface AdminUser {
  id: number;
  username: string;
  email: string;
  createdAt: string;
  admin: boolean;
  bannedAt: string | null;
  banReason: string | null;
}

export interface AdminVideo {
  youtubeId: string;
  title: string;
  channelTitle: string;
  topic: string;
}

export interface BannedVideo {
  youtubeId: string;
  // null when the video was banned before we ever fetched it
  title: string | null;
  reason: string;
  // null if the admin's account was deleted
  bannedBy: string | null;
  bannedAt: string;
}

export interface AdminAction {
  id: number;
  admin: string | null;
  action: 'ban_user' | 'unban_user' | 'ban_video' | 'unban_video';
  target: string;
  reason: string | null;
  createdAt: string;
}

export async function fetchAdminStats(): Promise<AdminStats> {
  return request<AdminStats>('/api/admin/stats');
}

export async function searchUsers(search: string): Promise<AdminUser[]> {
  const { users } = await request<{ users: AdminUser[] }>(`/api/admin/users?${new URLSearchParams({ q: search })}`);
  return users;
}

export async function banUser(userId: number, reason: string): Promise<void> {
  await request<void>(`/api/admin/users/${userId}/ban`, { method: 'PUT', body: JSON.stringify({ reason }) });
}

export async function unbanUser(userId: number): Promise<void> {
  await request<void>(`/api/admin/users/${userId}/ban`, { method: 'DELETE' });
}

export async function searchVideos(search: string): Promise<AdminVideo[]> {
  const { videos } = await request<{ videos: AdminVideo[] }>(
    `/api/admin/videos?${new URLSearchParams({ q: search })}`,
  );
  return videos;
}

export async function fetchBannedVideos(): Promise<BannedVideo[]> {
  const { videos } = await request<{ videos: BannedVideo[] }>('/api/admin/banned-videos');
  return videos;
}

export async function banVideo(youtubeId: string, reason: string): Promise<void> {
  await request<void>(`/api/admin/banned-videos/${encodeURIComponent(youtubeId)}`, {
    method: 'PUT',
    body: JSON.stringify({ reason }),
  });
}

export async function unbanVideo(youtubeId: string): Promise<void> {
  await request<void>(`/api/admin/banned-videos/${encodeURIComponent(youtubeId)}`, { method: 'DELETE' });
}

export async function fetchAdminActions(): Promise<AdminAction[]> {
  const { actions } = await request<{ actions: AdminAction[] }>('/api/admin/actions');
  return actions;
}
