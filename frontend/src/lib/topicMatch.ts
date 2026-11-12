// Client-side twins of the backend's topic name rules (topics/TopicNames.java and
// TopicMatcher.java), so the topic box can check input and find topics we already have without
// a request per keystroke. The backend checks everything again; these only make it feel instant.

import type { Topic } from '../api/feed';

export const MIN_LENGTH = 2;
export const MAX_LENGTH = 40;
export const MAX_WORDS = 3;
export const MAX_SUGGESTIONS = 6;

// Letters and digits in any language, spaces and & + ' - . # , starting with a letter or digit.
const ALLOWED = /^[\p{L}\p{N}][\p{L}\p{N} &+'.#-]*$/u;

/** Trims, collapses spaces and applies NFKC, like the backend before it checks a name. */
export function cleanTopicName(typed: string): string {
  return typed
    .normalize('NFKC')
    .replace(/\p{Cf}/gu, '')
    .replace(/[‘’]/g, "'")
    .replace(/\s+/g, ' ')
    .trim();
}

/** Why a typed name can't be a topic, or null if it's fine. Same messages as the backend. */
export function topicNameProblem(typed: string): string | null {
  const text = cleanTopicName(typed);
  if (!text) return 'Type a topic, like Black Holes.';
  if (text.length < MIN_LENGTH) return `Type at least ${MIN_LENGTH} characters.`;
  if (text.length > MAX_LENGTH) return `Keep it under ${MAX_LENGTH} characters.`;
  if (text.split(' ').length > MAX_WORDS) return 'Use one to three words, like Black Holes.';
  if (!ALLOWED.test(text)) return "Use letters, numbers and spaces (and & + ' - . # if you need them).";
  if (!/\p{L}/u.test(text)) return 'Type a topic with some letters in it, like Algebra 2.';
  return null;
}

/** Lowercase, "&" as "and", hyphens as spaces, other punctuation dropped (TopicNames.matchKey). */
export function matchKey(name: string): string {
  return name
    .normalize('NFKC')
    .toLowerCase()
    .replace(/&/g, ' and ')
    .replace(/[-/]/g, ' ')
    .replace(/[^\p{L}\p{N} +#]/gu, '')
    .replace(/\s+/g, ' ')
    .trim();
}

// A rough English singular, the same rules as TopicNames.singular.
function singular(word: string): string {
  if (word.length <= 3 || !word.endsWith('s')) return word;
  if (/(ss|us|is|ics)$/.test(word)) return word;
  if (word.endsWith('ies') && word.length > 4) return `${word.slice(0, -3)}y`;
  if (/(ches|shes|xes|sses)$/.test(word)) return word.slice(0, -2);
  return word.slice(0, -1);
}

function singularKey(name: string): string {
  return matchKey(name).split(' ').map(singular).join(' ');
}

/**
 * The catalog topic with the same name as what was typed, ignoring case, punctuation and
 * plurals ("black hole" finds "Black Holes"). Typos are left to the backend, which can say it
 * picked a close match.
 */
export function findSameTopic(typed: string, topics: Topic[]): Topic | undefined {
  const key = matchKey(typed);
  const single = singularKey(typed);
  return (
    topics.find((topic) => matchKey(topic.name) === key) ??
    topics.find((topic) => {
      const other = matchKey(topic.name);
      return singularKey(topic.name) === single || other === `${key}s` || key === `${other}s`;
    })
  );
}

/**
 * Topics to suggest while typing: names starting with the text first, then names with a word
 * starting with it, then names containing it anywhere. Subjects and topics both count.
 */
export function suggestTopics(typed: string, topics: Topic[], limit = MAX_SUGGESTIONS): Topic[] {
  const key = matchKey(typed);
  if (!key) return [];
  const rank = (topic: Topic) => {
    const name = matchKey(topic.name);
    if (name.startsWith(key)) return 0;
    if (name.split(' ').some((word) => word.startsWith(key))) return 1;
    return name.includes(key) ? 2 : -1;
  };
  return topics
    .map((topic) => ({ topic, rank: rank(topic) }))
    .filter((entry) => entry.rank >= 0)
    .sort((a, b) => a.rank - b.rank || a.topic.name.length - b.topic.name.length)
    .slice(0, limit)
    .map((entry) => entry.topic);
}
