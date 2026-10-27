// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { Eye } from 'lucide-react';
import StatTile from './StatTile';

afterEach(cleanup);

describe('StatTile', () => {
  it('shows the label, the value and the hint', () => {
    render(<StatTile icon={<Eye />} label="Watched today" value="3" hint="from your last 25 videos" />);
    expect(screen.getByText('Watched today')).toBeTruthy();
    expect(screen.getByText('3')).toBeTruthy();
    expect(screen.getByText('from your last 25 videos')).toBeTruthy();
  });

  it('shows names with HTML in them as plain text', () => {
    const { container } = render(<StatTile icon={<Eye />} label="Top channel" value="<b>Sneaky</b>" />);
    expect(screen.getByText('<b>Sneaky</b>')).toBeTruthy();
    expect(container.querySelector('b')).toBeNull();
  });
});
