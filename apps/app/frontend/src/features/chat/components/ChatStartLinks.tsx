import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { Cpu, FolderPlus } from 'lucide-react'
import type { TabKey } from '@/features/settings/components/SettingsModal'
import { FEATURES } from '@/config/features'
import { useAuthStore } from '@/store/authStore'
import { useSettingsModalStore } from '@/store/settingsModalStore'
import { useSettings } from '@/features/settings/hooks/useSettings'
import { indexingApi } from '@/features/indexing/services/indexingApi'
import { ChatStartLinks as KitChatStartLinks, ExternalLink } from '@/components/ui/kit'
import { keys } from '@/services/queryKeys'
import { useCanReadAgentSettings } from '../hooks/useCanReadAgentSettings'
import { useTranslation } from '@/i18n'

/**
 * Under the new-chat composer: the ONE thing that would make this chat work better right now, if
 * there is one, and a quiet way to the documentation.
 *
 * It was five chips (the maintainer's request, 2026-09-28): Connectors, Add a source, and three
 * documents - using Proxyma from Claude Code, VS Code or Copilot, its API, and what the agent can
 * do. The review's F27 (approved 2026-09-30) found a row of centered chips, three of them external
 * links, on the one screen whose job is to get a question typed. So it asks the deployment instead:
 *
 *   - no chat model set up  -> Set up a model (Settings > AI Providers), for whoever may set one;
 *   - no sources at all     -> Add a source;
 *   - otherwise             -> nothing but Help.
 *
 * TWO QUIET WAYS IN BESIDE HELP, since 2026-10-01 (maintainer: "is it really only this link?"):
 * Skills and the AI Assistants tab - both in the app, both things a new reader would not otherwise
 * find from here, and both plain caption-colored links rather than the chips F27 removed.
 *
 * Help goes to the documentation index, which lists the three documents the chips linked to.
 * Settings > About already links the manual, the API and MCP guide, the Q&A and the release notes.
 *
 * A guest on server mode has no Settings and no Sources, so sees Help only.
 */
export function ChatStartLinks() {
  const { t, locale } = useTranslation()
  const navigate = useNavigate()
  const user = useAuthStore(s => s.user)
  const canManage = !FEATURES.auth || !!user
  // The chat settings are an administrator's to read on Enterprise Server (see useAgentModel), so
  // only those who could fix a missing model are told about one.
  const canSetModel = useCanReadAgentSettings()
  const { data: chat } = useSettings('chat', canSetModel)
  const { data: summary } = useQuery({
    queryKey: keys.connectorSummary(true),
    queryFn: () => indexingApi.getSummary(true),
    enabled: canManage,
  })
  const noModel = canSetModel && !!chat && !String(chat.model ?? '').trim()
  const noSources = canManage && !!summary && summary.totalSources === 0
  const docs = `https://proxyma.ai${locale === 'en' ? '' : `/${locale}`}/docs/`

  const action = noModel
    ? { icon: Cpu, label: t('Set up a model'), onClick: () => useSettingsModalStore.getState().open('AI Providers') }
    : noSources
      ? { icon: FolderPlus, label: t('Add a source'), onClick: () => navigate('/sources') }
      : null

  const openTab = (tab: TabKey) => () => useSettingsModalStore.getState().open(tab)

  const links = [
    ...(canManage ? [
      { key: 'skills', render: (cls: string) => <button type="button" onClick={openTab('Skills')} className={cls}>{t('Skills')}</button> },
      { key: 'assistants', render: (cls: string) => <button type="button" onClick={openTab('AI Assistants')} className={cls}>{t('Use from other AI assistants')}</button> },
    ] : []),
    { key: 'help', render: (cls: string) => <ExternalLink href={docs} className={cls} iconSize={11}>{t('Help')}</ExternalLink> },
  ]

  return (
    // The kit's row: from the left edge on a phone, where it sits above the docked composer -
    // nothing to tap is placed mid-screen there - and centered under the composer from `sm` up.
    <nav aria-label={t('Get started')}>
      <KitChatStartLinks
        primary={action ? {
          label: action.label,
          icon: action.icon,
          render: (cls, body) => <button type="button" onClick={action.onClick} className={cls}>{body}</button>,
        } : undefined}
        links={links}
      />
    </nav>
  )
}
