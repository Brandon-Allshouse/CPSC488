import type { ReactNode } from 'react';
import { CircleHelp, Play, RefreshCw, Trophy } from 'lucide-react';
import PageLayout, { type PageNavigation } from '@/components/layout/PageLayout';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';

// How scoring is planned to work (quizzes are next on the Roadmap in the README). Only describes the
// plan; there are no made-up rankings here, so nobody mistakes a mock-up for real people.
const STEPS: { icon: ReactNode; title: string; text: string }[] = [
  { icon: <Play />, title: 'Watch', text: 'Short videos from the topics you picked, like you do now.' },
  { icon: <CircleHelp />, title: 'Quiz yourself', text: 'Answer a quick question or two after a video to lock it in.' },
  { icon: <Trophy />, title: 'Climb the ranks', text: 'Right answers earn points toward your spot on the board.' },
];

// Placeholder until scores exist. The button is disabled so it doesn't look like it did something.
export default function LeaderboardPage(navigation: PageNavigation) {
  return (
    <PageLayout
      {...navigation}
      name="leaderboard"
      title="Leaderboard"
      description="See who's learning the most."
      actions={
        <Button size="sm" disabled>
          <RefreshCw aria-hidden="true" />
          Update leaderboard
        </Button>
      }
    >
      <Card
        role="region"
        aria-label="Leaderboard information"
        className="leaderboard-panel items-center gap-3 px-6 py-10 text-center"
      >
        <span className="flex size-14 items-center justify-center rounded-full bg-primary/15 text-primary">
          <Trophy aria-hidden="true" className="size-7" />
        </span>
        <p className="text-lg font-semibold">No rankings yet</p>
        <p className="max-w-md text-sm text-muted-foreground">
          Rankings will show up here once quizzes and progress tracking are added. Here's how it will work.
        </p>
      </Card>

      <ol aria-label="How the leaderboard will work" className="grid gap-4 sm:grid-cols-3">
        {STEPS.map((step, i) => (
          <li key={step.title} className="leaderboard-step flex flex-col gap-2 rounded-xl border bg-card p-5 shadow-sm">
            <div className="flex items-center gap-3">
              <span
                aria-hidden="true"
                className="flex size-9 items-center justify-center rounded-lg bg-primary/12 text-primary [&_svg]:size-[18px]"
              >
                {step.icon}
              </span>
              <span className="text-xs font-semibold tracking-wider text-muted-foreground uppercase">Step {i + 1}</span>
            </div>
            <h2 className="text-base">{step.title}</h2>
            <p className="text-sm text-muted-foreground">{step.text}</p>
          </li>
        ))}
      </ol>
    </PageLayout>
  );
}
