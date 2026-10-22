import { useEffect, useRef, useState } from 'react';
import { Check, CircleAlert, Download, Loader2, Pencil, Save, Search, Trash2, X } from 'lucide-react';
import { ApiError } from '../api/client';
import type { SavedVideo } from '../api/feed';
import { fetchStudyNotes, saveStudyNote } from '../api/studyNotes';
import { downloadStudyNotes, filterStudyVideos, hasStudyNote, MAX_STUDY_NOTE_LENGTH, type NoteFilter, type StudyNotes } from '../lib/studyNotebook';
import { Alert } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';

interface Props {
  videos: SavedVideo[];
  removing: boolean;
  onOpenVideo: (video: SavedVideo) => void;
  onRemoveVideo: (youtubeId: string) => Promise<void>;
  onSessionExpired: () => void;
  onDirtyChange: (dirty: boolean) => void;
}

export default function StudyNotebook({ videos, removing, onOpenVideo, onRemoveVideo, onSessionExpired, onDirtyChange }: Props) {
  const [notes, setNotes] = useState<StudyNotes>({});
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<NoteFilter>('all');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [draft, setDraft] = useState('');
  const [saving, setSaving] = useState(false);
  const [editorError, setEditorError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const savingRef = useRef(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const selected = videos.find((video) => video.youtubeId === selectedId);
  const originalNote = selectedId ? notes[selectedId]?.note ?? '' : '';
  const dirty = Boolean(selected && draft !== originalNote);
  const matches = filterStudyVideos(videos, notes, query, filter);
  const noteCount = videos.filter((video) => hasStudyNote(notes[video.youtubeId]?.note)).length;

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setLoadError(null);
    fetchStudyNotes()
      .then((loaded) => {
        if (!cancelled) setNotes(Object.fromEntries(loaded.map((note) => [note.youtubeId, note])));
      })
      .catch((error: unknown) => {
        if (cancelled) return;
        if (error instanceof ApiError && error.status === 401) onSessionExpired();
        else setLoadError(error instanceof Error ? error.message : 'Could not load your notes.');
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [reload]);

  useEffect(() => { onDirtyChange(dirty); }, [dirty, onDirtyChange]);
  useEffect(() => () => onDirtyChange(false), [onDirtyChange]);

  useEffect(() => {
    if (!dirty) return;
    function warnBeforeLeaving(event: BeforeUnloadEvent) {
      event.preventDefault();
      event.returnValue = '';
    }
    window.addEventListener('beforeunload', warnBeforeLeaving);
    return () => window.removeEventListener('beforeunload', warnBeforeLeaving);
  }, [dirty]);

  useEffect(() => {
    if (selectedId && !videos.some((video) => video.youtubeId === selectedId)) {
      setSelectedId(null);
      setDraft('');
      setEditorError(null);
    }
  }, [videos, selectedId]);

  useEffect(() => { if (selectedId) textareaRef.current?.focus(); }, [selectedId]);

  function mayDiscardDraft() {
    return !dirty || window.confirm('Discard your unsaved note changes?');
  }

  function editNote(video: SavedVideo) {
    if (savingRef.current || (selectedId !== video.youtubeId && !mayDiscardDraft())) return;
    if (selectedId === video.youtubeId) { textareaRef.current?.focus(); return; }
    setSelectedId(video.youtubeId);
    setDraft(notes[video.youtubeId]?.note ?? '');
    setEditorError(null);
    setMessage(null);
  }

  function closeEditor() {
    if (savingRef.current || !mayDiscardDraft()) return;
    setSelectedId(null);
    setDraft('');
    setEditorError(null);
    setMessage(null);
  }

  async function persistNote(text: string) {
    if (!selected || savingRef.current) return;
    if (text.length > MAX_STUDY_NOTE_LENGTH || text.includes('\0')) {
      setEditorError('Use at most ' + MAX_STUDY_NOTE_LENGTH + ' characters and remove any null characters.');
      return;
    }
    const youtubeId = selected.youtubeId;
    savingRef.current = true;
    setSaving(true);
    setEditorError(null);
    setMessage(null);
    try {
      const saved = await saveStudyNote(youtubeId, text);
      setNotes((current) => ({ ...current, [youtubeId]: saved }));
      setDraft(saved.note);
      setMessage(hasStudyNote(saved.note) ? 'Note saved to your account.' : 'Note cleared. The video is still in your notebook.');
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) onSessionExpired();
      else setEditorError(error instanceof Error ? error.message : 'Could not save your note. Your changes are still here.');
    } finally {
      savingRef.current = false;
      setSaving(false);
    }
  }

  function clearNote() {
    if (window.confirm('Clear this note? The video will stay in your notebook.')) void persistNote('');
  }

  async function removeVideo(video: SavedVideo) {
    const hasDraft = selectedId === video.youtubeId && dirty;
    if ((hasDraft || hasStudyNote(notes[video.youtubeId]?.note)) &&
      !window.confirm('Remove this video from your notebook, including its note? Playlist copies stay saved.')) return;
    try {
      await onRemoveVideo(video.youtubeId);
    } catch (error) {
      setEditorError(error instanceof Error ? error.message : 'Could not remove this study video.');
    }
  }

  function exportNotes() {
    if (!noteCount) return;
    try {
      downloadStudyNotes(videos, notes);
      setMessage('Downloaded your saved notes. Unsaved edits are not included.');
    } catch {
      setEditorError('Could not download your notes. Try again.');
    }
  }

  return (
    <div id="study-notebook" className="space-y-4">
      <p className="text-sm leading-relaxed text-muted-foreground">Keep your own takeaways, questions, and revision notes beside the videos you are studying.</p>
      <div className="flex flex-col gap-2 lg:flex-row lg:items-center" role="search" aria-label="Search Study notebook">
        <div className="relative min-w-0 flex-1">
          <Search aria-hidden="true" className="pointer-events-none absolute top-3 left-3 size-4 text-muted-foreground" />
          <Input id="notebook-search" type="search" aria-label="Search notebook" placeholder="Search titles, creators, or saved notes" className="pl-9" value={query} onChange={(event) => setQuery(event.target.value)} />
        </div>
        <select
          id="notebook-note-filter"
          aria-label="Filter notebook by notes"
          className="h-10 rounded-md border border-input bg-background px-3 text-sm shadow-xs outline-none transition-colors focus-visible:border-ring focus-visible:ring-2 focus-visible:ring-ring/40"
          value={filter}
          onChange={(event) => setFilter(event.target.value as NoteFilter)}
          disabled={loading || Boolean(loadError)}
        >
          <option value="all">All study videos</option>
          <option value="with-notes">With notes</option>
          <option value="without-notes">Without notes</option>
        </select>
        <Button variant="outline" className="h-10" onClick={exportNotes} disabled={loading || saving || !noteCount || Boolean(loadError)} title="Download all saved notebook notes as a text file">
          <Download aria-hidden="true" />Download notes
        </Button>
      </div>
      <p className="text-xs text-muted-foreground">Showing {matches.length} of {videos.length} videos · {noteCount} with notes. Downloads include all saved notebook notes.</p>

      {loading && <p role="status" className="flex items-center gap-2 text-sm text-muted-foreground"><Loader2 aria-hidden="true" className="size-4 animate-spin" />Loading notes...</p>}
      {loadError && <Alert variant="destructive" role="alert"><CircleAlert aria-hidden="true" /><div className="space-y-2"><p>{loadError}</p><Button variant="outline" size="sm" onClick={() => setReload((value) => value + 1)}>Retry notes</Button></div></Alert>}
      {editorError && <Alert variant="destructive" role="alert"><CircleAlert aria-hidden="true" />{editorError}</Alert>}
      {message && <p role="status" className="flex items-center gap-2 text-sm text-primary"><Check aria-hidden="true" className="size-4" />{message}</p>}

      <Table className="your-videos-table">
        <TableHeader><TableRow><TableHead scope="col">Title</TableHead><TableHead scope="col">Creator</TableHead><TableHead scope="col" className="w-24"><span className="sr-only">Actions</span></TableHead></TableRow></TableHeader>
        <TableBody>
          {matches.length === 0 ? <TableRow><TableCell colSpan={3} className="py-8 text-center text-muted-foreground">
            {videos.length ? 'No matching study videos. Try another search or filter.' : 'No study videos yet. Choose Study notebook from the bookmark menu.'}
          </TableCell></TableRow> : matches.map((video) => (
            <TableRow key={video.youtubeId}>
              <TableCell className="font-medium"><button type="button" className="cursor-pointer text-left underline-offset-4 transition-colors hover:text-primary hover:underline" title="Watch in your feed" onClick={() => onOpenVideo(video)}>{video.title}</button>
                {hasStudyNote(notes[video.youtubeId]?.note) && <Badge variant="secondary" className="mt-1 ml-2">Has notes</Badge>}
              </TableCell>
              <TableCell className="text-muted-foreground">{video.creator}</TableCell>
              <TableCell><div className="flex justify-end gap-1">
                <Button size="icon" variant="ghost" aria-label={'Edit note for ' + video.title} aria-expanded={selectedId === video.youtubeId} aria-controls="notebook-note-editor" disabled={loading || saving || removing || Boolean(loadError)} onClick={() => editNote(video)}><Pencil aria-hidden="true" /></Button>
                <Button size="icon" variant="ghost" aria-label={'Remove ' + video.title + ' from Study notebook'} disabled={loading || saving || removing || Boolean(loadError)} onClick={() => void removeVideo(video)}><X aria-hidden="true" /></Button>
              </div></TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>

      {selected && (
        <form id="notebook-note-editor" className="space-y-3 rounded-xl border bg-muted/20 p-4 sm:p-5" aria-label={'Study notes for ' + selected.title} onSubmit={(event) => { event.preventDefault(); void persistNote(draft); }}>
          <div className="flex items-start justify-between gap-3"><div className="min-w-0"><h3 className="text-base font-semibold">Your notes</h3><p className="break-words text-sm text-muted-foreground">{selected.title}</p></div><Button size="icon" variant="ghost" aria-label="Close note editor" disabled={saving} onClick={closeEditor}><X aria-hidden="true" /></Button></div>
          <label htmlFor="study-note-text" className="block text-sm font-medium">Personal study notes</label>
          <textarea
            ref={textareaRef}
            id="study-note-text"
            className="min-h-44 w-full resize-y rounded-lg border border-input bg-background px-3 py-2 text-sm leading-relaxed shadow-xs outline-none transition-[border-color,box-shadow] focus-visible:border-ring focus-visible:ring-2 focus-visible:ring-ring/40 disabled:opacity-60"
            value={draft}
            maxLength={MAX_STUDY_NOTE_LENGTH}
            disabled={saving}
            aria-describedby="study-note-hint"
            placeholder="What did you learn? Add key ideas, examples, or questions to revisit."
            onChange={(event) => { setDraft(event.target.value); setEditorError(null); setMessage(null); }}
            onKeyDown={(event) => {
              if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') { event.preventDefault(); void persistNote(draft); }
              else if (event.key === 'Escape') { event.preventDefault(); closeEditor(); }
            }}
          />
          <div id="study-note-hint" className="flex flex-wrap justify-between gap-2 text-xs text-muted-foreground"><span>Private to your account. Ctrl+Enter saves.</span><span>{draft.length}/{MAX_STUDY_NOTE_LENGTH} characters</span></div>
          <div className="flex flex-wrap items-center gap-2">
            <Button type="submit" disabled={saving || !dirty}>{saving ? <Loader2 aria-hidden="true" className="animate-spin" /> : <Save aria-hidden="true" />}{saving ? 'Saving note...' : 'Save note'}</Button>
            <Button variant="outline" onClick={closeEditor} disabled={saving}>Close</Button>
            <Button variant="ghost" className="text-destructive sm:ml-auto" onClick={clearNote} disabled={saving || (!hasStudyNote(originalNote) && !draft)}><Trash2 aria-hidden="true" />Clear note</Button>
            {dirty && <span className="text-xs text-muted-foreground">Unsaved changes</span>}
          </div>
        </form>
      )}
    </div>
  );
}
