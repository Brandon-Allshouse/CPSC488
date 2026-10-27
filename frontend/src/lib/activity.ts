// Small numbers worked out from data the app already loads (watch history, saved and liked videos).
// Everything here is computed in the browser; there's no stats endpoint yet, so these only cover
// what the backend keeps (the newest 25 watched videos, see WatchHistoryController.LIMIT).

interface Watched {
  watchedAt: string;
}

interface ByCreator {
  creator: string;
}

/** True if both times fall on the same calendar day in the user's own time zone. */
export function isSameDay(a: Date, b: Date) {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}

/** How many of these videos were watched today. */
export function countWatchedToday(videos: Watched[], now = new Date()) {
  return videos.filter((video) => isSameDay(new Date(video.watchedAt), now)).length;
}

/**
 * A count for display. The history only keeps `limit` videos, so a full history means there could
 * be more than we can see: "25+" instead of claiming exactly 25.
 */
export function formatCapped(count: number, limit: number) {
  return count >= limit ? `${limit}+` : String(count);
}

export type DayGroup = 'Today' | 'Yesterday' | 'This week' | 'Older';

/** Splits a newest-first list into Today / Yesterday / This week / Older, keeping the order. */
export function groupByDay<T extends Watched>(videos: T[], now = new Date()) {
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const day = 86_400_000;
  const groups = new Map<DayGroup, T[]>();
  for (const video of videos) {
    const time = new Date(video.watchedAt).getTime();
    let label: DayGroup;
    if (time >= startOfToday) label = 'Today';
    else if (time >= startOfToday - day) label = 'Yesterday';
    else if (time >= startOfToday - 6 * day) label = 'This week';
    else label = 'Older';
    groups.set(label, [...(groups.get(label) ?? []), video]);
  }
  return [...groups].map(([label, items]) => ({ label, videos: items }));
}

/** Creators by how many of these videos are theirs, most first. Ties keep the order they appeared in. */
export function topCreators(videos: ByCreator[], max = 3) {
  const counts = new Map<string, number>();
  for (const { creator } of videos) counts.set(creator, (counts.get(creator) ?? 0) + 1);
  return [...counts]
    .map(([creator, count]) => ({ creator, count }))
    .sort((a, b) => b.count - a.count)
    .slice(0, max);
}

/** "October 2026", for "Learning since" in the sidebar. Empty if the date can't be read. */
export function monthYear(iso: string) {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
}
