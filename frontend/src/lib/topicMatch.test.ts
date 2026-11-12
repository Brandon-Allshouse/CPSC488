import { describe, expect, it } from 'vitest';
import { cleanTopicName, findSameTopic, matchKey, suggestTopics, topicNameProblem } from './topicMatch';

const topics = [
  { id: 1, name: 'Math', parentId: null },
  { id: 2, name: 'Space', parentId: null },
  { id: 3, name: 'Algebra 2', parentId: 1 },
  { id: 4, name: 'Pre-Algebra', parentId: 1 },
  { id: 5, name: 'Black Holes', parentId: 2 },
  { id: 6, name: 'Genetics', parentId: null },
  { id: 7, name: 'Linear Algebra', parentId: 1 },
];

describe('topic names', () => {
  it('cleans up spacing, full-width letters and curly apostrophes', () => {
    expect(cleanTopicName('  black \t holes ')).toBe('black holes');
    expect(cleanTopicName('ａｌｇｅｂｒａ')).toBe('algebra');
    expect(cleanTopicName('newton’s laws')).toBe("newton's laws");
  });

  it('accepts one to three words with the few allowed symbols', () => {
    for (const name of ['Black Holes', 'C++', 'C#', 'Node.js', 'Rock & Roll', 'Español', 'Algebra 2']) {
      expect(topicNameProblem(name)).toBeNull();
    }
  });

  it('explains what is wrong with a bad name', () => {
    expect(topicNameProblem('')).toContain('Type a topic');
    expect(topicNameProblem('a')).toContain('at least 2');
    expect(topicNameProblem('x'.repeat(41))).toContain('under 40');
    expect(topicNameProblem('one two three four')).toContain('one to three words');
    expect(topicNameProblem('<script>')).toContain('Use letters');
    expect(topicNameProblem('42')).toContain('some letters');
  });
});

describe('matching', () => {
  it('ignores case, punctuation and ampersands', () => {
    expect(matchKey('Pre-Algebra')).toBe(matchKey('pre algebra'));
    expect(matchKey('Rock & Roll')).toBe('rock and roll');
  });

  it('finds the same topic in another case or plural', () => {
    expect(findSameTopic('algebra 2', topics)?.id).toBe(3);
    expect(findSameTopic('black hole', topics)?.id).toBe(5);
    expect(findSameTopic('genetic', topics)?.id).toBe(6);
    expect(findSameTopic('pre algebra', topics)?.id).toBe(4);
  });

  it('leaves typos and new topics to the backend', () => {
    expect(findSameTopic('calculas', topics)).toBeUndefined();
    expect(findSameTopic('Algebra 3', topics)).toBeUndefined();
    expect(findSameTopic('Comets', topics)).toBeUndefined();
  });

  it('suggests names starting with the text first', () => {
    expect(suggestTopics('alg', topics).map((t) => t.name)).toEqual(['Algebra 2', 'Pre-Algebra', 'Linear Algebra']);
    expect(suggestTopics('holes', topics).map((t) => t.name)).toEqual(['Black Holes']);
    expect(suggestTopics('', topics)).toEqual([]);
    expect(suggestTopics('zzz', topics)).toEqual([]);
  });

  it('caps how many it suggests', () => {
    const many = Array.from({ length: 20 }, (_, i) => ({ id: i, name: `Topic ${i}`, parentId: null }));
    expect(suggestTopics('topic', many)).toHaveLength(6);
  });
});
