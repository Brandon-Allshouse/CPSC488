import { useEffect, useRef, type ReactNode } from 'react';
import { BookOpen, ChevronDown, Heart, History, LogIn, LogOut, ShieldCheck, Trophy } from 'lucide-react';
import type { User } from '../api/auth';
import brainLogo from '../assets/brainfeed-logo.png';
import { cn } from '@/lib/utils';
import ThemeToggle from './ThemeToggle';

interface Props {
  user: User | null;
  /** The BrainFeed logo and name: back to the feed, or to the top of it when already there. */
  onGoHome: () => void;
  onEditInterests: () => void;
  onShowLikedVideos: () => void;
  onShowLeaderboard: () => void;
  onShowHistory?: () => void;
  // Only admins see this menu item. The backend still checks on every admin request.
  onShowAdmin?: () => void;
  onLogout: () => void;
}

function MenuItem({ icon, onSelect, children, className }: {
  icon: ReactNode;
  onSelect: () => void;
  children: ReactNode;
  className?: string;
}) {
  return (
    <button
      type="button"
      onClick={onSelect}
      className={cn(
        'flex w-full cursor-pointer items-center gap-2.5 rounded-md px-2.5 py-2 text-left text-sm transition-colors duration-150 hover:bg-accent focus-visible:bg-accent [&_svg]:size-4 [&_svg]:text-muted-foreground',
        className,
      )}
    >
      {icon}
      {children}
    </button>
  );
}

// The bar across the top of every signed-in screen: the logo, and a Profile menu with the
// navigation. The menu is a native <details> element, so it works without any JavaScript; the
// effect below only adds closing it with Escape or by clicking somewhere else.
export default function FeedHeader({
  user,
  onGoHome,
  onEditInterests,
  onShowLikedVideos,
  onShowLeaderboard,
  onShowHistory,
  onShowAdmin,
  onLogout,
}: Props) {
  const menuRef = useRef<HTMLDetailsElement>(null);

  function closeMenu() {
    menuRef.current?.removeAttribute('open');
  }

  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape' && menuRef.current?.open) {
        closeMenu();
        menuRef.current.querySelector('summary')?.focus();
      }
    }
    function handlePointerDown(event: PointerEvent) {
      if (menuRef.current?.open && !menuRef.current.contains(event.target as Node)) {
        closeMenu();
      }
    }
    document.addEventListener('keydown', handleKeyDown);
    document.addEventListener('pointerdown', handlePointerDown);
    return () => {
      document.removeEventListener('keydown', handleKeyDown);
      document.removeEventListener('pointerdown', handlePointerDown);
    };
  }, []);

  // Closes the menu, then does what the item is for.
  function select(action: () => void) {
    return () => {
      closeMenu();
      action();
    };
  }

  return (
    <header
      id="app-header"
      className="feed-header sticky top-0 z-20 flex items-center justify-between gap-4 border-b bg-background/80 px-4 py-3 backdrop-blur-md"
    >
      <button
        type="button"
        className="feed-brand -ml-1.5 flex cursor-pointer items-center gap-2.5 rounded-lg px-1.5 py-1 transition-opacity duration-150 hover:opacity-80"
        aria-label="BrainFeed, go to your feed"
        title="Go to your feed"
        onClick={onGoHome}
      >
        <img className="feed-brand-logo size-9 rounded-lg object-cover" src={brainLogo} alt="" />
        <span className="brand text-lg font-bold tracking-tight">BrainFeed</span>
      </button>

      <div className="flex items-center gap-2">
        <ThemeToggle />
        <details ref={menuRef} className="profile-menu group relative">
          <summary className="flex cursor-pointer items-center gap-2 rounded-full border bg-card py-1 pr-3 pl-1 text-sm font-medium transition-colors duration-150 select-none hover:bg-accent">
            <span
              aria-hidden="true"
              className="flex size-7 items-center justify-center rounded-full bg-primary/20 text-xs font-semibold text-primary uppercase"
            >
              {user ? user.username.charAt(0) : '?'}
            </span>
            Profile
            <ChevronDown
              aria-hidden="true"
              className="profile-chevron size-4 text-muted-foreground transition-transform duration-200 group-open:rotate-180"
            />
          </summary>

          <div className="profile-menu-content absolute right-0 z-30 mt-2 w-56 animate-fade-in-up rounded-lg border bg-popover p-1.5 text-popover-foreground shadow-xl">
            <div className="px-2.5 pt-1.5 pb-2">
              <p className="text-xs text-muted-foreground">{user ? 'Signed in as' : 'Browsing as'}</p>
              <span className="text-sm font-semibold">{user ? user.username : 'Guest'}</span>
            </div>
            <div className="my-1 h-px bg-border" />
            <MenuItem icon={<BookOpen />} onSelect={select(onEditInterests)}>
              Interests
            </MenuItem>
            <MenuItem icon={<Heart />} onSelect={select(onShowLikedVideos)}>
              Your videos
            </MenuItem>
            {onShowHistory && (
              <MenuItem icon={<History />} onSelect={select(onShowHistory)}>
                Watch history
              </MenuItem>
            )}
            <MenuItem icon={<Trophy />} onSelect={select(onShowLeaderboard)}>
              Leaderboard
            </MenuItem>
            {user?.admin && onShowAdmin && (
              <MenuItem icon={<ShieldCheck />} onSelect={select(onShowAdmin)}>
                Admin
              </MenuItem>
            )}
            <div className="my-1 h-px bg-border" />
            <MenuItem
              icon={user ? <LogOut /> : <LogIn />}
              onSelect={select(onLogout)}
              className={user ? 'text-destructive [&_svg]:text-destructive' : undefined}
            >
              {user ? 'Sign out' : 'Log in'}
            </MenuItem>
          </div>
        </details>
      </div>
    </header>
  );
}
