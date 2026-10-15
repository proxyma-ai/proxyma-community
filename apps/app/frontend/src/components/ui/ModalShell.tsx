import { useCallback, useState } from 'react'
import { DialogShell, type DialogShellProps, type DiscardLabels } from '@/components/ui/kit'
import { sameValue } from './formSnapshot'
import { useTranslation } from '@/i18n'

/**
 * EVERY DIALOG IN THIS APP opens through this: the kit's `DialogShell` - the one layer stack,
 * focus trap, drag stop and "Discard unsaved changes?" guard - with the guard's words in the
 * reader's language. The behaviour lives in the kit since the 2026-10 rebuild, so the consoles'
 * dialogs have it too; what stays here is `t()`, which a kit component cannot call.
 *
 * Build the inside from the kit's parts: `size` (or `panelClassName` for a panel of its own),
 * then `DialogHeader`, `DialogBody`, `DialogFooter` - or `ConfirmDialog` / `DialogNotice` whole.
 */
export type ModalShellProps = Omit<DialogShellProps, 'discardLabels'>

export function ModalShell(props: ModalShellProps) {
  return <DialogShell {...props} discardLabels={useDiscardLabels()} />
}

/** The guard's words in the reader's language, for a kit part that opens its own DialogShell
 *  (the Settings window). */
export function useDiscardLabels(): DiscardLabels {
  const { t } = useTranslation()
  return {
    title: t('Discard unsaved changes?'),
    body: t('Your changes have not been saved.'),
    keep: t('Keep editing'),
    discard: t('Discard'),
  }
}

/**
 * Whether `current` differs from the value it had when the dialog opened.
 *
 * Compares VALUES, so an edit that is undone reads as clean again. For a form filled from data
 * that arrives after the first render, call `rebase` with the values the form was just filled
 * with - otherwise the fill itself reads as an edit.
 */
export function useFormDirty<T>(current: T): { dirty: boolean; rebase: (baseline: T) => void } {
  const [baseline, setBaseline] = useState<T>(current)
  const rebase = useCallback((next: T) => setBaseline(() => next), [])
  return { dirty: !sameValue(baseline, current), rebase }
}
