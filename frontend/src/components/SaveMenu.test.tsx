// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Playlist } from '../api/playlists';
import SaveMenu from './SaveMenu';

const playlists: Playlist[] = [
  { id: 1, name: 'Watch later', watchLater: true, videos: [] },
  { id: 2, name: 'Exam prep', watchLater: false, videos: [{ youtubeId: 'video000001', title: 'T', creator: 'C' }] },
];

function renderMenu(overrides: Partial<Parameters<typeof SaveMenu>[0]> = {}) {
  const props = {
    youtubeId: 'video000001',
    idPrefix: 'test',
    playlists,
    onToggle: vi.fn().mockResolvedValue(undefined),
    onCreate: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  };
  render(<SaveMenu {...props} />);
  return props;
}

afterEach(() => {
  cleanup();
});

describe('SaveMenu', () => {
  it('shows as saved when the video is in any playlist', () => {
    renderMenu();
    expect(screen.getByRole('button', { name: 'Saved. Change playlists' }).getAttribute('aria-pressed')).toBe('true');
  });

  it('shows as not saved when the video is in no playlist', () => {
    renderMenu({ youtubeId: 'video000002' });
    expect(screen.getByRole('button', { name: 'Save video' }).getAttribute('aria-pressed')).toBe('false');
  });

  it('opens a menu with a checkbox per playlist, ticked where the video is saved', () => {
    renderMenu();
    fireEvent.click(screen.getByRole('button', { name: 'Saved. Change playlists' }));

    expect(screen.getByRole('dialog', { name: 'Save to playlist' })).toBeTruthy();
    expect((screen.getByRole('checkbox', { name: 'Watch later' }) as HTMLInputElement).checked).toBe(false);
    expect((screen.getByRole('checkbox', { name: 'Exam prep' }) as HTMLInputElement).checked).toBe(true);
  });

  it('adds to or removes from a playlist when a box is clicked', async () => {
    const props = renderMenu();
    fireEvent.click(screen.getByRole('button', { name: 'Saved. Change playlists' }));

    fireEvent.click(screen.getByRole('checkbox', { name: 'Watch later' }));
    await vi.waitFor(() => expect(props.onToggle).toHaveBeenLastCalledWith(1, true));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Exam prep' }));
    await vi.waitFor(() => expect(props.onToggle).toHaveBeenLastCalledWith(2, false));
  });

  it('makes a new playlist with the video in it', async () => {
    const props = renderMenu();
    fireEvent.click(screen.getByRole('button', { name: 'Saved. Change playlists' }));

    fireEvent.change(screen.getByRole('textbox', { name: 'New playlist name' }), { target: { value: ' Biology ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create playlist' }));
    await vi.waitFor(() => expect(props.onCreate).toHaveBeenCalledWith('Biology'));
  });

  it('shows the error when saving fails', async () => {
    renderMenu({ onToggle: vi.fn().mockRejectedValue(new Error('Playlist not found.')) });
    fireEvent.click(screen.getByRole('button', { name: 'Saved. Change playlists' }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Watch later' }));
    expect((await screen.findByRole('alert')).textContent).toBe('Playlist not found.');
  });

  it('closes on Escape', () => {
    renderMenu();
    fireEvent.click(screen.getByRole('button', { name: 'Saved. Change playlists' }));
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('saves to the notebook without touching playlists', async () => {
    const notebookToggle = vi.fn().mockResolvedValue(undefined);
    const props = renderMenu({ notebook: { saved: false, onToggle: notebookToggle } });
    fireEvent.click(screen.getByRole('button', { name: 'Saved. Change save locations' }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Study notebook' }));
    await waitFor(() => expect(screen.getByRole('status').textContent).toBe('Added to Study notebook.'));
    expect(notebookToggle).toHaveBeenCalledTimes(1);
    expect(props.onToggle).not.toHaveBeenCalled();
    expect(props.onCreate).not.toHaveBeenCalled();
  });

  it('supports the notebook on a card with no playlist controls', () => {
    renderMenu({ notebook: { saved: true, onToggle: vi.fn() }, playlists: undefined });
    fireEvent.click(screen.getByRole('button', { name: 'Saved. Change save locations' }));
    expect(screen.getByRole<HTMLInputElement>('checkbox', { name: 'Study notebook' }).checked).toBe(true);
    expect(screen.queryByRole('textbox', { name: 'New playlist name' })).toBeNull();
  });

  it('disables destinations during a pending request and sends only one save', async () => {
    let finish!: () => void;
    const notebookToggle = vi.fn(() => new Promise<void>((resolve) => { finish = resolve; }));
    renderMenu({ notebook: { saved: false, onToggle: notebookToggle } });
    fireEvent.click(screen.getByRole('button', { name: 'Saved. Change save locations' }));
    const notebook = screen.getByRole<HTMLInputElement>('checkbox', { name: 'Study notebook' });
    fireEvent.click(notebook);
    fireEvent.click(notebook);
    expect(notebook.disabled).toBe(true);
    expect(screen.getByRole<HTMLInputElement>('checkbox', { name: 'Watch later' }).disabled).toBe(true);
    expect(screen.getByRole('status').textContent).toBe('Saving changes...');
    expect(notebookToggle).toHaveBeenCalledTimes(1);
    await act(async () => { finish(); });
    expect(notebook.disabled).toBe(false);
  });

  it('keeps the saved state unchanged and lets the user retry after a failed notebook save', async () => {
    const notebookToggle = vi.fn()
      .mockRejectedValueOnce(new Error('Please try again.'))
      .mockResolvedValue(undefined);
    const props = renderMenu({ notebook: { saved: false, onToggle: notebookToggle } });
    fireEvent.click(screen.getByRole('button', { name: 'Saved. Change save locations' }));
    const notebook = screen.getByRole<HTMLInputElement>('checkbox', { name: 'Study notebook' });
    fireEvent.click(notebook);
    expect((await screen.findByRole('alert')).textContent).toBe('Please try again.');
    expect(notebook.checked).toBe(false);
    expect(notebook.disabled).toBe(false);
    fireEvent.click(notebook);
    await waitFor(() => expect(screen.getByRole('status').textContent).toBe('Added to Study notebook.'));
    expect(notebookToggle).toHaveBeenCalledTimes(2);
    expect(screen.queryByRole('alert')).toBeNull();
    expect(props.onToggle).not.toHaveBeenCalled();
  });

  it('focuses a destination on open and returns focus to the bookmark on Escape', () => {
    renderMenu({ notebook: { saved: false, onToggle: vi.fn() } });
    const trigger = screen.getByRole('button', { name: 'Saved. Change save locations' });
    fireEvent.click(trigger);
    expect(document.activeElement).toBe(screen.getByRole('checkbox', { name: 'Study notebook' }));
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.activeElement).toBe(trigger);
  });

  it('closes when the user clicks outside the save menu', () => {
    renderMenu();
    fireEvent.click(screen.getByRole('button', { name: 'Saved. Change playlists' }));
    fireEvent.mouseDown(document.body);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

});
