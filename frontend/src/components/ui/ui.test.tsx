// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cn } from '@/lib/utils';
import { Alert } from './alert';
import { Badge } from './badge';
import { Button } from './button';
import { Input } from './input';
import { Label } from './label';

afterEach(() => {
  cleanup();
});

describe('cn', () => {
  it('lets a later Tailwind class win over an earlier one', () => {
    expect(cn('px-4 py-2', 'px-2')).toBe('py-2 px-2');
  });

  it('skips false and empty values', () => {
    expect(cn('a', false, undefined, '', 'b')).toBe('a b');
  });
});

describe('Button', () => {
  it("is type=button by default, so it can't submit a form by accident", () => {
    const onSubmit = vi.fn((e: Event) => e.preventDefault());
    render(
      <form onSubmit={(e) => onSubmit(e.nativeEvent)}>
        <Button>Plain</Button>
        <Button type="submit">Send</Button>
      </form>,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Plain' }));
    expect(onSubmit).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Send' }));
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it('adds extra classes on top of its variant', () => {
    render(
      <Button variant="destructive" className="w-full">
        Delete
      </Button>,
    );
    const button = screen.getByRole('button', { name: 'Delete' });
    expect(button.className).toContain('bg-destructive');
    expect(button.className).toContain('w-full');
  });

  it("doesn't fire clicks while disabled", () => {
    const onClick = vi.fn();
    render(
      <Button disabled onClick={onClick}>
        Save
      </Button>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(onClick).not.toHaveBeenCalled();
  });
});

describe('Input and Label', () => {
  it('link up so the label names the input', () => {
    render(
      <>
        <Label htmlFor="email">Email</Label>
        <Input id="email" />
      </>,
    );
    const input = screen.getByLabelText('Email') as HTMLInputElement;
    fireEvent.change(input, { target: { value: 'test@sru.edu' } });
    expect(input.value).toBe('test@sru.edu');
  });
});

describe('Alert and Badge', () => {
  it('keep the role they are given, so screen readers announce errors', () => {
    render(
      <Alert variant="destructive" role="alert">
        Something broke.
      </Alert>,
    );
    expect(screen.getByRole('alert').textContent).toBe('Something broke.');
  });

  it('show text as plain text', () => {
    // badges show ban reasons typed by admins, which must never become HTML
    const { container } = render(<Badge>{'<b>Banned</b>'}</Badge>);
    expect(screen.getByText('<b>Banned</b>')).toBeTruthy();
    expect(container.querySelector('b')).toBeNull();
  });
});
