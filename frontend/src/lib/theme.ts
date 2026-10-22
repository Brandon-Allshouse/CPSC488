import { useState } from 'react';

export type Theme = 'light' | 'dark';

const STORAGE_KEY = 'brainfeed-theme';

/** The theme picked last time on this browser, or light if none (or storage is blocked). */
export function getStoredTheme(): Theme {
  try {
    return localStorage.getItem(STORAGE_KEY) === 'dark' ? 'dark' : 'light';
  } catch {
    return 'light';
  }
}

/** Switches the colors by toggling the .dark class on <html>; index.css does the rest. */
export function applyTheme(theme: Theme) {
  document.documentElement.classList.toggle('dark', theme === 'dark');
}

/** The current theme and a function to flip it. The choice is remembered in this browser only. */
export function useTheme() {
  const [theme, setTheme] = useState<Theme>(getStoredTheme);

  function toggleTheme() {
    const next: Theme = theme === 'dark' ? 'light' : 'dark';
    applyTheme(next);
    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // Private windows can block storage; the switch still works until the page reloads.
    }
    setTheme(next);
  }

  return { theme, toggleTheme };
}
