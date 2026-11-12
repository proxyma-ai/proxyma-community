import { Download, RefreshCw } from 'lucide-react'
import { useStore } from 'zustand'
import { Button, Caption, FadeSwap, Popover, Presence, TEXT, Tooltip, iconButton } from '@/components/ui/kit'
import { useWideLayout } from '@/hooks/useWideLayout'
import { useTranslation } from '@/i18n'
import { useSettingsModalStore } from '@/store/settingsModalStore'
import { useUpdateNoticeStore } from '@/store/updateNoticeStore'
import { useApplyUpdate } from '@/features/settings/hooks/useApplyUpdate'
import { mainChatInstance } from '@/features/chat/instance/chatInstance'
import { useActiveConversations } from '@/features/chat/hooks/useActiveConversations'
import { useEditionWorkingAgents } from '@edition'
import { cn } from '@/utils/cn'
import { useLiveUpdateStatus } from '../hooks/useLiveUpdateStatus'
import { updateNotice } from '../utils/updateNotice'

// In the window's drag strip, so every control here opts out of dragging.
const NO_DRAG = '[-webkit-app-region:no-drag]'

// THE CONTROL WITH ITS WORDS: the accent's ink and a hairline of it - noticed, never a fill (a teal
// fill is the primary button's alone). The quieter "Update available" is caption ink with no line.
function pill(ready: boolean): string {
  return cn(
    'inline-flex items-center gap-2 h-7 px-2.5 rounded-md text-xs font-medium whitespace-nowrap',
    'transition-colors duration-fast focus-ring', NO_DRAG,
    ready
      ? 'border border-brand-accent/60 text-brand-legible mouse:hover:bg-lift/60'
      : 'text-ink-caption mouse:hover:text-ink-primary',
  )
}

/**
 * "RESTART TO UPDATE" ON THE DESKTOP WINDOW'S TITLE ROW, left of its buttons (the maintainer,
 * 2026-11-04). Which variant, if any, is updateNotice.ts's decision; this draws it.
 *
 * A staged update opens a small popover - the version, Restart now and Later. Restart now is the
 * Updates page's own flow (useApplyUpdate) and its restart screen; Later hides the control for this
 * version until the app next starts. An update found and not downloaded (auto-update off) is a
 * quieter link to Settings > Updates. A narrow window folds either to its icon, the tooltip naming
 * the version.
 */
export function UpdateNotice({ inWindow }: { inWindow: boolean }) {
  const { t } = useTranslation()
  const wide = useWideLayout()
  const update = useLiveUpdateStatus(inWindow)
  const dismissedVersion = useUpdateNoticeStore(s => s.dismissedVersion)
  const notice = updateNotice({ inWindow, update, dismissedVersion, wide })
  return (
    <Presence show={notice.kind !== 'none'} collapse={false}>
      {notice.kind !== 'none' && (
        <FadeSwap swapKey={`${notice.kind}-${notice.compact}`}>
          {notice.kind === 'ready'
            ? <ReadyControl version={notice.version} compact={notice.compact} />
            : (
              <Tooltip label={t('Proxyma {version} is available', { version: notice.version })} side="bottom">
                <button
                  type="button"
                  onClick={() => useSettingsModalStore.getState().open('Updates')}
                  aria-label={notice.compact ? t('Update available') : undefined}
                  className={notice.compact ? cn(iconButton('md'), NO_DRAG) : pill(false)}
                >
                  <Download size={14} aria-hidden="true" />
                  {!notice.compact && t('Update available')}
                </button>
              </Tooltip>
            )}
        </FadeSwap>
      )}
    </Presence>
  )
}

function ReadyControl({ version, compact }: { version: string; compact: boolean }) {
  const { t } = useTranslation()
  const dismiss = useUpdateNoticeStore(s => s.dismiss)
  const apply = useApplyUpdate()
  return (
    <Popover
      role="dialog"
      placement="bottom-end"
      // Wide enough for both buttons on one line in a longer language (the maintainer, 2026-11-10: "Restart Proxyma now" in
      // Vietnamese ran past its button at w-72, then "make the modal wider and place the button in the same line").
      widthClass="w-96"
      ariaLabel={t('Restart Proxyma to update')}
      trigger={({ open, toggle, ref }) => (
        <Tooltip label={t('Proxyma {version} is ready to install', { version })} side="bottom">
          <button
            ref={ref}
            type="button"
            aria-haspopup="dialog"
            aria-expanded={open}
            aria-label={compact ? t('Restart Proxyma to update') : undefined}
            onClick={toggle}
            className={compact ? cn(iconButton('md', null), 'text-brand-legible', NO_DRAG) : pill(true)}
          >
            <RefreshCw size={14} aria-hidden="true" />
            {!compact && t('Restart Proxyma to update')}
          </button>
        </Tooltip>
      )}
    >
      {close => (
        <div className="p-4 flex flex-col gap-3">
          <div>
            <p className={TEXT.label}>{t('Proxyma {version} is ready.', { version })}</p>
            <Caption className="mt-1">{t('Otherwise it installs the next time Proxyma starts.')}</Caption>
          </div>
          <WorkInProgress />
          <div className="flex justify-end gap-2 [&>*]:shrink-0">
            <Button variant="secondary" size="sm" onClick={() => { dismiss(version); close() }}>
              {t('Later')}
            </Button>
            <Button size="sm" autoFocus disabled={apply.isPending} onClick={() => { close(); apply.mutate() }}>
              {t('Restart Proxyma now')}
            </Button>
          </div>
        </div>
      )}
    </Popover>
  )
}

/**
 * One line, only while something is at work: a chat turn (this page's, or any conversation's the
 * server reports running - deployed agents' included), or an edition page's agents. What a restart
 * does to it, and no more: a running turn is cut short (ChatRestartIT), and a scheduled run is
 * recorded as interrupted. Mounted only while the popover is open, so it asks nothing otherwise.
 */
function WorkInProgress() {
  const { t } = useTranslation()
  const streaming = useStore(mainChatInstance().store, s => s.streaming)
  const active = useActiveConversations()
  const edition = useEditionWorkingAgents()
  const working = streaming || active.length > 0 || edition.length > 0
  return (
    <Presence show={working}>
      <Caption tone="warning">{t('Work still in progress stops when Proxyma restarts.')}</Caption>
    </Presence>
  )
}
