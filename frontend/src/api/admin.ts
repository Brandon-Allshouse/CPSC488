// Typed wrappers around the backend's /api/admin endpoints (see the README's "Admin API" table).
// Every one of them returns 403 unless the logged-in user is an admin.

import { ApiError, OFFLINE_MESSAGE, request } from './client';

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
  // Where the account was created and last logged in from. null for accounts from before IPs
  // were recorded. Personal data: only admins ever get these.
  signupIp: string | null;
  lastLoginIp: string | null;
  lastLoginAt: string | null;
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
  action: 'ban_user' | 'unban_user' | 'ban_video' | 'unban_video' | 'remove_topic';
  target: string;
  reason: string | null;
  // where the admin did it from
  ip: string | null;
  createdAt: string;
}

// A topic a user added with the AI topic finder. addedBy is null if that account was deleted;
// picks is how many users have it as an interest. The name came from the LLM: plain text only.
export interface AiTopic {
  id: number;
  name: string;
  /** null for a subject */
  subject: string | null;
  addedBy: string | null;
  createdAt: string;
  picks: number;
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

// Only names the backend builds (AuditLogExport.fileName) are used; anything else falls back.
const SAFE_FILE_NAME = /filename="([A-Za-z0-9._-]{1,100})"/;

/**
 * Downloads the whole admin audit log as a CSV file. This can't use request() because the answer
 * is a file, not JSON, but errors come back as the usual {"error": "..."} and become an ApiError.
 */
export async function downloadAuditLog(): Promise<void> {
  let res: Response;
  try {
    res = await fetch('/api/admin/actions/export', { headers: { Accept: 'text/csv' }, credentials: 'same-origin' });
  } catch {
    throw new ApiError(0, OFFLINE_MESSAGE);
  }
  if (!res.ok) {
    const data = await res.json().catch(() => null);
    const message = data && typeof data.error === 'string' && data.error.length <= 300 ? data.error : `Download failed (${res.status})`;
    throw new ApiError(res.status, message);
  }
  const fileName = SAFE_FILE_NAME.exec(res.headers.get('Content-Disposition') ?? '')?.[1] ?? 'brainfeed-audit-log.csv';
  const url = URL.createObjectURL(await res.blob());
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

export async function fetchAiTopics(): Promise<AiTopic[]> {
  const { topics } = await request<{ topics: AiTopic[] }>('/api/admin/topics');
  return topics;
}

/** Removes an AI-added topic (and its topics, if it's a subject) and blocks the name. */
export async function removeAiTopic(topicId: number, reason: string): Promise<void> {
  await request<void>(`/api/admin/topics/${topicId}`, { method: 'DELETE', body: JSON.stringify({ reason }) });
}
