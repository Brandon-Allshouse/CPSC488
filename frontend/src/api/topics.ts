// The topic finder's endpoint (see the README's "Topic finder API"). Needs a login.

import { request } from './client';
import type { Topic } from './feed';

/** What typing a topic name got you. */
export interface AddedTopic {
  /** The topic you now follow: one we had, or a new one. */
  topic: Topic;
  /** true if it was just added to BrainFeed. */
  created: boolean;
  /** true if the backend picked a close match for a likely typo ("calculas" gave Calculus). */
  similar: boolean;
}

/**
 * Finds or adds the topic called {@link name} and follows it. Answers in well under a second.
 * With asTyped, close matches are skipped, for "No, I meant Generics, not Genetics".
 */
export async function addTopic(name: string, asTyped = false): Promise<AddedTopic> {
  const body = asTyped ? { name, asTyped: true } : { name };
  return request<AddedTopic>('/api/me/topics', { method: 'POST', body: JSON.stringify(body) });
}
