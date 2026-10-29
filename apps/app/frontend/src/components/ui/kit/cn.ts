/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/cn.ts
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import { clsx, type ClassValue } from 'clsx'
import { extendTailwindMerge } from 'tailwind-merge'

/**
 * twMerge, TAUGHT THE PRESET (2026-10-14). Out of the box it knows Tailwind's own scales only, so a
 * class from the kit's preset fell in no group: `cn('duration-fast', 'duration-base')` kept both,
 * as did `z-float`/`z-dialog`, a preset shadow and Tailwind's own, and `min-h-tap`/`min-h-0`, and CSS order
 * decided - the override `cn` exists to make. Each group below is a preset key
 * (tokens/tailwind-preset.ts); add one here when the preset gains a scale.
 */
const twMerge = extendTailwindMerge({
  extend: {
    classGroups: {
      'font-size': [{ text: ['2xs'] }],
      duration: [{ duration: ['fast', 'base', 'slow'] }],
      shadow: [{ shadow: ['card', 'float', 'well'] }],
      z: [{ z: ['raised', 'sticky', 'header', 'dropzone', 'dialog', 'toast', 'float', 'tooltip', 'alert', 'tour', 'status'] }],
      'min-h': [{ 'min-h': ['tap', 'tap-sm'] }],
      'min-w': [{ 'min-w': ['tap', 'tap-sm'] }],
      w: [{ w: ['tap', 'tap-sm'] }],
      h: [{ h: ['tap', 'tap-sm'] }],
    },
  },
})

/**
 * Class-name merge for every kit component.
 *
 * twMerge, not a bare join: the whole point of the trailing `className` prop on these
 * components is that a call site can OVERRIDE a base class (`<Button className="w-full">`,
 * `<Card className="rounded-none">`). A plain `.filter(Boolean).join(' ')` emits both
 * classes and lets CSS source order decide, which silently ignores the override about half
 * the time. proxyma-landing's own `cn()` was exactly that join, which is why overrides
 * behaved differently there than in the product.
 *
 * The kit deliberately ships its own copy rather than importing the host repo's `cn` -
 * the three Vite apps expose it at `@/utils/cn` and the landing site at `@/lib/utils`, and
 * a component that has to know which repo it is in is not a shared component.
 */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}
