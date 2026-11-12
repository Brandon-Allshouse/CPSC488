// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchVideoStudy, type VideoStudy } from '../api/study';
import StudyBox, { STUDY_GIVE_UP_MS, STUDY_POLL_MS } from './StudyBox';

vi.mock('../api/study', () => ({ fetchVideoStudy: vi.fn() }));

const ready: VideoStudy = {
  status: 'ready',
  summary: 'The video explains how plants turn sunlight into food.',
  questions: [
    {
      question: 'What do plants use to make food?',
      options: ['Sunlight', 'Sand', 'Moonlight', 'Salt'],
      answerIndex: 0,
      explanation: 'Photosynthesis uses sunlight.',
    },
    {
      question: 'Where does photosynthesis happen?',
      options: ['Roots', 'Leaves', 'Flowers', 'Seeds'],
      answerIndex: 1,
      explanation: 'Leaves have chloroplasts.',
    },
  ],
};

beforeEach(() => vi.resetAllMocks());
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('StudyBox', () => {
  it('waits for a click before asking the backend', () => {
    render(<StudyBox youtubeId="dQw4w9WgXcQ" />);
    expect(screen.getByText('Video Summary')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Summarize and quiz me' })).toBeTruthy();
    expect(fetchVideoStudy).not.toHaveBeenCalled();
  });

  it('starts by itself when autoStart turns on', async () => {
    vi.mocked(fetchVideoStudy).mockResolvedValue(ready);
    const { rerender } = render(<StudyBox youtubeId="dQw4w9WgXcQ" />);
    rerender(<StudyBox youtubeId="dQw4w9WgXcQ" autoStart />);
    expect(await screen.findByText(ready.summary)).toBeTruthy();
    expect(fetchVideoStudy).toHaveBeenCalledWith('dQw4w9WgXcQ');
  });

  it('shows the summary, both questions and the AI note', async () => {
    vi.mocked(fetchVideoStudy).mockResolvedValue(ready);
    render(<StudyBox youtubeId="dQw4w9WgXcQ" />);
    fireEvent.click(screen.getByRole('button', { name: 'Summarize and quiz me' }));
    expect(await screen.findByText(ready.summary)).toBeTruthy();
    expect(screen.getByText('1. What do plants use to make food?')).toBeTruthy();
    expect(screen.getByText('2. Where does photosynthesis happen?')).toBeTruthy();
    expect(screen.getByText(/Written by AI/)).toBeTruthy();
  });

  it('marks a right answer and shows the explanation', async () => {
    vi.mocked(fetchVideoStudy).mockResolvedValue(ready);
    render(<StudyBox youtubeId="dQw4w9WgXcQ" autoStart />);
    fireEvent.click(await screen.findByRole('button', { name: 'Sunlight' }));
    expect(screen.getByText('Correct.')).toBeTruthy();
    expect(screen.getByText('Photosynthesis uses sunlight.')).toBeTruthy();
    // can't change your answer after picking
    expect((screen.getByRole('button', { name: 'Sand' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('marks a wrong answer, points out the right one and counts the score', async () => {
    vi.mocked(fetchVideoStudy).mockResolvedValue(ready);
    render(<StudyBox youtubeId="dQw4w9WgXcQ" autoStart />);
    fireEvent.click(await screen.findByRole('button', { name: 'Sand' }));
    expect(screen.getByText('Not quite.')).toBeTruthy();
    expect(screen.getByLabelText('Correct answer')).toBeTruthy();
    expect(screen.queryByText(/You got/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Leaves' }));
    expect(screen.getByText('You got 1 of 2 right.')).toBeTruthy();
  });

  it('retake clears the answers', async () => {
    vi.mocked(fetchVideoStudy).mockResolvedValue(ready);
    render(<StudyBox youtubeId="dQw4w9WgXcQ" autoStart />);
    fireEvent.click(await screen.findByRole('button', { name: 'Sunlight' }));
    fireEvent.click(screen.getByRole('button', { name: 'Leaves' }));
    fireEvent.click(screen.getByRole('button', { name: 'Retake' }));
    expect(screen.queryByText(/You got/)).toBeNull();
    expect((screen.getByRole('button', { name: 'Sand' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('keeps checking while the summary is being written', async () => {
    vi.useFakeTimers();
    vi.mocked(fetchVideoStudy).mockResolvedValueOnce({ status: 'pending' }).mockResolvedValueOnce(ready);
    render(<StudyBox youtubeId="dQw4w9WgXcQ" autoStart />);
    await act(async () => {});
    expect(screen.getByText(/Writing a summary and quiz/)).toBeTruthy();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(STUDY_POLL_MS);
    });
    expect(screen.getByText(ready.summary)).toBeTruthy();
    expect(fetchVideoStudy).toHaveBeenCalledTimes(2);
  });

  it('gives up after a few minutes and offers to try again', async () => {
    vi.useFakeTimers();
    vi.mocked(fetchVideoStudy).mockResolvedValue({ status: 'pending' });
    render(<StudyBox youtubeId="dQw4w9WgXcQ" autoStart />);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(STUDY_GIVE_UP_MS + STUDY_POLL_MS);
    });
    expect(screen.getByText(/taking longer than usual/)).toBeTruthy();
    const calls = vi.mocked(fetchVideoStudy).mock.calls.length;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(STUDY_POLL_MS * 5);
    });
    expect(vi.mocked(fetchVideoStudy).mock.calls.length).toBe(calls);
    expect(screen.getByRole('button', { name: 'Try again' })).toBeTruthy();
  });

  it('stops checking when the card goes away', async () => {
    vi.useFakeTimers();
    vi.mocked(fetchVideoStudy).mockResolvedValue({ status: 'pending' });
    const { unmount } = render(<StudyBox youtubeId="dQw4w9WgXcQ" autoStart />);
    await act(async () => {});
    unmount();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(STUDY_POLL_MS * 5);
    });
    expect(fetchVideoStudy).toHaveBeenCalledTimes(1);
  });

  it.each([
    ['unavailable', /isn't running right now/],
    ['busy', /Lots of videos are waiting/],
  ] as const)('explains %s and lets you try again', async (status, message) => {
    vi.mocked(fetchVideoStudy).mockResolvedValueOnce({ status }).mockResolvedValueOnce(ready);
    render(<StudyBox youtubeId="dQw4w9WgXcQ" autoStart />);
    expect(await screen.findByText(message)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText(ready.summary)).toBeTruthy();
  });

  it('shows a plain message when the request fails', async () => {
    vi.mocked(fetchVideoStudy).mockRejectedValue(new Error('offline'));
    render(<StudyBox youtubeId="dQw4w9WgXcQ" autoStart />);
    expect(await screen.findByText(/Couldn't load the summary/)).toBeTruthy();
  });

  it('shows AI text as plain text, not HTML', async () => {
    const sneaky = '<img src=x onerror=alert(1)>';
    vi.mocked(fetchVideoStudy).mockResolvedValue({ ...ready, summary: sneaky });
    const { container } = render(<StudyBox youtubeId="dQw4w9WgXcQ" autoStart />);
    expect(await screen.findByText(sneaky)).toBeTruthy();
    expect(container.querySelector('img')).toBeNull();
  });

  it('never says coming soon', async () => {
    render(<StudyBox youtubeId="dQw4w9WgXcQ" />);
    await waitFor(() => expect(document.body.textContent).not.toMatch(/coming soon|on the way/i));
  });

  it('says when lots of videos are waiting, and can try again', async () => {
    vi.mocked(fetchVideoStudy).mockResolvedValueOnce({ status: 'busy' }).mockResolvedValueOnce(ready);
    render(<StudyBox youtubeId="dQw4w9WgXcQ" autoStart />);
    expect(await screen.findByText(/Lots of videos are waiting/)).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: /Try again/ }));
    expect(await screen.findByText(ready.summary)).toBeTruthy();
  });

  it("says when the summary writer isn't running", async () => {
    vi.mocked(fetchVideoStudy).mockResolvedValue({ status: 'unavailable' });
    render(<StudyBox youtubeId="dQw4w9WgXcQ" autoStart />);
    expect(await screen.findByText(/isn't running right now/)).toBeTruthy();
  });

  it('shows a loading message while the summary is being written', () => {
    vi.mocked(fetchVideoStudy).mockReturnValue(new Promise(() => {}));
    render(<StudyBox youtubeId="dQw4w9WgXcQ" />);
    fireEvent.click(screen.getByRole('button', { name: 'Summarize and quiz me' }));
    expect(screen.getByRole('status').textContent).toContain('Writing a summary and quiz');
  });
});
