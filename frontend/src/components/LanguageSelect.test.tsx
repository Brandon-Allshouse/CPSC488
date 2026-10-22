// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import LanguageSelect from './LanguageSelect';

afterEach(() => {
  cleanup();
});

// The picker is UI only for now (see the comment in LanguageSelect.tsx), so this only checks the
// control itself.
describe('LanguageSelect', () => {
  it('starts on English and lists the other languages', () => {
    render(<LanguageSelect />);
    const select = screen.getByRole<HTMLSelectElement>('combobox');
    expect(select.value).toBe('en');
    expect(screen.getAllByRole('option').map((option) => option.textContent)).toEqual([
      'English',
      'Español',
      'Français',
      'Deutsch',
      'Português',
      '中文',
      '日本語',
    ]);
  });

  it('remembers the picked language', () => {
    render(<LanguageSelect />);
    const select = screen.getByRole<HTMLSelectElement>('combobox');
    fireEvent.change(select, { target: { value: 'es' } });
    expect(select.value).toBe('es');
  });

  it('is labelled for screen readers', () => {
    render(<LanguageSelect />);
    expect(screen.getByLabelText(/Language/)).toBe(screen.getByRole('combobox'));
  });
});
