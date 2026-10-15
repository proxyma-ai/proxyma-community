import { Sparkles } from 'lucide-react'
import { IncludedList, DialogBody, DialogDescription, DialogHeader, ExternalLink } from '@/components/ui/kit'
import type { WhatsNewEntry } from '../data/whatsNew'
import { ModalShell } from '@/components/ui/ModalShell'
import { useTranslation } from '@/i18n'

interface Props {
  version: string
  entry: WhatsNewEntry
  onClose: () => void
}

/**
 * Shown once, the first time somebody opens a version they have not run before.
 *
 * <p>Both editions. A server deployment's users are upgraded by their operator without being asked,
 * so they are MORE likely to wonder what changed than a desktop user who pressed the button
 * themselves - the case for showing it there is stronger, not weaker.
 *
 * <p>Dismissed by the backdrop, Escape or Enter, and never shown again for that
 * version - it is an interruption on the way to something else, so every exit has to be easy. See
 * whatsNewStore for why a fresh install never sees it at all.
 *
 * <p>The kit's dialog parts since the 2026-10 rebuild: a divided header with the glyph, the summary,
 * the highlights as the kit's check list (brand ink - each line is something that now works), and
 * the release-notes link under them. No footer: its one button closed the panel and nothing else.
 */
export function WhatsNewModal({ version, entry, onClose }: Props) {
  const { t } = useTranslation()
  return (
    <ModalShell
      onClose={onClose}
      ariaLabelledBy="whats-new-title"
      // Enter dismisses it too: closing is the only action, so there is nothing to get wrong.
      onSubmit={onClose}
      size="md"
    >
      <DialogHeader
        titleId="whats-new-title"
        icon={<Sparkles className="text-brand-legible" aria-hidden="true" />}
        title={t("What's new in {version}", { version })}
      />

      <DialogBody className="space-y-4">
        <DialogDescription>{t(entry.summary)}</DialogDescription>
        <IncludedList items={entry.highlights.map(line => t(line))} />
        {/* The full notes are on the website rather than in here, so this panel can stay three
            lines long. It opens in a new tab because dismissing the panel and losing the page you
            were on is not what "read more" should cost. In the body since the footer went: its
            one button was "Got it", which only closed the panel - and a dialog closes on its
            backdrop and Escape (and this one on Enter too). */}
        <ExternalLink href="https://proxyma.ai/docs/release-notes/">{t('All release notes')}</ExternalLink>
      </DialogBody>
    </ModalShell>
  )
}
