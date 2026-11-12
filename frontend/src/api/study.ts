// The video summary and quiz endpoints (see the README's "Study API" and "How scoring works").

import { request } from './client';

// Matches the questions in GET /study. The answer isn't included: the backend only says which
// option is right after you answer (answerVideoQuestion), so it can't be read from the network tab.
// Every string was written by an LLM: only ever render it as plain text (normal JSX does this).
export interface StudyQuestion {
  question: string;
  options: string[];
}

/**
 * pending: being written now, ask again in a few seconds. busy: lots of videos are waiting, ask
 * again in a minute. unavailable: the summary writer is off or couldn't do this video right now.
 */
export type VideoStudy =
  | { status: 'ready'; summary: string; questions: StudyQuestion[] }
  | { status: 'pending' | 'busy' | 'unavailable' };

/** What the backend said about one answer. */
export interface AnswerResult {
  correct: boolean;
  /** Which option was right (0-3). */
  answerIndex: number;
  /** Why it's right. Written by the LLM, so plain text only. */
  explanation: string;
  /** False for guests, who can check answers but don't earn points. */
  signedIn: boolean;
  /** True if this was your first answer to the question, so it was saved. Only first answers score. */
  counted: boolean;
  /** 10 for a right first answer, otherwise 0. */
  pointsEarned: number;
  /** Your daily streak in days after this answer. null for guests. */
  streak: number | null;
}

/** The video's summary and quiz. The first request for a video starts writing them. */
export async function fetchVideoStudy(youtubeId: string): Promise<VideoStudy> {
  return request<VideoStudy>(`/api/videos/${encodeURIComponent(youtubeId)}/study`);
}

/** Sends one answer (question 0-2, choice 0-3) and gets back whether it was right. */
export async function answerVideoQuestion(youtubeId: string, question: number, choice: number): Promise<AnswerResult> {
  return request<AnswerResult>(`/api/videos/${encodeURIComponent(youtubeId)}/study/answers`, {
    method: 'POST',
    body: JSON.stringify({ question, choice }),
  });
}
