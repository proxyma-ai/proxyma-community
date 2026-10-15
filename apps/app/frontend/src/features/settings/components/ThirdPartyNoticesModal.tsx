import { useQuery } from '@tanstack/react-query'
import { CodeBlock, DialogBody, DialogDescription, DialogHeader, FadeSwap, InlineNotice, Presence, Skeleton } from '@/components/ui/kit'
import { ModalShell } from '@/components/ui/ModalShell'
import { useTranslation } from '@/i18n'
import { noticesApi } from '../services/noticesApi'

/**
 * Settings > About > Open-source licenses: THIRD-PARTY-NOTICES.txt, verbatim.
 *
 * The End User License Agreement sends a reader here by name, so the title is fixed. The text is
 * the same file the installers put in the installation folder - joined at build time from the
 * Java, web and Python lists and the hand-written header (apps/app/third-party/) - and it is
 * shown untouched rather than parsed into rows, so nothing here can drift from the file.
 *
 * A fixed height with the text as the ONE region that scrolls (.claude/rules/frontend.md), in the
 * UI face rather than a code face: it is licenses and prose, not code. No footer - closing is the
 * only thing to do, and the backdrop and Escape are that.
 */
export function ThirdPartyNoticesModal({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation()
  const { data, isLoading, isError } = useQuery({
    queryKey: ['third-party-notices'],
    queryFn: noticesApi.get,
    // It changes only with the build, and the page is reloaded after every update.
    staleTime: Infinity,
    retry: false,
  })

  return (
    <ModalShell onClose={onClose} size="xl" fill ariaLabel={t('Open-source licenses')}>
      <DialogHeader title={t('Open-source licenses')} />
      <DialogBody className="flex-1 min-h-0 flex flex-col gap-3">
        <DialogDescription>
          {t('Every third-party component Proxyma contains or downloads, its license, and how to get the source code of the GPL and LGPL ones. The same text is THIRD-PARTY-NOTICES.txt in the installation folder.')}
        </DialogDescription>
        {/* The text fades in over its skeleton as it arrives. */}
        <FadeSwap swapKey={isLoading ? 'loading' : !!data} className="flex-1 min-h-0 flex flex-col">
          {isLoading ? <Skeleton className="h-40 w-full" /> : data ? <CodeBlock text={data} wrap font="ui" fill /> : null}
        </FadeSwap>
        <Presence show={!!isError}>
          <InlineNotice tone="danger" size="md">{t('Could not load the notices. Try again in a moment.')}</InlineNotice>
        </Presence>
        <Presence show={data === null}>
          <InlineNotice tone="warning" size="md">{t('This build does not include the open-source notices. A release build always does.')}</InlineNotice>
        </Presence>
      </DialogBody>
    </ModalShell>
  )
}
