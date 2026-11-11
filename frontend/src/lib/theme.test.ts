// @vitest-environment jsdom
import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { applyTheme, getStoredTheme, useTheme } from './theme';

afterEach(() => {
  localStorage.clear();
  document.documentElement.classList.remove('dark');
  vi.restoreAllMocks();
});

describe('theme', () => {
  it('starts light when nothing was picked before', () => {
    expect(getStoredTheme()).toBe('light');
  });

  it('remembers dark, and treats anything else as light', () => {
    localStorage.setItem('brainfeed-theme', 'dark');
    expect(getStoredTheme()).toBe('dark');
    localStorage.setItem('brainfeed-theme', '<script>');
    expect(getStoredTheme()).toBe('light');
  });

  it('falls back to light when storage is blocked', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    expect(getStoredTheme()).toBe('light');
  });

  it('switches the dark class on the page', () => {
    applyTheme('dark');
    expect(document.documentElement.classList.contains('dark')).toBe(true);
    applyTheme('light');
    expect(document.documentElement.classList.contains('dark')).toBe(false);
  });

  it('toggling flips the theme and saves it', () => {
    const { result } = renderHook(() => useTheme());
    expect(result.current.theme).toBe('light');
    act(() => result.current.toggleTheme());
    expect(result.current.theme).toBe('dark');
    expect(localStorage.getItem('brainfeed-theme')).toBe('dark');
    expect(document.documentElement.classList.contains('dark')).toBe(true);
  });

  it('still toggles when storage is blocked', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    const { result } = renderHook(() => useTheme());
    act(() => result.current.toggleTheme());
    expect(result.current.theme).toBe('dark');
  });
});
