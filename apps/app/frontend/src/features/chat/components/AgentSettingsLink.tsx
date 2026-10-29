import type { ReactNode } from 'react'
import { Tooltip } from '@/components/ui/kit'
import { useSettingsModalStore } from '@/store/settingsModalStore'
import { useTranslation } from '@/i18n'
import { cn } from '@/utils/cn'

/**
 * WHEREVER THE CHAT NAMES THE AGENT'S PROVIDER AND MODEL, A CLICK OPENS SETTINGS > AGENTS, where they
 * are chosen (the user's request, 2026-10-27 and 2026-10-29): the label beside the composer, the
 * "Waiting for ..." and "... is thinking" rows under a running turn, and the agent's row in the agents
 * map. Each place shows the model only to a reader who may read those settings (useAgentModel), so the
 * link never opens a tab its reader cannot see. `before` runs first - the agents map closes itself.
 */
export function AgentSettingsLink({ children, before, className }: {
  children: ReactNode
  before?: () => void
  className?: string
}) {
  const { t } = useTranslation()
  return (
    <Tooltip label={t('Open Agent settings')}>
      <button
        type="button"
        onClick={() => {
          before?.()
          useSettingsModalStore.getState().open('Agents')
        }}
        className={cn(
          'focus-ring min-w-0 max-w-full rounded text-left text-ink-caption transition-colors duration-150 hover:text-ink-primary motion-reduce:transition-none',
          className,
        )}
      >
        {children}
      </button>
    </Tooltip>
  )
}
