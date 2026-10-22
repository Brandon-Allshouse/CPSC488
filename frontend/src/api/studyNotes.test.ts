import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from './client';
import { fetchStudyNotes, saveStudyNote } from './studyNotes';

vi.mock('./client', () => ({ request: vi.fn() }));
beforeEach(() => vi.resetAllMocks());

describe('study notes api', () => {
  it('loads the signed-in user notes through the shared request helper', async () => {
    vi.mocked(request).mockResolvedValue({ notes: [{ youtubeId: 'video000001', note: 'SYN', updatedAt: '2026-10-22T00:00:00Z' }] });
    expect((await fetchStudyNotes())[0].note).toBe('SYN');
    expect(request).toHaveBeenCalledWith('/api/me/study-notes');
  });

  it('sends only the video id and note, with no client-selected user id', async () => {
    const note = { youtubeId: 'video000001', note: 'SYN\nACK', updatedAt: '2026-10-22T00:00:00Z' };
    vi.mocked(request).mockResolvedValue({ note });
    expect(await saveStudyNote(note.youtubeId, note.note)).toEqual(note);
    expect(request).toHaveBeenCalledWith('/api/me/study-notes/video000001', { method: 'PUT', body: JSON.stringify({ note: 'SYN\nACK' }) });
  });

  it('passes an empty note to clear text while preserving the notebook entry', async () => {
    vi.mocked(request).mockResolvedValue({ note: { youtubeId: 'video000001', note: '', updatedAt: '2026-10-22T00:00:00Z' } });
    await saveStudyNote('video000001', '');
    expect(request).toHaveBeenCalledWith('/api/me/study-notes/video000001', { method: 'PUT', body: '{"note":""}' });
  });
});
