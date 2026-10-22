import type { SavedVideo } from '../api/feed';
import type { StudyNote } from '../api/studyNotes';

export const MAX_STUDY_NOTE_LENGTH = 2000;
export type StudyNotes = Record<string, StudyNote>;
export type NoteFilter = 'all' | 'with-notes' | 'without-notes';

export function hasStudyNote(text: string | undefined): boolean {
  return Boolean(text?.trim());
}

export function filterStudyVideos(videos: SavedVideo[], notes: StudyNotes, query: string, filter: NoteFilter): SavedVideo[] {
  const search = query.trim().toLocaleLowerCase();
  return videos.filter((video) => {
    const text = notes[video.youtubeId]?.note ?? '';
    if (filter === 'with-notes' && !hasStudyNote(text)) return false;
    if (filter === 'without-notes' && hasStudyNote(text)) return false;
    return !search || [video.title, video.creator, text].some((value) => value.toLocaleLowerCase().includes(search));
  });
}

/** A plain text export includes saved notes only; unfinished editor text stays in the editor. */
export function buildStudyNotesText(videos: SavedVideo[], notes: StudyNotes): string {
  const entries = videos.filter((video) => hasStudyNote(notes[video.youtubeId]?.note)).map((video) => [
    video.title,
    'Creator: ' + video.creator,
    'Video: https://www.youtube.com/watch?v=' + encodeURIComponent(video.youtubeId),
    '',
    notes[video.youtubeId].note,
  ].join('\n'));
  return ['BrainFeed Study notebook', '', ...entries.flatMap((entry, index) => index ? ['\n--------------------\n', entry] : [entry])].join('\n') + '\n';
}

export function downloadStudyNotes(videos: SavedVideo[], notes: StudyNotes): void {
  const url = URL.createObjectURL(new Blob([buildStudyNotesText(videos, notes)], { type: 'text/plain;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = 'brainfeed-study-notes.txt';
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
