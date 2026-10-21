import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

/**
 * Joins class names and lets later Tailwind classes win over earlier ones, so a component can be
 * given extra classes: cn('px-4', 'px-2') is 'px-2'. Every shadcn/ui component uses this.
 */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}
