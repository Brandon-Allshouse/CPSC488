// The video summary and quiz endpoint (see the README's "Study API").

import { request } from './client';

// Matches StudyMaterial.Question. Every string was written by an LLM: only ever render it as
// plain text (normal JSX does this), never as HTML.
export interface StudyQuestion {
  question: string;
  options: string[];
  /** Which option is right (0-3). */
  answerIndex: number;
  explanation: string;
}

/**
 * pending: being written now, ask again in a few seconds. busy: lots of videos are waiting, ask
 * again in a minute. unavailable: the summary writer is off or couldn't do this video right now.
 */
export type VideoStudy =
  | { status: 'ready'; summary: string; questions: StudyQuestion[] }
  | { status: 'pending' | 'busy' | 'unavailable' };

/** The video's summary and quiz. The first request for a video starts writing them. */
export async function fetchVideoStudy(youtubeId: string): Promise<VideoStudy> {
  return request<VideoStudy>(`/api/videos/${encodeURIComponent(youtubeId)}/study`);
}
