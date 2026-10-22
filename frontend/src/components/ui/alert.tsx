import type { ComponentProps } from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '@/lib/utils';

// shadcn/ui Alert: a boxed message. Pass role="alert" for errors and role="status" for successes,
// so screen readers announce them.
const alertVariants = cva('relative flex w-full items-start gap-3 rounded-lg border px-4 py-3 text-sm [&>svg]:mt-0.5 [&>svg]:size-4 [&>svg]:shrink-0', {
  variants: {
    variant: {
      default: 'bg-card text-card-foreground',
      destructive: 'border-destructive/40 bg-destructive/10 text-destructive',
      success: 'border-success/40 bg-success/10 text-success',
    },
  },
  defaultVariants: {
    variant: 'default',
  },
});

function Alert({ className, variant, ...props }: ComponentProps<'div'> & VariantProps<typeof alertVariants>) {
  return <div data-slot="alert" className={cn(alertVariants({ variant }), className)} {...props} />;
}

export { Alert };
