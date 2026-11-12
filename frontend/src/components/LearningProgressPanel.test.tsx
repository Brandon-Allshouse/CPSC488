// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { downloadLearningProgress, fetchLearningProgress, markLearningReviewed, setLearningStatus, type LearningSnapshot } from '../api/learningProgress';
import LearningProgressPanel from './LearningProgressPanel';

vi.mock('../api/learningProgress', () => ({ downloadLearningProgress: vi.fn(), fetchLearningProgress: vi.fn(), markLearningReviewed: vi.fn(), setLearningStatus: vi.fn() }));
const openVideo = vi.fn();
const expire = vi.fn();
const base: LearningSnapshot = {
  items: [
    { progress: { youtubeId: 'video000001', title: 'TCP lesson', creator: 'Networks', status: 'planned', completedAt: null, reviewedAt: null, updatedAt: '2026-11-05T12:00:00Z' }, reviewDueAt: null, dueForReview: false },
    { progress: { youtubeId: 'video000002', title: 'Java lesson', creator: 'Computing', status: 'completed', completedAt: '2026-10-01T12:00:00Z', reviewedAt: null, updatedAt: '2026-10-01T12:00:00Z' }, reviewDueAt: '2026-10-08T12:00:00Z', dueForReview: true },
  ],
  summary: { total: 2, planned: 1, inProgress: 0, completed: 1, dueForReview: 1, completionPercent: 50 },
  reviewIntervalDays: 7,
};
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(fetchLearningProgress).mockResolvedValue(base);
  vi.mocked(setLearningStatus).mockResolvedValue(undefined);
  vi.mocked(markLearningReviewed).mockResolvedValue(undefined);
  vi.mocked(downloadLearningProgress).mockResolvedValue(undefined);
});
afterEach(cleanup);
function show() { return render(<LearningProgressPanel onOpenVideo={openVideo} onSessionExpired={expire} />); }

it('shows server totals and opens a notebook video in the existing feed', async () => {
  show();
  fireEvent.click(await screen.findByRole('button', { name: 'TCP lesson' }));
  expect(openVideo).toHaveBeenCalledWith(base.items[0].progress);
  expect(screen.getByRole('progressbar').getAttribute('value')).toBe('50');
  expect(screen.getByRole('searchbox', { name: 'Search learning progress' })).toBeTruthy();
  expect(screen.getByText(/Playing a video does not mark it completed/)).toBeTruthy();
});

it('combines search and review filters while keeping totals for the whole notebook', async () => {
  show();
  await screen.findByRole('button', { name: 'TCP lesson' });
  fireEvent.change(screen.getByLabelText('Filter learning progress'), { target: { value: 'due' } });
  expect(screen.queryByRole('button', { name: 'TCP lesson' })).toBeNull();
  expect(screen.getByRole('button', { name: 'Java lesson' })).toBeTruthy();
  fireEvent.change(screen.getByLabelText('Search learning progress'), { target: { value: 'Networks' } });
  expect(screen.getByText('No videos match this search and filter.')).toBeTruthy();
  expect(screen.getByText('50% completed (1 of 2)')).toBeTruthy();
});

it('waits for persistence and refreshes totals before showing the new status', async () => {
  let resolveSave!: () => void;
  vi.mocked(setLearningStatus).mockImplementation(() => new Promise<void>((resolve) => { resolveSave = resolve; }));
  const updated = structuredClone(base);
  updated.items[0].progress.status = 'in_progress';
  updated.summary.planned = 0;
  updated.summary.inProgress = 1;
  vi.mocked(fetchLearningProgress).mockResolvedValueOnce(base).mockResolvedValue(updated);
  show();
  const select = await screen.findByLabelText('Progress for TCP lesson');
  fireEvent.change(select, { target: { value: 'in_progress' } });
  expect(setLearningStatus).toHaveBeenCalledWith('video000001', 'in_progress');
  expect((select as HTMLSelectElement).disabled).toBe(true);
  expect((select as HTMLSelectElement).value).toBe('planned');
  await act(async () => { resolveSave(); });
  await waitFor(() => expect((screen.getByLabelText('Progress for TCP lesson') as HTMLSelectElement).value).toBe('in_progress'));
});

it('marks a completed item reviewed and loads the new server schedule', async () => {
  show();
  fireEvent.click(await screen.findByRole('button', { name: /Mark reviewed/ }));
  await waitFor(() => expect(markLearningReviewed).toHaveBeenCalledWith('video000002'));
  await screen.findByText('Review saved. Your next review is in 7 days.');
  expect(fetchLearningProgress).toHaveBeenCalledTimes(2);
});

it('retains the last saved status on failure and allows retrying the load', async () => {
  vi.mocked(setLearningStatus).mockRejectedValue(new Error('Connection interrupted'));
  show();
  fireEvent.change(await screen.findByLabelText('Progress for TCP lesson'), { target: { value: 'completed' } });
  expect(await screen.findByRole('alert')).toBeTruthy();
  expect(screen.getByText('Connection interrupted')).toBeTruthy();
  expect((screen.getByLabelText('Progress for TCP lesson') as HTMLSelectElement).value).toBe('planned');
  fireEvent.click(screen.getByRole('button', { name: 'Retry progress' }));
  await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
  expect(fetchLearningProgress).toHaveBeenCalledTimes(2);
});

it('shows an honest load error and retries without presenting zero totals', async () => {
  vi.mocked(fetchLearningProgress).mockRejectedValueOnce(new Error('Unavailable')).mockResolvedValue(base);
  show();
  await screen.findByText('Unavailable');
  expect(screen.queryByRole('progressbar')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Retry progress' }));
  await screen.findByRole('progressbar');
});

it('expires the session on unauthorized access', async () => {
  vi.mocked(fetchLearningProgress).mockRejectedValue(new ApiError(401, 'Not logged in.'));
  show();
  await waitFor(() => expect(expire).toHaveBeenCalledOnce());
});

it('ignores requests that finish after the account view unmounts', async () => {
  let rejectLoad!: (error: Error) => void;
  vi.mocked(fetchLearningProgress).mockImplementation(() => new Promise((_resolve, reject) => { rejectLoad = reject; }));
  const view = show();
  view.unmount();
  await act(async () => { rejectLoad(new ApiError(401, 'Expired')); });
  expect(expire).not.toHaveBeenCalled();
});

it('explains how an empty notebook becomes a learning plan', async () => {
  vi.mocked(fetchLearningProgress).mockResolvedValue({ ...base, items: [], summary: { total: 0, planned: 0, inProgress: 0, completed: 0, dueForReview: 0, completionPercent: 0 } });
  show();
  await screen.findByText(/Your learning plan is empty/);
  expect(screen.getByRole('progressbar').getAttribute('value')).toBe('0');
});

it('explains a successful save followed by a failed refresh without claiming fresh totals', async () => {
  vi.mocked(fetchLearningProgress).mockResolvedValueOnce(base).mockRejectedValueOnce(new Error('Offline'));
  show();
  fireEvent.change(await screen.findByLabelText('Progress for TCP lesson'), { target: { value: 'completed' } });
  await screen.findByText('Your change was saved, but progress could not be refreshed. Use Retry progress.');
  expect(screen.queryByText('Progress saved.')).toBeNull();
  expect((screen.getByLabelText('Progress for TCP lesson') as HTMLSelectElement).disabled).toBe(true);
  expect(screen.getByText('50% completed (1 of 2)')).toBeTruthy();
});

it('downloads the whole plan even when a filter hides videos', async () => {
  show();
  await screen.findByRole('button', { name: 'TCP lesson' });
  fireEvent.change(screen.getByLabelText('Filter learning progress'), { target: { value: 'due' } });
  fireEvent.click(screen.getByRole('button', { name: 'Download learning plan' }));
  await screen.findByText('Learning plan download started.');
  expect(downloadLearningProgress).toHaveBeenCalledOnce();
  expect(screen.getByText(/CSV includes your entire notebook/)).toBeTruthy();
});

it('handles session expiry during a download', async () => {
  vi.mocked(downloadLearningProgress).mockRejectedValue(new ApiError(401, 'Expired'));
  show();
  fireEvent.click(await screen.findByRole('button', { name: 'Download learning plan' }));
  await waitFor(() => expect(expire).toHaveBeenCalledOnce());
});
