import { Caption, FadeSwap, Presence } from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import type { SaveWord } from './autosave'

/**
 * "Saving..." while a change is on its way and "Saved." for a moment after (autosave.ts saveWord) - the
 * Settings window's word beside its tab's title, and every dialog's that saves field by field, beside
 * the dialog's title (DialogHeader's `actions`). In the reader's eye line without a bar of its own;
 * fades in and out (Presence), and across (FadeSwap). A refusal is said where it happened, not here.
 */
export function SaveStatus({ word }: { word: SaveWord }) {
  const { t } = useTranslation()
  return (
    <Presence show={!!word} collapse={false}>
      <FadeSwap swapKey={word}>
        <Caption as="span" role="status" tone={word === 'saved' ? 'brand' : 'default'}>
          {word === 'saving' ? t('Saving...') : t('Saved.')}
        </Caption>
      </FadeSwap>
    </Presence>
  )
}
