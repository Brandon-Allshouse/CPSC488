// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
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
});