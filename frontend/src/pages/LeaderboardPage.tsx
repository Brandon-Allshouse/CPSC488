import { useEffect, useRef, useState } from 'react';
import { CircleAlert, Flame, Loader2, Medal, RefreshCw, Star, Trophy } from 'lucide-react';
import { ApiError } from '../api/client';
import { fetchLeaderboard, type Leaderboard, type LeaderboardBoard } from '../api/leaderboard';
import PageLayout, { type PageNavigation } from '@/components/layout/PageLayout';
import StatTile from '@/components/StatTile';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { cn } from '@/lib/utils';

type Period = 'week' | 'allTime';

const PERIODS: { id: Period; label: string }[] = [
  { id: 'week', label: 'This week' },
  { id: 'allTime', label: 'All time' },
];

type State =
  | { kind: 'loading' }
  | { kind: 'error'; message: string }
  | { kind: 'ready'; board: Leaderboard };

/**
 * The leaderboard: the top 20 by quiz points for this week (since Monday, UTC) or all time, and
 * your own rank even if you're further down. Scores are kept by the backend; see "How scoring
 * works" in the README. Usernames come from other people, so they're rendered as plain text.
 */
export default function LeaderboardPage(navigation: PageNavigation) {
  const [state, setState] = useState<State>({ kind: 'loading' });
  const [refreshing, setRefreshing] = useState(false);
  const [period, setPeriod] = useState<Period>('week');
  const run = useRef(0);

  async function load() {
    const id = ++run.current;
    setRefreshing(true);
    try {
      const board = await fetchLeaderboard();
      if (id === run.current) setState({ kind: 'ready', board });
    } catch (err) {
      if (id !== run.current) return;
      const message =
        err instanceof ApiError && err.status !== 0 ? err.message : "Couldn't load the leaderboard. Check your connection and try again.";
      setState({ kind: 'error', message });
    } finally {
      if (id === run.current) setRefreshing(false);
    }
  }

  useEffect(() => {
    void load();
    // Ignore answers that arrive after the page is closed.
    return () => void (run.current += 1);
  }, []);

  return (
    <PageLayout
      {...navigation}
      name="leaderboard"
      title="Leaderboard"
      description="Earn 10 points for each quiz question you get right the first time."
      actions={
        <Button size="sm" onClick={() => void load()} disabled={refreshing}>
          <RefreshCw aria-hidden="true" className={cn(refreshing && 'animate-spin')} />
          Update leaderboard
        </Button>
      }
    >
      <div role="group" aria-label="Time period" className="inline-flex rounded-lg border bg-card p-1">
        {PERIODS.map((p) => (
          <button
            key={p.id}
            type="button"
            aria-pressed={period === p.id}
            className={cn(
              'leaderboard-period cursor-pointer rounded-md px-3 py-1.5 text-sm font-medium transition-colors duration-150',
              period === p.id ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-accent',
            )}
            onClick={() => setPeriod(p.id)}
          >
            {p.label}
          </button>
        ))}
      </div>

      {state.kind === 'loading' && (
        <p className="flex items-center gap-2 text-sm text-muted-foreground" role="status">
          <Loader2 aria-hidden="true" className="size-4 animate-spin" />
          Loading the leaderboard…
        </p>
      )}

      {state.kind === 'error' && (
        <Alert variant="destructive" role="alert">
          <CircleAlert aria-hidden="true" />
          {state.message}
        </Alert>
      )}

      {state.kind === 'ready' && (
        <Board board={state.board[period]} period={period} signedIn={navigation.user !== null} />
      )}
    </PageLayout>
  );
}

function Board({ board, period, signedIn }: { board: LeaderboardBoard; period: Period; signedIn: boolean }) {
  const you = board.you;
  const when = period === 'week' ? 'this week' : 'yet';
  return (
    <>
      {signedIn && you ? (
        <section aria-label="Your rank" className="grid gap-3 sm:grid-cols-4">
          <StatTile icon={<Medal />} label="Your rank" value={you.rank === null ? 'Not ranked' : `#${you.rank}`} />
          <StatTile icon={<Star />} label="Points" value={you.points} />
          <StatTile icon={<Trophy />} label="Right answers" value={you.correct} />
          <StatTile icon={<Flame />} label="Day streak" value={you.streak} hint="days in a row with a right answer" />
        </section>
      ) : (
        <Card role="region" aria-label="Your rank" className="leaderboard-guest px-5 py-4 text-sm">
          <p>Log in to earn points and get your name on the board.</p>
        </Card>
      )}

      {board.top.length === 0 ? (
        <Card role="region" aria-label="Rankings" className="leaderboard-panel items-center gap-3 px-6 py-10 text-center">
          <span className="flex size-14 items-center justify-center rounded-full bg-primary/15 text-primary">
            <Trophy aria-hidden="true" className="size-7" />
          </span>
          <p className="text-lg font-semibold">Nobody has points {when}</p>
          <p className="max-w-md text-sm text-muted-foreground">
            Play a video, open its summary and answer the quiz. Each right first answer is worth 10 points.
          </p>
        </Card>
      ) : (
        <Card role="region" aria-label="Rankings" className="leaderboard-panel overflow-x-auto p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-16">Rank</TableHead>
                <TableHead>Username</TableHead>
                <TableHead className="text-right">Points</TableHead>
                <TableHead className="text-right">Right answers</TableHead>
                <TableHead className="text-right">Streak</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {board.top.map((entry) => {
                const isYou = you !== null && entry.username === you.username;
                return (
                  <TableRow
                    key={entry.username}
                    className={cn('leaderboard-row', isYou && 'bg-primary/10 font-medium')}
                    aria-current={isYou ? 'true' : undefined}
                  >
                    <TableCell>#{entry.rank}</TableCell>
                    <TableCell className="max-w-48 truncate">
                      {entry.username}
                      {isYou && <span className="text-muted-foreground"> (you)</span>}
                    </TableCell>
                    <TableCell className="text-right">{entry.points}</TableCell>
                    <TableCell className="text-right">{entry.correct}</TableCell>
                    <TableCell className="text-right">{entry.streak > 0 ? `${entry.streak} ${entry.streak === 1 ? 'day' : 'days'}` : '-'}</TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </Card>
      )}
      <p className="text-xs text-muted-foreground">
        People with the same points share a rank. This week starts Monday at midnight UTC.
      </p>
    </>
  );
}
