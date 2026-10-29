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
 * - `bg-field` (since 2026-10-29, the depth review). White in Light, so a field on a white card
 *   is drawn by its line and never reads as a gray slab; a WELL in Dark, sunk into the card or
 *   dialog it sits on. It was `bg-canvas` - "a hole in the surface" - while the canvas was white,
 *   and would have turned every field gray once the page did.
 * - `rounded-md` literal, not `rounded-input`. The token indirection existed for
 *   deploy-time theming that no longer exists, and --radius-input's own fallback was
 *   --radius-control, a full pill - so an unset token silently turned text boxes into
 *   lozenges.
 * - Focus moves the BORDER, not a ring. Reserve the ring for `focus-ring`, which is for
 *   controls that have no border of their own to move.
 * - `focus:border-brand-solid`, not brand-accent. Same reason as Button's focus ring:
 *   brand-accent is the decorative step and reads as a pale wash in dark mode.
 * - 14/20 text and a 36px MINIMUM height (44px on glass), matching Button's default `md`. A form
 *   row has to line up with the button that submits it. A minimum, not a height, so a textarea
 *   grows; an input centers its line in it. (It was 13px in a 32px box until 2026-10-28.) The
 *   6px `py-1.5` is off the 4-point rhythm on purpose: the minimum, not the padding, sets an
 *   input's height, and 8px would make it 38px; the padding is only a textarea's line inset.
 */
export const FIELD_BASE =
  // 16px ON GLASS (touch:text-base) for every field, the Select's button trigger included: the
  // primitives' touch rule reaches input, textarea and select only, so a Select read 14px beside a
  // Combobox's 16px in one form on a phone (the UI review, 2026-10-28).
  'block w-full min-h-9 touch:min-h-tap py-1.5 rounded-md border text-sm touch:text-base font-ui text-ink-primary bg-field ' +
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

/** What `fieldWords` decides: where the hint goes, and the ids that tie the words to the control. */
export interface FieldWords {
  /** The hint sits under the label, above the control (`FieldHint`); else under it (`FieldNote`). */
  hintAbove: boolean
  /** Anything is drawn over the control: the hint, or `Field`'s `description` (always above). */
  above: boolean
  /** The id of the words over the control. */
  hintId: string
  /** The id of the line under the control - the error, or a label-less field's hint. */
  noteId: string
  /** For the control's `aria-describedby`: every line of words that shows, or nothing. */
  describedBy?: string
}

/**
 * WHERE A FIELD'S WORDS GO, since 2026-10-29 (the maintainer's review: "Description should be
 * placed below the section title"). A field with a label takes its hint UNDER THE LABEL and above
 * the control; its error stays under the control, which is what it points at. A field with no label
 * keeps its hint under the control - a caption over a bare control reads as its title. The control
 * is described by both lines when both show. Every kit field (`Input`, `Textarea`, `PasswordInput`,
 * `CopyField`, `Field`) asks this rather than deciding again. `description` is `Field`'s: it opens
 * the field, label or not.
 */
export function fieldWords(id: string, { labelled, hint, error, description = false }: {
  labelled: boolean
  hint: boolean
  error: boolean
  description?: boolean
}): FieldWords {
  const hintAbove = labelled && hint
  const above = hintAbove || description
  const hintId = `${id}-hint`
  const noteId = `${id}-note`
  const below = error || (hint && !hintAbove)
  const ids = [above ? hintId : null, below ? noteId : null].filter((x): x is string => !!x)
  return { hintAbove, above, hintId, noteId, describedBy: ids.length ? ids.join(' ') : undefined }
}
