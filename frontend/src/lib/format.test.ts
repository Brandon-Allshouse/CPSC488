import { describe, expect, it } from 'vitest';
import { timeAgo } from './format';

const now = Date.parse('2026-10-22T12:00:00Z');

describe('timeAgo', () => {
  it('uses the biggest unit that fits', () => {
    expect(timeAgo('2026-07-22T12:00:00Z', now)).toBe('3 months ago');
    expect(timeAgo('2024-10-01T12:00:00Z', now)).toBe('2 years ago');
    expect(timeAgo('2026-10-22T09:00:00Z', now)).toBe('3 hours ago');
  });

  it('says yesterday and just now in words', () => {
    expect(timeAgo('2026-10-21T12:00:00Z', now)).toBe('yesterday');
    expect(timeAgo('2026-10-22T11:59:50Z', now)).toBe('just now');
  });

  it('returns null for a missing or broken date', () => {
    expect(timeAgo(undefined, now)).toBeNull();
    expect(timeAgo('not a date', now)).toBeNull();
  });
});
