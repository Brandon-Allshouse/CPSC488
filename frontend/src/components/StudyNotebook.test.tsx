// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchStudyNotes, saveStudyNote } from '../api/studyNotes';
import StudyNotebook from './StudyNotebook';

vi.mock('../api/studyNotes', () => ({ fetchStudyNotes: vi.fn(), saveStudyNote: vi.fn() }));

const videos = [
  { youtubeId: 'video000001', title: 'TCP handshake', creator: 'Network lessons' },
  { youtubeId: 'video000002', title: 'Matrix multiplication', creator: 'Computing lab' },
];
const savedNote = { youtubeId: 'video000001', note: 'SYN, SYN/ACK, ACK', updatedAt: '2026-10-22T00:00:00Z' };
function setup() {
  const props = { videos, removing: false, onOpenVideo: vi.fn(), onRemoveVideo: vi.fn().mockResolvedValue(undefined), onSessionExpired: vi.fn(), onDirtyChange: vi.fn() };
  return { ...render(<StudyNotebook {...props} />), props };
}
async function openEditor(title = 'TCP handshake') {
  const edit = screen.getByRole<HTMLButtonElement>('button', { name: 'Edit note for ' + title });
  await waitFor(() => expect(edit.disabled).toBe(false));
  fireEvent.click(edit);
  return screen.getByRole<HTMLTextAreaElement>('textbox', { name: 'Personal study notes' });
}

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(fetchStudyNotes).mockResolvedValue([savedNote]);
  vi.mocked(saveStudyNote).mockImplementation(async (youtubeId, note) => ({ youtubeId, note, updatedAt: savedNote.updatedAt }));
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers(); });

describe('StudyNotebook', () => {
  it('loads saved notes into a labeled editor and saves changes with Ctrl+Enter', async () => {
    const { props } = setup();
    const textarea = await openEditor();
    expect(textarea.value).toBe(savedNote.note);
    expect(textarea.maxLength).toBe(2000);
    fireEvent.change(textarea, { target: { value: 'TCP uses a three-way handshake.' } });
    await waitFor(() => expect(props.onDirtyChange).toHaveBeenLastCalledWith(true));
    fireEvent.keyDown(textarea, { key: 'Enter', ctrlKey: true });
    expect(await screen.findByText('Note saved to your account.')).toBeTruthy();
    expect(saveStudyNote).toHaveBeenCalledWith('video000001', 'TCP uses a three-way handshake.');
    await waitFor(() => expect(props.onDirtyChange).toHaveBeenLastCalledWith(false));
  });

  it('searches saved notes and combines the search with note filters', async () => {
    setup();
    const filter = screen.getByRole<HTMLSelectElement>('combobox', { name: 'Filter notebook by notes' });
    await waitFor(() => expect(filter.disabled).toBe(false));
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search notebook' }), { target: { value: 'SYN/ACK' } });
    expect(screen.getByRole('button', { name: 'TCP handshake' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Matrix multiplication' })).toBeNull();
    fireEvent.change(filter, { target: { value: 'without-notes' } });
    expect(screen.getByText('No matching study videos. Try another search or filter.')).toBeTruthy();
  });

  it('retains the draft after a failed save and lets the user retry', async () => {
    vi.mocked(saveStudyNote).mockRejectedValueOnce(new Error('Please try again.'));
    setup();
    const textarea = await openEditor();
    fireEvent.change(textarea, { target: { value: 'Keep this draft.' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save note' }));
    expect((await screen.findByRole('alert')).textContent).toBe('Please try again.');
    expect(textarea.value).toBe('Keep this draft.');
    fireEvent.click(screen.getByRole('button', { name: 'Save note' }));
    expect(await screen.findByText('Note saved to your account.')).toBeTruthy();
  });

  it('keeps a draft when the user cancels closing the editor', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    setup();
    const textarea = await openEditor();
    fireEvent.change(textarea, { target: { value: 'Unfinished revision notes.' } });
    fireEvent.click(screen.getByRole('button', { name: 'Close note editor' }));
    expect(confirm).toHaveBeenCalled();
    expect(textarea.value).toBe('Unfinished revision notes.');
    confirm.mockReturnValue(true);
    fireEvent.click(screen.getByRole('button', { name: 'Close note editor' }));
    expect(screen.queryByRole('textbox', { name: 'Personal study notes' })).toBeNull();
  });

  it('clears the saved note while keeping the video in the notebook', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    setup();
    const textarea = await openEditor();
    fireEvent.click(screen.getByRole('button', { name: 'Clear note' }));
    expect(await screen.findByText('Note cleared. The video is still in your notebook.')).toBeTruthy();
    expect(saveStudyNote).toHaveBeenCalledWith('video000001', '');
    expect(textarea.value).toBe('');
    expect(screen.getByRole('button', { name: 'TCP handshake' })).toBeTruthy();
  });

  it('asks before removing a video that has a saved note', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const { props } = setup();
    const remove = screen.getByRole<HTMLButtonElement>('button', { name: 'Remove TCP handshake from Study notebook' });
    await waitFor(() => expect(remove.disabled).toBe(false));
    fireEvent.click(remove);
    expect(props.onRemoveVideo).not.toHaveBeenCalled();
    confirm.mockReturnValue(true);
    fireEvent.click(remove);
    await waitFor(() => expect(props.onRemoveVideo).toHaveBeenCalledWith('video000001'));
  });

  it('shows a note-loading error and can retry without losing the video list', async () => {
    vi.mocked(fetchStudyNotes).mockRejectedValueOnce(new Error('Notes temporarily unavailable.'));
    setup();
    expect((await screen.findByRole('alert')).textContent).toContain('Notes temporarily unavailable.');
    expect(screen.getByRole('button', { name: 'TCP handshake' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Retry notes' }));
    const textarea = await openEditor();
    expect(textarea.value).toBe(savedNote.note);
  });

  it('renders note markup as text inside the editor', async () => {
    vi.mocked(fetchStudyNotes).mockResolvedValue([{ ...savedNote, note: '<img src=x onerror=alert(1)>' }]);
    const { container } = setup();
    expect((await openEditor()).value).toBe('<img src=x onerror=alert(1)>');
    expect(container.querySelector('img')).toBeNull();
  });

  it('blocks duplicate save requests while a save is pending', async () => {
    let finish!: (value: typeof savedNote) => void;
    vi.mocked(saveStudyNote).mockImplementationOnce(() => new Promise((resolve) => { finish = resolve; }));
    setup();
    const textarea = await openEditor();
    fireEvent.change(textarea, { target: { value: 'New note' } });
    fireEvent.keyDown(textarea, { key: 'Enter', ctrlKey: true });
    fireEvent.keyDown(textarea, { key: 'Enter', ctrlKey: true });
    expect(saveStudyNote).toHaveBeenCalledTimes(1);
    expect(textarea.disabled).toBe(true);
    await act(async () => finish({ ...savedNote, note: 'New note' }));
    expect(textarea.disabled).toBe(false);
  });

  it('downloads saved notes through a temporary text-file link', async () => {
    const createObjectURL = vi.fn(() => 'blob:study-notes');
    const revokeObjectURL = vi.fn();
    vi.stubGlobal('URL', { createObjectURL, revokeObjectURL });
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    setup();
    const download = screen.getByRole<HTMLButtonElement>('button', { name: 'Download notes' });
    await waitFor(() => expect(download.disabled).toBe(false));
    vi.useFakeTimers({ toFake: ['setTimeout'] });
    fireEvent.click(download);
    expect(createObjectURL).toHaveBeenCalledWith(expect.any(Blob));
    expect(click).toHaveBeenCalledTimes(1);
    vi.runOnlyPendingTimers();
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:study-notes');
  });
});
