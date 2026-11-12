// The study notes endpoints (see the README's "Study notebook API"). Both need a login and only
// touch the signed-in user's own notes.

import { request } from './client';

export interface StudyNote {
  youtubeId: string;
  note: string;
  updatedAt: string;
}

/** Every note the user has written on their notebook videos. */
export async function fetchStudyNotes(): Promise<StudyNote[]> {
  const result = await request<{ notes: StudyNote[] }>('/api/me/study-notes');
  return result.notes;
}

/** Saves the note for one notebook video. An empty note clears it but keeps the video. */
export async function saveStudyNote(youtubeId: string, note: string): Promise<StudyNote> {
  const result = await request<{ note: StudyNote }>(`/api/me/study-notes/${encodeURIComponent(youtubeId)}`, {
    method: 'PUT',
    body: JSON.stringify({ note }),
  });
  return result.note;
}
