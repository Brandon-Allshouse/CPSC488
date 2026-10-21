// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import AdminPage from './AdminPage';

afterEach(() => {
  cleanup();
});

describe('AdminPage', () => {
  it('blacklists and un-blacklists a creator, and logs both actions', () => {
    render(<AdminPage adminName="admin1" onBack={vi.fn()} />);

    fireEvent.click(screen.getByRole('tab', { name: /Creators/ }));
    fireEvent.change(screen.getByLabelText('YouTube channel name or ID'), { target: { value: 'Clickbait Channel' } });
    fireEvent.change(screen.getByLabelText('Reason (optional)'), { target: { value: 'not educational' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add to blacklist' }));
    expect(screen.getByText('Clickbait Channel')).toBeTruthy();
    expect(screen.getByRole('tab', { name: /Creators/ }).textContent).toContain('(1)');

    fireEvent.click(screen.getByRole('button', { name: 'Remove' }));
    expect(screen.getByText('Nothing blacklisted yet.')).toBeTruthy();

    fireEvent.click(screen.getByRole('tab', { name: 'Activity log' }));
    const log = screen.getByRole('region', { name: 'Activity log' }).textContent!;
    expect(log).toContain('admin1 blacklisted creator "Clickbait Channel" (reason: not educational)');
    expect(log).toContain('admin1 removed creator "Clickbait Channel" from the blacklist');
  });

  it('bans users and blacklists videos, and goes back to the feed', () => {
    const onBack = vi.fn();
    render(<AdminPage adminName="admin1" onBack={onBack} />);

    fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'spammer' } });
    fireEvent.click(screen.getByRole('button', { name: 'Ban user' }));
    expect(screen.getByText('spammer')).toBeTruthy();

    fireEvent.click(screen.getByRole('tab', { name: /Videos/ }));
    expect(screen.getByRole('button', { name: 'Add to blacklist' }).hasAttribute('disabled')).toBe(true);
    fireEvent.change(screen.getByLabelText('YouTube video ID or link'), { target: { value: 'dQw4w9WgXcQ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add to blacklist' }));
    expect(screen.getByText('dQw4w9WgXcQ')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Back to feed' }));
    expect(onBack).toHaveBeenCalled();
  });
});
