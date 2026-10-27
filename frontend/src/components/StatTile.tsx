import type { ReactNode } from 'react';
import { cn } from '@/lib/utils';

interface Props {
  icon: ReactNode;
  label: string;
  /** Shown big. Pass a string for things like "25+" or a channel name. */
  value: ReactNode;
  /** A short line under the value, e.g. "from your last 25 videos". */
  hint?: string;
  className?: string;
}

// One number with a label, like the summary boxes at the top of a dashboard. Used on Watch history,
// Your videos and in the feed's sidebar. Values come from YouTube or the user in some places
// (channel names), so they're always rendered as plain text.
export default function StatTile({ icon, label, value, hint, className }: Props) {
  return (
    <div className={cn('stat-tile flex min-w-0 items-start gap-3 rounded-xl border bg-card p-4 shadow-sm', className)}>
      <span
        aria-hidden="true"
        className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-primary/12 text-primary [&_svg]:size-[18px]"
      >
        {icon}
      </span>
      <div className="min-w-0">
        <p className="text-xs font-medium text-muted-foreground">{label}</p>
        <p className="truncate text-xl font-semibold tracking-tight">{value}</p>
        {hint && <p className="truncate text-xs text-muted-foreground">{hint}</p>}
      </div>
    </div>
  );
}
