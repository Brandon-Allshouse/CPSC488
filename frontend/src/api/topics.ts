// The AI topic finder's endpoints (see the README's "Topic finder API"). Both need a login.

import { request } from './client';
import type { Topic } from './feed';

/** A new topic the AI proposed. Send key back to add it. name and subject came from the LLM. */
export interface TopicIdea {
  key: string;
  name: string;
  subject: string;
  /** true if the subject doesn't exist yet and gets added too */
  newSubject: boolean;
}

export interface TopicSuggestions {
  /** Topics already in the catalog that fit. */
  matches: Topic[];
  /** New topics the user can add. */
  ideas: TopicIdea[];
}

/** Asks the AI for topics that fit a few keywords or examples. Takes a few seconds. */
export async function suggestTopics(text: string): Promise<TopicSuggestions> {
  return request<TopicSuggestions>('/api/me/topic-suggestions', { method: 'POST', body: JSON.stringify({ text }) });
}

/** Adds a suggested topic. Returns the topics to pick: its new subject first (if one was made), then it. */
export async function addSuggestedTopic(key: string): Promise<Topic[]> {
  const { topics } = await request<{ topics: Topic[] }>('/api/me/topics', { method: 'POST', body: JSON.stringify({ key }) });
  return topics;
}
