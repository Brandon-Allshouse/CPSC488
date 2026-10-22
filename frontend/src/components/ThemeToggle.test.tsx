// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import ThemeToggle from './ThemeToggle';

afterEach(() => {
  cleanup();
  localStorage.clear();
  document.documentElement.classList.remove('dark');
});

describe('ThemeToggle', () => {
  it('starts in light mode and switches to dark and back', () => {
    render(<ThemeToggle />);

    fireEvent.click(screen.getByRole('button', { name: 'Switch to dark mode' }));
    expect(document.documentElement.classList.contains('dark')).toBe(true);
    expect(localStorage.getItem('brainfeed-theme')).toBe('dark');

    fireEvent.click(screen.getByRole('button', { name: 'Switch to light mode' }));
    expect(document.documentElement.classList.contains('dark')).toBe(false);
    expect(localStorage.getItem('brainfeed-theme')).toBe('light');
  });

  it('remembers dark mode from last time', () => {
    localStorage.setItem('brainfeed-theme', 'dark');
    render(<ThemeToggle />);

    expect(screen.getByRole('button', { name: 'Switch to light mode' })).toBeTruthy();
  });
});
