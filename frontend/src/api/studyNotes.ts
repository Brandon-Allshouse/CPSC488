import { request } from './client';

export interface StudyNote {
  youtubeId: string;
  note: string;
  updatedAt: string;
}

export async function fetchStudyNotes(): Promise<StudyNote[]> {
  const result = await request<{ notes: StudyNote[] }>('/api/me/study-notes');
  return result.notes;
}

export async function saveStudyNote(youtubeId: string, note: string): Promise<StudyNote> {
  const result = await request<{ note: StudyNote }>(`/api/me/study-notes/${encodeURIComponent(youtubeId)}`, {
    method: 'PUT',
    body: JSON.stringify({ note }),
  });
  return result.note;
}
