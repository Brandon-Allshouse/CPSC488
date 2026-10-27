import type { ReactNode } from 'react';
import { Bookmark, Eye, Heart, History, Home, LogIn, Pencil, ShieldCheck, Trophy } from 'lucide-react';
import type { User } from '../api/auth';
import brainLogo from '../assets/brainfeed-logo.png';
import { Button } from '@/components/ui/button';
import { monthYear } from '@/lib/activity';
import { cn } from '@/lib/utils';

interface Props {
  user: User | null;
  /** Names of the topics the feed is built from, in the order they were picked. */
  topicNames: string[];
  onGoHome: () => void;
  onEditInterests: () => void;
  onShowLikedVideos: () => void;
  onShowHistory: () => void;
  onShowLeaderboard: () => void;
  onShowAdmin: () => void;
  /** For guests this goes to the login page. */
  onLogin: () => void;
  /** Signed-in users' numbers for the "Your progress" box. Left out, the box isn't shown. */
  activity?: {
    /** A string so a full history can say "25+" (see formatCapped). */
    watchedToday: string;
    saved: number;
    liked: number;
  };
}

function NavItem({ icon, active, onClick, children }: {
  icon: ReactNode;
  active?: boolean;
  onClick: () => void;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-current={active ? 'page' : undefined}
      className={cn(
        'flex w-full cursor-pointer items-center gap-3 rounded-lg px-3 py-2.5 text-left text-[15px] font-medium transition-colors duration-150 hover:bg-accent [&_svg]:size-5 [&_svg]:text-muted-foreground',
        active && 'bg-primary/10 text-primary hover:bg-primary/15 [&_svg]:text-primary',
      )}
    >
      {icon}
      {children}
    </button>
  );
}

// The left column on wide screens: the same places as the Profile menu, always in view, plus the
// topics this feed is built from. Hidden on phones and tablets, where the menu is enough.
export default function FeedSidebar({
  user,
  topicNames,
  onGoHome,
  onEditInterests,
  onShowLikedVideos,
  onShowHistory,
  onShowLeaderboard,
  onShowAdmin,
  onLogin,
  activity,
}: Props) {
  return (
    <aside className="feed-sidebar hidden w-64 shrink-0 flex-col gap-6 overflow-y-auto border-r bg-card/40 px-3 py-5 lg:flex" aria-label="Sidebar">
      <nav className="flex flex-col gap-1" aria-label="Main">
        <NavItem icon={<Home />} active onClick={onGoHome}>
          Feed
        </NavItem>
        <NavItem icon={<Heart />} onClick={onShowLikedVideos}>
          Your videos
        </NavItem>
        <NavItem icon={<History />} onClick={onShowHistory}>
          Watch history
        </NavItem>
        <NavItem icon={<Trophy />} onClick={onShowLeaderboard}>
          Leaderboard
        </NavItem>
        {user?.admin && (
          <NavItem icon={<ShieldCheck />} onClick={onShowAdmin}>
            Admin
          </NavItem>
        )}
      </nav>

      <section className="feed-topics flex flex-col gap-3 border-t px-3 pt-5" aria-labelledby="feed-topics-title">
        <div className="flex items-center justify-between">
          <h2 id="feed-topics-title" className="text-xs font-semibold tracking-wider text-muted-foreground uppercase">
            Your topics
          </h2>
          <Button variant="ghost" size="sm" className="-mr-2 h-7 px-2 text-primary" onClick={onEditInterests}>
            <Pencil aria-hidden="true" />
            Edit topics
          </Button>
        </div>
        {topicNames.length > 0 ? (
          <ul className="flex flex-wrap gap-1.5">
            {topicNames.map((name) => (
              <li key={name} className="rounded-full border bg-background px-2.5 py-1 text-xs font-medium">
                {name}
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-sm text-muted-foreground">Loading topics…</p>
        )}
      </section>

      {user && activity && (
        <section className="feed-activity mx-1 flex flex-col gap-3 rounded-xl border bg-background p-4" aria-labelledby="feed-activity-title">
          <div>
            <h2 id="feed-activity-title" className="text-sm font-semibold">
              Your progress
            </h2>
            {monthYear(user.createdAt) && (
              <p className="text-xs text-muted-foreground">Learning since {monthYear(user.createdAt)}</p>
            )}
          </div>
          <dl className="grid grid-cols-3 gap-2 text-center">
            <ActivityNumber icon={<Eye />} label="Today" value={activity.watchedToday} />
            <ActivityNumber icon={<Bookmark />} label="Saved" value={String(activity.saved)} />
            <ActivityNumber icon={<Heart />} label="Liked" value={String(activity.liked)} />
          </dl>
        </section>
      )}

      {!user && (
        <section className="mx-1 flex flex-col gap-2 rounded-xl border bg-primary/5 p-4" aria-label="Sign in">
          <p className="text-sm font-semibold">Keep what you learn</p>
          <p className="text-sm text-muted-foreground">
            Log in to save videos, write study notes and pick up where you left off.
          </p>
          <Button size="sm" className="mt-1" onClick={onLogin}>
            <LogIn aria-hidden="true" />
            Log in or sign up
          </Button>
        </section>
      )}

      <div className="mt-auto flex items-center gap-3 px-3 pt-4">
        <img className="feed-logo size-10 rounded-lg object-cover" src={brainLogo} alt="BrainFeed brain logo" />
        <p className="text-xs leading-snug text-muted-foreground">Learn something new with every scroll.</p>
      </div>
    </aside>
  );
}

// One of the three numbers in "Your progress". A <dl> so screen readers read "Today: 3".
function ActivityNumber({ icon, label, value }: { icon: ReactNode; label: string; value: string }) {
  return (
    <div className="flex flex-col items-center gap-0.5 rounded-lg bg-muted/50 px-1 py-2">
      <span aria-hidden="true" className="text-primary [&_svg]:size-4">
        {icon}
      </span>
      {/* dt has to come first in the markup; order-last puts the label under the number. */}
      <dt className="order-last text-[11px] text-muted-foreground">{label}</dt>
      <dd className="text-lg leading-tight font-semibold">{value}</dd>
    </div>
  );
}
