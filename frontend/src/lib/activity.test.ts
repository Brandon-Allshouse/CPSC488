import { describe, expect, it } from 'vitest';
import { countWatchedToday, formatCapped, groupByDay, isSameDay, monthYear, topCreators } from './activity';

// Local time on purpose, so "today" means the same thing as it does for the user.
const now = new Date(2026, 9, 26, 15, 0);

function at(year: number, month: number, day: number, hour = 12) {
  return { watchedAt: new Date(year, month, day, hour).toISOString() };
}

describe('activity helpers', () => {
  it('knows when two times are on the same day', () => {
    expect(isSameDay(new Date(2026, 9, 26, 0, 5), now)).toBe(true);
    expect(isSameDay(new Date(2026, 9, 25, 23, 59), now)).toBe(false);
  });

  it('counts only the videos watched today', () => {
    const videos = [at(2026, 9, 26, 9), at(2026, 9, 26, 1), at(2026, 9, 25)];
    expect(countWatchedToday(videos, now)).toBe(2);
    expect(countWatchedToday([], now)).toBe(0);
  });

  it('adds a plus when the history is full, since there could be more', () => {
    expect(formatCapped(3, 25)).toBe('3');
    expect(formatCapped(25, 25)).toBe('25+');
  });

  it('groups history into today, yesterday, this week and older, in order', () => {
    const videos = [at(2026, 9, 26), at(2026, 9, 25), at(2026, 9, 25, 8), at(2026, 9, 22), at(2026, 8, 1)];
    const groups = groupByDay(videos, now);

    expect(groups.map((group) => group.label)).toEqual(['Today', 'Yesterday', 'This week', 'Older']);
    expect(groups[1].videos).toHaveLength(2);
  });

  it('leaves out groups with nothing in them', () => {
    expect(groupByDay([at(2026, 9, 26)], now).map((group) => group.label)).toEqual(['Today']);
    expect(groupByDay([], now)).toEqual([]);
  });

  it('ranks creators by how many videos are theirs', () => {
    const videos = [{ creator: 'Khan Academy' }, { creator: 'Crash Course' }, { creator: 'Crash Course' }, { creator: 'Numberphile' }];
    expect(topCreators(videos, 2)).toEqual([
      { creator: 'Crash Course', count: 2 },
      { creator: 'Khan Academy', count: 1 },
    ]);
  });

  it('formats the month someone joined, and gives up quietly on a bad date', () => {
    expect(monthYear('2026-10-05T12:00:00Z')).toBe('October 2026');
    expect(monthYear('not a date')).toBe('');
  });
});
