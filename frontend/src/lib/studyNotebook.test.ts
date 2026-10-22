import { describe, expect, it } from 'vitest';
import { buildStudyNotesText, filterStudyVideos, type StudyNotes } from './studyNotebook';

const videos = [
  { youtubeId: 'video000001', title: 'TCP handshake', creator: 'Network lessons' },
  { youtubeId: 'video000002', title: 'Matrix multiplication', creator: 'Computing lab' },
];
const notes: StudyNotes = { video000001: { youtubeId: 'video000001', note: 'SYN, SYN/ACK, ACK', updatedAt: '2026-10-22T00:00:00Z' } };

describe('study notebook search and export', () => {
  it('searches saved note text, titles and creators without changing the source lists', () => {
    expect(filterStudyVideos(videos, notes, ' syn/ack ', 'all')).toEqual([videos[0]]);
    expect(filterStudyVideos(videos, notes, 'MATRIX', 'all')).toEqual([videos[1]]);
    expect(filterStudyVideos(videos, notes, 'computing', 'all')).toEqual([videos[1]]);
    expect(videos).toHaveLength(2);
  });

  it('combines a search with the with-notes and without-notes filters', () => {
    expect(filterStudyVideos(videos, notes, '', 'with-notes')).toEqual([videos[0]]);
    expect(filterStudyVideos(videos, notes, '', 'without-notes')).toEqual([videos[1]]);
    expect(filterStudyVideos(videos, notes, 'TCP', 'without-notes')).toEqual([]);
  });

  it('exports titles, creators, YouTube links and saved note text', () => {
    const text = buildStudyNotesText(videos, notes);
    expect(text).toContain('TCP handshake');
    expect(text).toContain('Creator: Network lessons');
    expect(text).toContain('https://www.youtube.com/watch?v=video000001');
    expect(text).toContain('SYN, SYN/ACK, ACK');
    expect(text).not.toContain('Matrix multiplication');
  });

  it('does not export blank notes or notes whose notebook video was removed', () => {
    const extra: StudyNotes = { ...notes, video000002: { youtubeId: 'video000002', note: '   ', updatedAt: '' }, oldVideo: { youtubeId: 'oldVideo', note: 'Removed entry', updatedAt: '' } };
    expect(buildStudyNotesText(videos, extra)).not.toContain('Removed entry');
    expect(buildStudyNotesText(videos, extra)).not.toContain('Matrix multiplication');
  });
});
