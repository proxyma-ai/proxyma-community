/**
 * THE BUTTON'S CLASSES, apart from the component: a plain module, so a server component on the
 * landing site can call `buttonClass()` for a Next.js `Link` - a function exported from a
 * 'use client' module cannot be called on the server at all. `Button.tsx` is the component.
 */
import { cn } from './cn'
import { textLink } from './typeScale'

export type ButtonVariant = 'primary' | 'featured' | 'secondary' | 'hyperlink' | 'quiet' | 'danger' | 'danger-quiet'
export type ButtonSize = 'sm' | 'md' | 'lg'

const TEXT_VARIANTS: ButtonVariant[] = ['hyperlink', 'quiet', 'danger-quiet']

/**
 * The classes of a button of this variant and size - shared by `Button`, `ButtonLink` and any
 * router link that has to look like one (the kit cannot import a router, so a react-router or
 * Next.js `Link` takes `className={buttonClass(...)}`).
 */
export function buttonClass(variant: ButtonVariant = 'primary', size: ButtonSize = 'sm', className?: string): string {
  // The three TEXT variants are links in a button's clothing: no padding, no floor width, no fill.
  // They are the kit's one text-link recipe (`textLink` in typeScale.ts), so a `hyperlink` Button
  // and a hand-written anchor with `textLink()` are the same thing.
  if (TEXT_VARIANTS.includes(variant)) {
    return cn(
      textLink(variant === 'hyperlink' ? 'brand' : variant === 'quiet' ? 'quiet' : 'danger'),
      size === 'sm' && 'text-xs',
      size === 'md' && 'text-sm',
      size === 'lg' && 'text-base',
      'whitespace-nowrap leading-none aria-disabled:opacity-40 aria-disabled:cursor-not-allowed aria-disabled:no-underline',
      className,
    )
  }
  return cn(
    'inline-flex items-center justify-center font-bold font-ui rounded-md no-underline',
    'transition-all duration-fast cursor-pointer whitespace-nowrap leading-none',
    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-solid focus-visible:ring-offset-2 focus-visible:ring-offset-canvas',
    'disabled:opacity-40 disabled:cursor-not-allowed disabled:pointer-events-none',
    'aria-disabled:opacity-40 aria-disabled:cursor-not-allowed',
    'active:scale-[0.98] min-w-[100px]',
    size === 'sm' && 'px-3 py-1.5 text-xs gap-1.5',
    size === 'md' && 'px-5 py-2.5 text-sm gap-2',
    size === 'lg' && 'px-6 py-3 text-base gap-2.5',
    // No lift-on-hover and no coloured drop shadow. A colored shadow under a solid button reads as
    // a halo in light mode, because it has nowhere to fade to. `text-brand-on-solid` rather than
    // `text-white` so the pairing stays correct if --brand-solid is ever given a light value.
    // The transparent border makes a filled button exactly as tall as the outlined one beside it;
    // the landing site wrote `border border-transparent` by hand in three places for that.
    variant === 'primary' && 'bg-brand-solid text-brand-on-solid border border-transparent mouse:hover:opacity-90',
    // A pale chip rather than a second solid fill. brand-pale/brand-legible are a matched pair in
    // both modes; --brand-accent cannot back a label (it flips which foreground it needs per mode).
    variant === 'featured' && 'bg-brand-pale text-brand-legible border border-brand-solid/30 mouse:hover:opacity-90',
    variant === 'secondary' && 'bg-transparent text-ink-primary border border-line mouse:hover:border-brand-solid',
    // The SOLID danger fill (`--danger-solid`), not `bg-danger`: that token is brightened for dark
    // mode precisely because it backs error TEXT on the canvas, and white on it clears only 2.4:1 as
    // a fill. The solid step holds 4.8:1 in every appearance.
    variant === 'danger' && 'bg-danger-solid text-on-status border border-transparent mouse:hover:opacity-90',
    className,
  )
}
