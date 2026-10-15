import type { LucideIcon } from 'lucide-react'
import { Hand, Search, Zap } from 'lucide-react'
import { MenuGroup, MenuRow, MenuTrigger, Popover, Tooltip } from '@/components/ui/kit'
import { useChatStore } from '../instance/ChatInstanceContext'
import type { PermissionMode } from '../types/chat.types'
import { useTranslation } from '@/i18n'

const ICONS: Record<PermissionMode, LucideIcon> = { ASK: Hand, AUTO: Zap, READ_ONLY: Search }
const ORDER: PermissionMode[] = ['ASK', 'AUTO', 'READ_ONLY']

/**
 * Ask, Auto or Read-only - how much the agent may change without asking - beside the model in the
 * composer (the maintainer's request, 2026-09-28; hand, zap and search are their icons). It skips
 * or tightens the approval pause only: a connection's Write switch still decides what a change may
 * reach, in every mode.
 *
 * The kit's Popover, MenuTrigger and menu rows, opening UPWARD: the composer sits at the bottom of the page, and
 * a menu dropping below it would open off-screen. The Popover flips if there is no room above, and
 * marks Escape handled so a dialog beneath stays open (its own listeners did not).
 */
export function PermissionModePicker() {
  const { t } = useTranslation()
  // The chat this picker sits in - the chat page's, or a panel's - each with a mode of its own.
  const mode = useChatStore(s => s.permissionMode)
  const setMode = useChatStore(s => s.setPermissionMode)

  const names: Record<PermissionMode, string> = { ASK: t('Ask'), AUTO: t('Auto'), READ_ONLY: t('Read-only') }
  const hints: Record<PermissionMode, string> = {
    ASK: t('Asks before every change'),
    AUTO: t('Makes changes without asking'),
    READ_ONLY: t('Reads only, never changes anything'),
  }

  const Icon = ICONS[mode]
  return (
    <Popover
      placement="top-start"
      widthClass="w-64"
      ariaLabel={t('Permission: {mode}', { mode: names[mode] })}
      trigger={({ open, toggle, ref }) => (
        <Tooltip label={hints[mode]}>
          <MenuTrigger
            triggerRef={ref}
            icon={Icon}
            value={names[mode]}
            ariaLabel={t('Permission: {mode}', { mode: names[mode] })}
            open={open}
            onClick={toggle}
            tone={mode === 'AUTO' ? 'warning' : 'default'}
          />
        </Tooltip>
      )}
    >
      {close => (
        <MenuGroup>
          {ORDER.map(m => {
            const ItemIcon = ICONS[m]
            return (
              <MenuRow
                key={m}
                role="menuitemradio"
                icon={<ItemIcon />}
                title={names[m]}
                description={hints[m]}
                trailing={m === mode ? 'check' : undefined}
                onSelect={() => { setMode(m); close() }}
              />
            )
          })}
        </MenuGroup>
      )}
    </Popover>
  )
}
