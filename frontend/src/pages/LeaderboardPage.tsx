import { RefreshCw, Trophy } from 'lucide-react';
import PageLayout, { type PageNavigation } from '@/components/layout/PageLayout';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';

// Placeholder until scores exist (see the Roadmap in the README). The button is disabled so it
// doesn't look like it did something.
export default function LeaderboardPage(navigation: PageNavigation) {
  return (
    <PageLayout
      {...navigation}
      name="leaderboard"
      title="Leaderboard"
      description="See who's learning the most."
      actions={
        <Button size="sm" disabled title="Coming soon">
          <RefreshCw aria-hidden="true" />
          Update leaderboard
        </Button>
      }
    >
      <Card
        role="region"
        aria-label="Leaderboard information"
        className="leaderboard-panel min-h-[50dvh] items-center justify-center gap-3 text-center"
      >
        <span className="flex size-14 items-center justify-center rounded-full bg-primary/15 text-primary">
          <Trophy aria-hidden="true" className="size-7" />
        </span>
        <p className="font-medium">No rankings yet</p>
        <p className="max-w-sm text-sm text-muted-foreground">
          Rankings will show up here once quizzes and progress tracking are added.
        </p>
      </Card>
    </PageLayout>
  );
}
