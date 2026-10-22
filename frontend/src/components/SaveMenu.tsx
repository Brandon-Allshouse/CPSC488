import { useEffect, useRef, useState, type FormEvent } from 'react';
import { BookOpen, Bookmark, Check, Loader2, Plus, X } from 'lucide-react';
import type { Playlist } from '../api/playlists';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/utils';

interface Props {
  youtubeId: string;
  /** Used for the menu's id, so each video card's menu is separate. */
  idPrefix: string;
  /** The private study collection uses its existing saved-video API. */
  notebook?: {
    saved: boolean;
    onToggle: () => void | Promise<void>;
  };
  /** Supply all three playlist props to show the existing playlist controls. */
  playlists?: Playlist[];
  onToggle?: (playlistId: number, save: boolean) => Promise<void>;
  onCreate?: (name: string) => Promise<void>;
}

// One bookmark opens both destinations. The notebook and playlists keep separate storage.
export default function SaveMenu({ youtubeId, idPrefix, notebook, playlists, onToggle, onCreate }: Props) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [newName, setNewName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const pendingRef = useRef(false);

  const playlistActions = playlists && onToggle && onCreate ? { playlists, onToggle, onCreate } : null;
  const savedIn = (playlistActions?.playlists ?? []).filter((p) => p.videos.some((v) => v.youtubeId === youtubeId));
  const isSaved = Boolean(notebook?.saved || savedIn.length > 0);
  const menuId = `${idPrefix}-save-menu`;
  const notebookHintId = `${idPrefix}-notebook-hint`;

  function closeMenu() {
    setOpen(false);
    triggerRef.current?.focus();
  }

  // Focus the first destination on open. Escape returns to the bookmark button.
  useEffect(() => {
    if (!open) return;
    rootRef.current?.querySelector<HTMLInputElement>('input:not(:disabled)')?.focus();
    function handlePointer(event: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) setOpen(false);
    }
    function handleKey(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.preventDefault();
        closeMenu();
      }
    }
    document.addEventListener('mousedown', handlePointer);
    document.addEventListener('keydown', handleKey);
    return () => {
      document.removeEventListener('mousedown', handlePointer);
      document.removeEventListener('keydown', handleKey);
    };
  }, [open]);

  async function run(action: () => void | Promise<void>, success: string) {
    // A ref also blocks a second event before React has rendered disabled controls.
    if (pendingRef.current) return;
    pendingRef.current = true;
    setBusy(true);
    setError(null);
    setMessage(null);
    try {
      await action();
      setMessage(success);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not save changes. Try again.');
    } finally {
      pendingRef.current = false;
      setBusy(false);
    }
  }

  function handleCreate(event: FormEvent) {
    event.preventDefault();
    const name = newName.trim();
    if (!name || !playlistActions) return;
    void run(async () => {
      await playlistActions.onCreate(name);
      setNewName('');
    }, 'Playlist created and video saved.');
  }

  const label = isSaved ? (notebook ? 'Saved. Change save locations' : 'Saved. Change playlists') : 'Save video';

  return (
    <div ref={rootRef} className="relative">
      <button
        ref={triggerRef}
        type="button"
        className={cn(
          'group/save relative flex size-10 cursor-pointer items-center justify-center rounded-full border bg-card transition-colors duration-150 hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring motion-reduce:transition-none',
          isSaved && 'border-primary/60 bg-primary/15 text-primary hover:bg-primary/20',
        )}
        aria-label={label}
        title={label}
        aria-haspopup="dialog"
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
          aria-label={notebook ? 'Save video' : 'Save to playlist'}
          aria-busy={busy}
          className="absolute right-0 bottom-full z-20 mb-2 flex max-h-[65dvh] w-72 max-w-[calc(100vw-4rem)] animate-fade-in-up flex-col gap-3 overflow-y-auto rounded-xl border bg-popover p-4 text-popover-foreground shadow-xl motion-reduce:animate-none"
        >
          <div className="flex items-center justify-between gap-2">
            <p className="text-sm font-semibold">Save to</p>
            <Button type="button" size="icon" variant="ghost" className="size-7" aria-label="Close save menu" onClick={closeMenu}>
              <X aria-hidden="true" className="size-4" />
            </Button>
          </div>

          {notebook && (
            <div className="rounded-lg border bg-muted/30 p-2">
              <label className="flex cursor-pointer items-center gap-2 rounded-md px-1 py-1.5 text-sm font-medium hover:bg-accent">
                <input
                  type="checkbox"
                  className="size-4 cursor-pointer accent-primary disabled:cursor-wait"
                  checked={notebook.saved}
                  disabled={busy}
                  aria-describedby={notebookHintId}
                  onChange={() => void run(notebook.onToggle, notebook.saved ? 'Removed from Study notebook.' : 'Added to Study notebook.')}
                />
                <BookOpen aria-hidden="true" className="size-4 shrink-0 text-primary" />
                <span>Study notebook</span>
              </label>
              <p id={notebookHintId} className="px-1 pt-1 text-xs leading-relaxed text-muted-foreground">
                Your personal study collection in Your videos, under the Saved tab.
              </p>
            </div>
          )}

          {playlistActions && (
            <>
              <p className="text-xs font-medium tracking-wide text-muted-foreground">Playlists</p>
              <ul className="flex max-h-40 flex-col gap-1 overflow-y-auto" aria-label="Playlists">
                {playlistActions.playlists.map((playlist) => {
                  const checked = savedIn.includes(playlist);
                  return (
                    <li key={playlist.id}>
                      <label className="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-sm hover:bg-accent">
                        <input
                          type="checkbox"
                          className="size-4 cursor-pointer accent-primary disabled:cursor-wait"
                          checked={checked}
                          disabled={busy}
                          onChange={() => void run(() => playlistActions.onToggle(playlist.id, !checked), 'Playlist updated.')}
                        />
                        {/* Playlist names remain plain text, including user-entered HTML. */}
                        <span className="truncate">{playlist.name}</span>
                      </label>
                    </li>
                  );
                })}
              </ul>

              <form className="flex gap-2 border-t pt-3" onSubmit={handleCreate}>
                <Input
                  className="h-8 min-w-0"
                  placeholder="New playlist"
                  aria-label="New playlist name"
                  maxLength={50}
                  disabled={busy}
                  value={newName}
                  onChange={(event) => setNewName(event.target.value)}
                />
                <Button type="submit" size="sm" variant="outline" disabled={busy || !newName.trim()} aria-label="Create playlist">
                  <Plus aria-hidden="true" />
                </Button>
              </form>
            </>
          )}

          {(busy || message) && (
            <p className="flex items-center gap-2 text-xs text-muted-foreground" role="status">
              {busy ? <Loader2 aria-hidden="true" className="size-3 animate-spin motion-reduce:animate-none" /> : <Check aria-hidden="true" className="size-3 text-primary" />}
              {busy ? 'Saving changes...' : message}
            </p>
          )}
          {error && <p className="text-xs leading-relaxed text-destructive" role="alert">{error}</p>}
        </div>
      )}
    </div>
  );
}
