import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Bookmark, Loader2, Plus } from 'lucide-react';
import type { Playlist } from '../api/playlists';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/utils';

interface Props {
  youtubeId: string;
  /** Used for the menu's id, so each video card's menu is separate. */
  idPrefix: string;
  playlists: Playlist[];
  /** Adds the video to the playlist (save true) or removes it (save false). Throws on failure. */
  onToggle: (playlistId: number, save: boolean) => Promise<void>;
  /** Makes a new playlist and saves the video into it. Throws on failure (e.g. name taken). */
  onCreate: (name: string) => Promise<void>;
}

// The bookmark button on a video, and the small menu it opens: a checkbox per playlist ("Watch
// later" first) plus a box to make a new playlist, like YouTube's "Save to…".
export default function SaveMenu({ youtubeId, idPrefix, playlists, onToggle, onCreate }: Props) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [newName, setNewName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const rootRef = useRef<HTMLDivElement>(null);

  const savedIn = playlists.filter((p) => p.videos.some((v) => v.youtubeId === youtubeId));
  const isSaved = savedIn.length > 0;
  const menuId = `${idPrefix}-save-menu`;

  // Close on a click outside the menu, or on Escape.
  useEffect(() => {
    if (!open) return;
    function handlePointer(event: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) setOpen(false);
    }
    function handleKey(event: KeyboardEvent) {
      if (event.key === 'Escape') setOpen(false);
    }
    document.addEventListener('mousedown', handlePointer);
    document.addEventListener('keydown', handleKey);
    return () => {
      document.removeEventListener('mousedown', handlePointer);
      document.removeEventListener('keydown', handleKey);
    };
  }, [open]);

  async function run(action: () => Promise<void>) {
    setBusy(true);
    setError(null);
    try {
      await action();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong.');
    } finally {
      setBusy(false);
    }
  }

  function handleCreate(e: FormEvent) {
    e.preventDefault();
    const name = newName.trim();
    if (!name) return;
    void run(async () => {
      await onCreate(name);
      setNewName('');
    });
  }

  const label = isSaved ? 'Saved. Change playlists' : 'Save video';

  return (
    <div ref={rootRef} className="relative">
      <button
        type="button"
        className={cn(
          'group/save relative flex size-10 cursor-pointer items-center justify-center rounded-full border bg-card transition-colors duration-150 hover:bg-accent',
          isSaved && 'border-primary/60 bg-primary/15 text-primary hover:bg-primary/20',
        )}
        aria-label={label}
        aria-expanded={open}
        aria-controls={menuId}
        aria-pressed={isSaved}
        onClick={() => setOpen((current) => !current)}
      >
        <Bookmark aria-hidden="true" className={cn('size-[18px]', isSaved && 'fill-current')} />
      </button>

      {open && (
        <div
          id={menuId}
          role="dialog"
          aria-label="Save to playlist"
          className="absolute right-0 bottom-full z-20 mb-2 flex w-64 animate-fade-in-up flex-col gap-3 rounded-lg border bg-popover p-3 text-popover-foreground shadow-xl"
        >
          <p className="text-sm font-medium">Save to…</p>
          <ul className="flex max-h-48 flex-col gap-1 overflow-y-auto">
            {playlists.map((playlist) => {
              const checked = savedIn.includes(playlist);
              return (
                <li key={playlist.id}>
                  {/* Playlist names are typed by the user: plain JSX text only. */}
                  <label className="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-sm hover:bg-accent">
                    <input
                      type="checkbox"
                      className="size-4 cursor-pointer accent-primary"
                      checked={checked}
                      disabled={busy}
                      onChange={() => void run(() => onToggle(playlist.id, !checked))}
                    />
                    <span className="truncate">{playlist.name}</span>
                  </label>
                </li>
              );
            })}
          </ul>

          <form className="flex gap-2 border-t pt-3" onSubmit={handleCreate}>
            <Input
              className="h-8"
              placeholder="New playlist"
              aria-label="New playlist name"
              maxLength={50}
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
            />
            <Button type="submit" size="sm" variant="outline" disabled={busy || !newName.trim()} aria-label="Create playlist">
              {busy ? <Loader2 aria-hidden="true" className="animate-spin" /> : <Plus aria-hidden="true" />}
            </Button>
          </form>

          {error && (
            <p className="text-xs text-destructive" role="alert">
              {error}
            </p>
          )}
        </div>
      )}
    </div>
  );
}