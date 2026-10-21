// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import AuthLayout from './AuthLayout';

afterEach(() => {
  cleanup();
});

describe('AuthLayout', () => {
  it('shows the logo, the title, the description and the content in one card', () => {
    const { container } = render(
      <AuthLayout name="login" title="Welcome back" description="Log in to continue.">
        <p>Form goes here</p>
      </AuthLayout>,
    );

    expect(screen.getByRole('img', { name: 'BrainFeed brain logo' })).toBeTruthy();
    expect(screen.getByRole('heading', { level: 1, name: 'Welcome back' })).toBeTruthy();
    expect(screen.getByText('Log in to continue.')).toBeTruthy();
    expect(screen.getByText('Form goes here')).toBeTruthy();
    expect(container.querySelector('main')!.id).toBe('login-page');
  });

  it('labels the card with its title for screen readers', () => {
    render(
      <AuthLayout name="interests" title="Pick your interests" description="Choose a few.">
        <p>Topics</p>
      </AuthLayout>,
    );
    expect(screen.getByRole('region', { name: 'Pick your interests' })).toBeTruthy();
  });
});
