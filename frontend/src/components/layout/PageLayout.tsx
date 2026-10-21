import type { ReactNode } from 'react';
import { ArrowLeft } from 'lucide-react';
import type { User } from '../../api/auth';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import FeedHeader from '../FeedHeader';

/**
 * What every signed-in page gets from FeedPage: who's logged in, and how to move between pages.
 * A new page takes these props and passes them straight to PageLayout.
 */
export interface PageNavigation {
  user: User | null;
  onBack: () => void;
  onEditInterests: () => void;
  onShowLikedVideos: () => void;
  onShowLeaderboard: () => void;
  onShowHistory?: () => void;
  onShowAdmin?: () => void;
  onLogout: () => void;
}

interface Props extends PageNavigation {
  /** Used for the page's id, e.g. "admin" gives id="admin-page". */
  name: string;
  title: string;
  description?: ReactNode;
  /** Buttons shown next to the title, after "Back to feed". */
  actions?: ReactNode;
  className?: string;
  children: ReactNode;
}

/**
 * The template for every page other than the feed and the login/interests screens: the header with
 * the Profile menu, then a title row with "Back to feed", then the page's content. To add a page,
 * see "Adding a page" in the README.
 */
export default function PageLayout({
  name,
  title,
  description,
  actions,
  className,
  children,
  onBack,
  ...navigation
}: Props) {
  return (
    <>
      <FeedHeader {...navigation} onGoHome={onBack} />
      <main id={`${name}-page`} className={cn('mx-auto w-full max-w-5xl animate-fade-in-up px-4 py-6 sm:py-8', className)}>
        <div className="mb-6 flex flex-wrap items-start justify-between gap-4">
          <div className="space-y-1">
            <h1 className="text-2xl sm:text-3xl">{title}</h1>
            {description && <p className="text-sm text-muted-foreground">{description}</p>}
          </div>
          <div className="flex items-center gap-2">
            <Button variant="outline" size="sm" onClick={onBack}>
              <ArrowLeft aria-hidden="true" />
              Back to feed
            </Button>
            {actions}
          </div>
        </div>
        <div className="space-y-6">{children}</div>
      </main>
    </>
  );
}
