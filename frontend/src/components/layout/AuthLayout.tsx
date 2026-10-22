import type { ReactNode } from 'react';
import brainLogo from '../../assets/brainfeed-logo.png';
import { Card, CardContent, CardDescription, CardHeader } from '@/components/ui/card';
import { cn } from '@/lib/utils';
import ThemeToggle from '../ThemeToggle';

interface Props {
  /** Used for ids, e.g. "login" gives id="login-page" and the title id="login-title". */
  name: string;
  title: string;
  description: ReactNode;
  className?: string;
  children: ReactNode;
}

/**
 * The template for full-screen steps before the feed (log in, pick interests): the logo in the
 * corner and one centered card with the BrainFeed name, a title and a short description.
 */
export default function AuthLayout({ name, title, description, className, children }: Props) {
  return (
    <main
      id={`${name}-page`}
      className="auth-backdrop relative flex min-h-dvh items-center justify-center px-4 py-16"
    >
      <img
        className="auth-logo fixed top-3 left-3 z-10 size-14 rounded-xl object-cover shadow-lg sm:size-[72px]"
        src={brainLogo}
        alt="BrainFeed brain logo"
      />
      <ThemeToggle className="fixed top-4 right-4 z-10" />
      <Card
        aria-labelledby={`${name}-title`}
        className={cn('w-full max-w-md animate-fade-in-up border-border/70 shadow-2xl', className)}
        role="region"
      >
        <CardHeader className="gap-2">
          <p className="brand text-xs font-semibold tracking-[0.2em] text-primary uppercase">BrainFeed</p>
          <h1 id={`${name}-title`} className="text-2xl">
            {title}
          </h1>
          <CardDescription>{description}</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">{children}</CardContent>
      </Card>
    </main>
  );
}
