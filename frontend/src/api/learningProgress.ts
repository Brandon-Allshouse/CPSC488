import { ApiError, OFFLINE_MESSAGE, request } from './client';

export type LearningStatus = 'planned' | 'in_progress' | 'completed';
export interface LearningItem {
  progress: {
    youtubeId: string;
    title: string;
    creator: string;
    status: LearningStatus;
    completedAt: string | null;
    reviewedAt: string | null;
    updatedAt: string;
  };
  reviewDueAt: string | null;
  dueForReview: boolean;
}
export interface LearningSnapshot {
  items: LearningItem[];
  summary: {
    total: number;
    planned: number;
    inProgress: number;
    completed: number;
    dueForReview: number;
    completionPercent: number;
  };
  reviewIntervalDays: number;
}

/** Loads progress and review dates for the signed-in user's notebook. */
export function fetchLearningProgress(): Promise<LearningSnapshot> {
  return request<LearningSnapshot>('/api/me/learning-progress');
}

/** Saves one video's status; the backend sets completion and review dates. */
export function setLearningStatus(youtubeId: string, status: LearningStatus): Promise<void> {
  return request<void>(`/api/me/learning-progress/${encodeURIComponent(youtubeId)}`, {
    method: 'PUT',
    body: JSON.stringify({ status }),
  });
}

export function markLearningReviewed(youtubeId: string): Promise<void> {
  return request<void>(`/api/me/learning-progress/${encodeURIComponent(youtubeId)}/review`, { method: 'POST' });
}

/** Downloads an authenticated file; unlike request(), success is CSV rather than JSON. */
export async function downloadLearningProgress(): Promise<void> {
  let response: Response;
  try {
    response = await fetch('/api/me/learning-progress/export', { headers: { Accept: 'text/csv' }, credentials: 'same-origin' });
  } catch {
    throw new ApiError(0, OFFLINE_MESSAGE);
  }
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    const error = body && typeof body.error === 'string' ? body.error.trim() : '';
    throw new ApiError(response.status, error && error.length <= 300 ? error : `Download failed (${response.status})`);
  }
  const url = URL.createObjectURL(await response.blob());
  try {
    const link = document.createElement('a');
    link.href = url;
    link.download = 'brainfeed-learning-progress.csv';
    document.body.appendChild(link);
    try { link.click(); } finally { link.remove(); }
  } finally {
    setTimeout(() => URL.revokeObjectURL(url), 0);
  }
}
