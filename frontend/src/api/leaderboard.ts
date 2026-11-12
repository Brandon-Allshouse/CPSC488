// The leaderboard endpoint (see the README's "How scoring works").

import { request } from './client';

/** One row. Only the username is shown, never anything else about the account. */
export interface LeaderboardEntry {
  /** Shared by people with the same points (1, 2, 2, 4). null if you have no points yet. */
  rank: number | null;
  username: string;
  points: number;
  /** Correct first answers in the period. */
  correct: number;
  /** Days in a row with a correct answer, 0 if the streak has ended. */
  streak: number;
}

export interface LeaderboardBoard {
  /** The top 20. */
  top: LeaderboardEntry[];
  /** Your own row, even outside the top 20. null for guests. */
  you: LeaderboardEntry | null;
}

export interface Leaderboard {
  /** When "this week" started (Monday 00:00 UTC), as an ISO date. */
  weekStart: string;
  week: LeaderboardBoard;
  allTime: LeaderboardBoard;
}

/** This week's and the all-time leaderboard in one request. */
export async function fetchLeaderboard(): Promise<Leaderboard> {
  return request<Leaderboard>('/api/leaderboard');
}
