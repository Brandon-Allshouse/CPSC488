// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import PageLayout from './PageLayout';

const testUser = { id: 1, email: 'test@sru.edu', username: 'testuser', createdAt: '2026-10-05T00:00:00Z', admin: false };
const navigation = {
  user: testUser,
  onBack: vi.fn(),
  onEditInterests: vi.fn(),
  onShowLikedVideos: vi.fn(),
  onShowLeaderboard: vi.fn(),
  onLogout: vi.fn(),
};

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('PageLayout', () => {
  it('shows the header, the title, the description and the page content', () => {
    const { container } = render(
      <PageLayout {...navigation} name="notes" title="Notes" description="Your study notes.">
        <p>Page content</p>
      </PageLayout>,
    );

    expect(screen.getByText('BrainFeed')).toBeTruthy();
    expect(screen.getByRole('heading', { level: 1, name: 'Notes' })).toBeTruthy();
    expect(screen.getByText('Your study notes.')).toBeTruthy();
    expect(screen.getByText('Page content')).toBeTruthy();
    // the id comes from the name, so each page can be found and styled on its own
    expect(container.querySelector('main')!.id).toBe('notes-page');
  });

  it('goes back to the feed', () => {
    render(
      <PageLayout {...navigation} name="notes" title="Notes">
        <p>Page content</p>
      </PageLayout>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Back to feed' }));
    expect(navigation.onBack).toHaveBeenCalledTimes(1);
  });

  it('shows extra buttons next to the title', () => {
    const onExport = vi.fn();
    render(
      <PageLayout
        {...navigation}
        name="notes"
        title="Notes"
        actions={
          <button type="button" onClick={onExport}>
            Export
          </button>
        }
      >
        <p>Page content</p>
      </PageLayout>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Export' }));
    expect(onExport).toHaveBeenCalledTimes(1);
  });

  it('passes the navigation on to the Profile menu', () => {
    render(
      <PageLayout {...navigation} name="notes" title="Notes">
        <p>Page content</p>
      </PageLayout>,
    );
    fireEvent.click(screen.getByText('Profile'));
    fireEvent.click(screen.getByRole('button', { name: 'Interests' }));
    expect(navigation.onEditInterests).toHaveBeenCalledTimes(1);
  });

  it('goes back to the feed from the BrainFeed logo too', () => {
    render(
      <PageLayout {...navigation} name="notes" title="Notes">
        <p>Page content</p>
      </PageLayout>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'BrainFeed, go to your feed' }));
    expect(navigation.onBack).toHaveBeenCalledTimes(1);
  });
});
