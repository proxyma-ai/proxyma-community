/**
 * The one field recipe, shared by Input, Textarea, PasswordInput, SecretInput, SearchInput, Select,
 * Combobox and DateTimePicker. A field's NAME is the type scale's `TEXT.label` (typeScale.ts) - the
 * `FIELD_LABEL` copy of it and the label-derived `fieldId` went on 2026-10-14 (ids are `useId()`).
 *
 * This constant exists because these five had already drifted apart inside a single
 * application. In proxyma-app, Input rendered `rounded-md font-grotesk bg-canvas
 * focus:border-brand-solid disabled:opacity-40` while Select rendered `rounded-input
 * font-ui bg-transparent focus:border-brand-accent disabled:opacity-50` - a different
 * radius source, a different fill, a different focus colour and a different disabled
 * weight. Side by side in one form they read as controls from two different products, and
 * nothing in either file said they were meant to match.
 *
 * The rules, so a sixth control can be added without re-deriving them:
 *
 * - `bg-canvas`, not `bg-surface`. A field is a HOLE in the surface, not another surface.
 *   This is the one rule the style guide states outright.
 * - `rounded-md` literal, not `rounded-input`. The token indirection existed for
 *   deploy-time theming that no longer exists, and --radius-input's own fallback was
 *   --radius-control, a full pill - so an unset token silently turned text boxes into
 *   lozenges.
 * - Focus moves the BORDER, not a ring. Reserve the ring for `focus-ring`, which is for
 *   controls that have no border of their own to move.
 * - `focus:border-brand-solid`, not brand-accent. Same reason as Button's focus ring:
 *   brand-accent is the decorative step and reads as a pale wash in dark mode.
 * - text-xs and py-1.5, matching Button's `sm`. A form row has to line up with the button
 *   that submits it.
 */
export const FIELD_BASE =
  'block w-full py-1.5 rounded-md border text-xs font-ui text-ink-primary bg-canvas ' +
  'placeholder:text-ink-disabled outline-none transition-all duration-fast focus-border ' +
  'disabled:opacity-40 disabled:cursor-not-allowed ' +
  // Fades in where it mounts: most fields arrive in place of a FieldSkeleton when their value
  // loads. `animate-fade-in` lets go when it ends, so `disabled:opacity-40` still applies.
  'animate-fade-in'

/** Border colour is the only thing an error state changes. */
export const fieldBorder = (error?: string) =>
  error ? 'border-danger focus:border-danger' : 'border-line focus:border-brand-solid'

/**
 * Both browser-drawn password affordances, suppressed. Edge draws its own reveal eye
 * (`::-ms-reveal`) and Chrome an autofill key button; either one sitting beside the kit's
 * own reveal toggle gives the field two of the same control, one of them unstyleable.
 */
export const FIELD_NO_NATIVE_PASSWORD_UI =
  '[&::-ms-reveal]:hidden [&::-webkit-credentials-auto-fill-button]:hidden'
