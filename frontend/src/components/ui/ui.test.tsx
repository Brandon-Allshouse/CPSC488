// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cn } from '@/lib/utils';
import { Alert } from './alert';
import { Badge } from './badge';
import { Button } from './button';
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from './card';
import { Input } from './input';
import { Label } from './label';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from './table';

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

describe('Card', () => {
  it('puts the title, description, content and footer in order and keeps extra classes', () => {
    const { container } = render(
      <Card className="extra">
        <CardHeader>
          <CardTitle>Question 1</CardTitle>
          <CardDescription>Pick one</CardDescription>
        </CardHeader>
        <CardContent>Body</CardContent>
        <CardFooter>Footer</CardFooter>
      </Card>,
    );
    expect(screen.getByRole('heading', { name: 'Question 1' }).tagName).toBe('H2');
    expect(container.firstElementChild!.classList.contains('extra')).toBe(true);
    expect(container.textContent).toBe('Question 1Pick oneBodyFooter');
  });
});

describe('Table', () => {
  it('builds a real table that screen readers can read, with text shown as text', () => {
    render(
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead scope="col">Title</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          <TableRow>
            <TableCell>{'<b>plain</b>'}</TableCell>
          </TableRow>
        </TableBody>
      </Table>,
    );
    expect(screen.getByRole('table')).toBeTruthy();
    expect(screen.getByRole('columnheader', { name: 'Title' })).toBeTruthy();
    expect(screen.getByRole('cell', { name: '<b>plain</b>' })).toBeTruthy();
  });
});
