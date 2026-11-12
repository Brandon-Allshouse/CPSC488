import { useEffect, useRef, useState } from 'react';
import { BookOpen, Check, CircleAlert, Clock, Download, Loader2, Search } from 'lucide-react';
import { ApiError } from '../api/client';
import type { SavedVideo } from '../api/feed';
import { downloadLearningProgress, fetchLearningProgress, markLearningReviewed, setLearningStatus, type LearningSnapshot, type LearningStatus } from '../api/learningProgress';
import StatTile from './StatTile';
import { Alert } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader } from '@/components/ui/card';
import { Input } from '@/components/ui/input';

const labels: Record<LearningStatus, string> = {
  planned: 'Planned',
  in_progress: 'In progress',
  completed: 'Completed',
};
const selectClassName = 'h-10 rounded-md border border-input bg-background px-3 text-sm shadow-xs outline-none transition-colors focus-visible:border-ring focus-visible:ring-2 focus-visible:ring-ring/40 disabled:opacity-50';
type Filter = LearningStatus | 'all' | 'due';
interface Props {
  onOpenVideo: (video: SavedVideo) => void;
  onSessionExpired: () => void;
}

/** The parent keys this component by account id; private data is discarded on account changes. */
export default function LearningProgressPanel({ onOpenVideo, onSessionExpired }: Props) {
  const [snapshot, setSnapshot] = useState<LearningSnapshot | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [retry, setRetry] = useState(0);
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<Filter>('all');
  const [busy, setBusy] = useState<string | null>(null);
  const [exporting, setExporting] = useState(false);
  const pending = useRef(false);
  const exportPending = useRef(false);
  const mounted = useRef(false);

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    fetchLearningProgress()
      .then((data) => { if (!cancelled) setSnapshot(data); })
      .catch((failure: unknown) => {
        if (cancelled) return;
        if (failure instanceof ApiError && failure.status === 401) onSessionExpired();
        else setError(failure instanceof Error ? failure.message : 'Could not load learning progress.');
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [retry]);

  async function update(youtubeId: string, action: () => Promise<void>, success: string) {
    if (pending.current || exportPending.current) return;
    pending.current = true;
    setBusy(youtubeId);
    setError(null);
    setMessage(null);
    let persisted = false;
    try {
      await action();
      persisted = true;
      if (!mounted.current) return;
      // Always use server totals and scheduling after a successful write.
      const data = await fetchLearningProgress();
      if (mounted.current) {
        setSnapshot(data);
        setMessage(success);
      }
    } catch (failure) {
      if (!mounted.current) return;
      if (failure instanceof ApiError && failure.status === 401) onSessionExpired();
      else setError(persisted ? 'Your change was saved, but progress could not be refreshed. Use Retry progress.'
        : failure instanceof Error ? failure.message : 'Could not update progress. Try again.');
    } finally {
      pending.current = false;
      if (mounted.current) setBusy(null);
    }
  }

  async function exportProgress() {
    if (exportPending.current || pending.current) return;
    exportPending.current = true;
    setExporting(true);
    setMessage(null);
    setError(null);
    try {
      await downloadLearningProgress();
      if (mounted.current) setMessage('Learning plan download started.');
    } catch (failure) {
      if (!mounted.current) return;
      if (failure instanceof ApiError && failure.status === 401) onSessionExpired();
      else setError(failure instanceof Error ? failure.message : 'Could not download learning progress.');
    } finally {
      exportPending.current = false;
      if (mounted.current) setExporting(false);
    }
  }

  const normalized = query.trim().toLocaleLowerCase();
  const items = snapshot?.items.filter((item) => (
    (filter === 'all' || (filter === 'due' ? item.dueForReview : item.progress.status === filter))
    && `${item.progress.title} ${item.progress.creator}`.toLocaleLowerCase().includes(normalized)
  )) ?? [];
  const summary = snapshot?.summary;

  return (
    <section aria-label="Learning progress" className="learning-progress space-y-4">
      <p className="text-sm text-muted-foreground">
        Turn your Study notebook into a learning plan. Mark progress yourself as you study.
        Playing a video does not mark it completed. Completed videos return for review after {snapshot?.reviewIntervalDays ?? 7} days.
      </p>
      {error && (
        <Alert role="alert" variant="destructive">
          <CircleAlert aria-hidden="true" />
          <span>{error}</span>
          <Button variant="outline" size="sm" disabled={loading || busy !== null || exporting} onClick={() => setRetry((value) => value + 1)}>Retry progress</Button>
        </Alert>
      )}
      {message && <p role="status" className="text-sm text-primary">{message}</p>}
      {loading && <p role="status" className="flex items-center gap-2 text-sm"><Loader2 aria-hidden="true" className="size-4 animate-spin" />Loading learning progress...</p>}
      {!loading && snapshot && summary && (
        <>
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4" aria-label="Learning totals">
            <StatTile icon={<BookOpen />} label="Planned" value={summary.planned} />
            <StatTile icon={<Clock />} label="In progress" value={summary.inProgress} />
            <StatTile icon={<Check />} label="Completed" value={summary.completed} />
            <StatTile icon={<Clock />} label="Due for review" value={summary.dueForReview} />
          </div>
          <div className="space-y-1">
            <label htmlFor="learning-completion" className="text-sm font-medium">{summary.completionPercent}% completed ({summary.completed} of {summary.total})</label>
            <progress id="learning-completion" value={summary.completionPercent} max={100} className="block h-3 w-full accent-primary" />
          </div>
          <Button variant="outline" size="sm" disabled={exporting || busy !== null || error !== null} onClick={() => void exportProgress()}>
            <Download aria-hidden="true" />{exporting ? 'Preparing download...' : 'Download learning plan'}
          </Button>
          <p className="text-xs text-muted-foreground">The CSV includes your entire notebook, including videos hidden by search or filters.</p>
          {summary.total === 0 ? (
            <p className="rounded-xl border p-6 text-sm text-muted-foreground">Your learning plan is empty. Use a video's bookmark menu to add it to your Study notebook.</p>
          ) : (
            <>
              <div role="search" aria-label="Learning progress search controls" className="flex flex-col gap-3 sm:flex-row">
                <div className="relative min-w-0 flex-1">
                  <Search aria-hidden="true" className="pointer-events-none absolute top-3 left-3 size-4 text-muted-foreground" />
                  <Input type="search" aria-label="Search learning progress" placeholder="Search titles or creators" value={query} onChange={(event) => setQuery(event.target.value)} className="pl-9" />
                </div>
                <label className="flex items-center gap-2 text-sm">Show
                  <select aria-label="Filter learning progress" value={filter} onChange={(event) => setFilter(event.target.value as Filter)} className={selectClassName}>
                    <option value="all">All videos</option>
                    <option value="planned">Planned</option>
                    <option value="in_progress">In progress</option>
                    <option value="completed">Completed</option>
                    <option value="due">Due for review</option>
                  </select>
                </label>
              </div>
              <p aria-live="polite" className="text-xs text-muted-foreground">Showing {items.length} of {summary.total} notebook videos</p>
              {items.length === 0 && <p className="py-4 text-sm text-muted-foreground">No videos match this search and filter.</p>}
              <div className="grid gap-3 lg:grid-cols-2">
                {items.map(({ progress, reviewDueAt, dueForReview }) => (
                  <Card key={progress.youtubeId} className="min-w-0">
                    <CardHeader className="gap-2">
                      <button type="button" className="cursor-pointer text-left font-semibold break-words hover:text-primary" onClick={() => onOpenVideo(progress)}>{progress.title}</button>
                      <p className="text-xs break-words text-muted-foreground">{progress.creator}</p>
                      <Badge variant={dueForReview ? 'default' : 'secondary'}>{dueForReview ? 'Ready for review' : labels[progress.status]}</Badge>
                    </CardHeader>
                    <CardContent className="space-y-3">
                      <label className="flex flex-wrap items-center gap-2 text-sm">Progress
                        <select
                          aria-label={`Progress for ${progress.title}`}
                          value={progress.status}
                          disabled={busy !== null || exporting || error !== null}
                          className={selectClassName}
                          onChange={(event) => {
                            const status = event.target.value as LearningStatus;
                            void update(progress.youtubeId, () => setLearningStatus(progress.youtubeId, status), 'Progress saved.');
                          }}
                        >
                          <option value="planned">Planned</option>
                          <option value="in_progress">In progress</option>
                          <option value="completed">Completed</option>
                        </select>
                      </label>
                      {reviewDueAt && <p className="text-xs text-muted-foreground">{dueForReview ? 'Review due' : 'Next review'}: <time dateTime={reviewDueAt}>{new Date(reviewDueAt).toLocaleDateString()}</time></p>}
                      {progress.status === 'completed' && (
                        <Button variant="outline" size="sm" disabled={busy !== null || exporting || error !== null} onClick={() => void update(progress.youtubeId, () => markLearningReviewed(progress.youtubeId), 'Review saved. Your next review is in 7 days.')}>
                          <Check aria-hidden="true" />Mark reviewed
                        </Button>
                      )}
                      {busy === progress.youtubeId && <p role="status" className="text-xs">Saving learning progress...</p>}
                    </CardContent>
                  </Card>
                ))}
              </div>
            </>
          )}
        </>
      )}
    </section>
  );
}
