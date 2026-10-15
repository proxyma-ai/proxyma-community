import { lazy, Suspense, useState, useCallback, useMemo, useEffect } from 'react'
import { Eye } from 'lucide-react'
import { Callout, ConfirmDialog, SaveBar, SettingsWindow, type RailGroup } from '@/components/ui/kit'
import { settingsApi } from '../services/settingsApi'
import { TAB_ICONS } from '../data/tabIcons'
import { apiErrorText } from '@/services/apiError'
import { SkillsTab } from './SkillsTab'
import { MemoryTab } from './MemoryTab'
import { AssistantsTab } from './AssistantsTab'
import { RestartBanner, useRestartNow } from './RestartBanner'
// The title-slot glue and useResolved live in pages/shared.tsx rather than here: the per-edition
// pages need them too, and they cannot import this file - the modal imports the edition module
// through the @settings-pages alias, so the edition module importing the modal back would be a
// runtime cycle. Everything VISIBLE in this file is the kit's, since the 2026-10 rebuild.
import { SettingsTitleSlot, TabFieldsSkeleton } from '../pages/shared'
import { useAuthStore } from '@/store/authStore'
import { FEATURES } from '@/config/features'
import { queryClient } from '@/app/QueryClient'
// Resolved by vite.config.ts to index.desktop.tsx or index.server.tsx - only one of the two is in
// this build's module graph, so the other edition's pages are not merely hidden, they are absent.
import { edition } from '@settings-pages'
import { keys } from '@/services/queryKeys'
import { useTranslation } from '@/i18n'
import { useDiscardLabels } from '@/components/ui/ModalShell'
import type { PendingChange } from '../pages/shared'
// One file per tab since 2026-10-14 - this file is the window and nothing else.
import { AccountTab } from '../tabs/AccountTab'
import { AboutTab } from '../tabs/AboutTab'
import { ConnectorsTab } from '../tabs/ConnectionsTab'
import { ApiTokensTab } from '../tabs/ApiTokensTab'
import { ProvidersTab } from '../tabs/ProvidersTab'
import { ChatTab } from '../tabs/AgentsTab'
import { GuardrailsTab } from '../tabs/GuardrailsTab'
import { DocumentsTab } from '../tabs/ConversionTab'
import { RetrievalTab } from '../tabs/SearchTab'
import { DataRetentionTab } from '../tabs/DataRetentionTab'
import { NetworkTab } from '../tabs/NetworkTab'
import { UsageCostTab } from '../tabs/UsageCostTab'

// HIDDEN since 0.1.1 (FEATURES.workflows, a const false that the backend property of the same name
// must match), so it is loaded only if the flag is ever turned on rather than carried in every
// bundle.
const WorkflowsTab = lazy(() => import('./WorkflowsTab').then(m => ({ default: m.WorkflowsTab })))

// Every tab either edition can show. WHICH of them a given edition shows is not decided here -
// see features/settings/pages/index.{desktop,server}.tsx, one of which is compiled in by the
// @settings-pages alias. The union stays shared so links into settings (app://settings/<tab>,
// the tour, MarkdownRenderer) can name a tab without knowing the edition; naming one this
// edition does not have simply falls back to its default tab.
//
// FOUR MERGES ON 2026-09-02, taking the rail from twenty-two entries to sixteen. Each one joined
// two or three tabs that answered the same question in different places, which is what made the
// list long rather than the product being large:
//
//   About        <- About + Documentation + Privacy. None of the three changed how the install
//                   behaves; all three answer "what is this and what does it do with my data".
//   Retrieval    <- Embedding + Search Engine. One produces the vectors, the other consumes them.
//   Usage & Cost <- Usage & Cost + Cost Settings. Two tabs about money, one of which reported and
//                   one of which capped.
//
// A FOURTH WAS CONSIDERED AND REJECTED: Import & Export + Reset. They look mergeable - both are
// whole-install operations at the bottom of the rail - and the name is what killed it.
// ConfigTransferPage opens by stating it is NOT a backup: the server has a real one on
// Administration that dumps the database and the index, while this is portability, a readable
// JSON file of settings that touches no conversation, document or index. Any name covering both
// tabs ("Backup & Reset") asserts the thing that page exists to deny. Reset also sits where it
// does deliberately - away from the knobs it would wipe - and it is desktop-only, so the merge
// would have bought one rail entry in one edition at the price of a name that lies.
//
// Removing a key is not free: `app://settings/<slug>` links are baked into the stored history of
// every conversation where a tool reported a missing credential. Every retired slug is repointed
// at the tab that absorbed it in MarkdownRenderer's SETTINGS_TAB_BY_SLUG rather than dropped -
// an unrecognized slug degrades to plain text, so dropping one turns an old button into the
// breadcrumb it was introduced to replace.
export type TabKey = 'Account' | 'About' | 'Connections' | 'Skills' | 'Memory' | 'Workflows' | 'API Tokens' | 'AI Assistants' | 'AI Providers' | 'Agents' | 'Conversion' | 'Search' | 'Network' | 'Data Retention' | 'License' | 'Guardrails' | 'Usage & Cost' | 'Logs' | 'Import & Export' | 'Updates' | 'Reset'

// SYSTEM CONFIGURATION: the deployment-wide knobs, as opposed to the personal settings. ADMIN and
// SUPER_ADMIN both SEE these; only SUPER_ADMIN can change them (see SettingsController.checkAccess
// and ModelPricingController server-side, which is where the rule is actually enforced - the
// read-only rendering below is presentation, not a control). It is NOT a group of the rail any
// more: since 2026-10-02 the rail groups tabs by subject (see index.desktop.tsx), and these sit
// in two of its groups. This list is what decides read-only, and the only thing that does.
// MCP Tools used to be here. It held one deployment-wide switch per connector, ANDed with the
// user's own consent on a different tab - so enabling a write tool took two people and two pages,
// and the page that looked like it controlled the toggle was not the one holding the switch that
// was off. Every tool is now permitted at the deployment level and the per-connector permission
// lives beside that connector's URL and token, on Connections.
const SYSTEM_CONFIG_TABS: TabKey[] = [
  'AI Providers', 'Agents', 'Conversion', 'Search', 'Network', 'Data Retention',
  // Was 'Cost Settings'. The merged tab carries the same knobs plus the spend readout beside
  // them, so it stays here and keeps the read-at-ADMIN / write-at-SUPER_ADMIN rule it had -
  // reading what a deployment has spent is not a wider exposure than reading its ceiling.
  'Usage & Cost', 'Guardrails',
  // Listed because it is system configuration. The read-only rendering this list drives is a
  // no-op for it: the page has no settings fields, and it is
  // only ever in the server nav for a SUPER_ADMIN in the first place (see index.server.tsx) -
  // read-at-ADMIN / write-at-SUPER_ADMIN does not apply to something where reading IS the leak.
  'Import & Export',
]



export function SettingsModal({ onClose, initialTab, initialFocus }: {
  onClose: () => void; initialTab: TabKey | null; initialFocus?: string | null
}) {
  const { t } = useTranslation()
  const user = useAuthStore(s => s.user)
  const isAdmin = !FEATURES.auth || user?.role === 'SUPER_ADMIN' || user?.role === 'ADMIN'
  const isSuperAdmin = FEATURES.auth && user?.role === 'SUPER_ADMIN'
  // Whether License appears at all is the edition module's call now (super-admin-only on server,
  // always present on desktop, which has no roles).
  // Desktop is a single local
  // user with no roles at all, so there is no one to restrict system config from.
  const canEditSystemConfig = !FEATURES.auth || isSuperAdmin

  // Connections covers the credentialed sources - Jira, Confluence, Bitbucket, SVN, network shares,
  // in every tier, and Microsoft 365, which an Enterprise Server without a license in force cannot
  // add (ConnectionModal's `locked` says so rather than the service disappearing).

  // WHICH tabs this edition shows comes from the edition module the build compiled in - see
  // features/settings/pages/index.{desktop,server}.tsx and the @settings-pages alias in
  // vite.config.ts. The modal no longer decides; it renders what it is given.
  const navGroups = useMemo(() => edition.buildNavGroups({ isAdmin, isSuperAdmin }), [isAdmin, isSuperAdmin])


  // initialTab is honoured only if this user can actually see that tab. It arrives from a chat
  // message too (an `app://settings/...` link the agent relayed from a tool error - see
  // MarkdownRenderer), and the agent knows neither the viewer's role nor the edition, so it can
  // name an admin-only tab, or one that only the other edition has. Both fall back rather than
  // rendering a tab whose requests would 403 or whose component is not in this build.
  const [tab, setTab] = useState<TabKey>(() => {
    if (!initialTab) return edition.defaultTab
    return navGroups.some(g => g.tabs.includes(initialTab)) ? initialTab : edition.defaultTab
  })
  // The focus target is consumed once, on arrival. Without this, navigating away to another tab
  // and back would re-expand and re-focus the linked connector as if the user had just clicked the
  // link again, overriding whatever they were actually doing.
  const [focusKey, setFocusKey] = useState<string | null>(initialFocus ?? null)

  // The two initializers above only run on MOUNT, and this modal is mounted once globally in
  // AppShell and kept alive for as long as it stays open. So a second open(tab) call while it was
  // already open used to change nothing at all: the onboarding tour walks through three settings
  // steps back to back (Connectors -> Skills -> Documentation) without closing in
  // between, and every step after the first left the modal sitting on the previous tab - the
  // step's target element never existed, so the tour pointed at nothing for the rest of them.
  // Same bug for an `app://settings/<tab>` link clicked in chat while Settings happened to be
  // open already.
  //
  // Keyed on the props alone so it only reacts to a genuine new request: a caller that opens with
  // no tab (the Topbar gear) passes null and is ignored, and clicking through tabs by hand
  // afterwards never re-triggers this, since neither prop has changed.
  useEffect(() => {
    if (!initialTab) return
    if (!navGroups.some(g => g.tabs.includes(initialTab))) return
    setTab(initialTab)
    setFocusKey(initialFocus ?? null)
  }, [initialTab, initialFocus, navGroups])
  const [pending, setPending] = useState<PendingChange[]>([])
  const [saving, setSaving] = useState(false)
  const [savingField, setSavingField] = useState<string | null>(null)
  // A STATUS, not a sentence. The save bar used to hold the translated message and pick its colour with
  // msg.startsWith('Failed'), which never matches the Vietnamese copy - a failed save showed in the
  // success colour. The copy is derived from the status below, and the colour from the status too.
  const [msgStatus, setMsgStatus] = useState<'saved' | 'saveFailed' | 'restartFailed' | null>(null)
  // Why a save failed, as the server said it - a refused role, a plan that does not cover it, a
  // value out of range. "Failed to save." alone told the reader nothing they could act on.
  const [msgReason, setMsgReason] = useState<string | null>(null)
  const msg = msgStatus === 'saved' ? t('Saved.')
    : msgStatus === 'saveFailed' ? (msgReason ? t('Failed to save: {reason}', { reason: msgReason }) : t('Failed to save.'))
    : msgStatus === 'restartFailed' ? t('Restart failed. Please restart manually.')
    : ''
  const [restartNeeded, setRestartNeeded] = useState(false)
  // The restart screen is the restart store's, not local state: this modal closes on a backdrop
  // click, which would unmount the blocking screen along with it. See store/restartStore.ts.
  const restartNow = useRestartNow()
  // Gate shown before the save loop runs whenever `pending` includes the embedding model field -
  // see performSave/save below. Held separately from `saving` so the confirm dialog itself never
  // triggers a partial save.
  const [confirmEmbeddingChange, setConfirmEmbeddingChange] = useState(false)

  const isDirty = pending.length > 0
  const systemConfigReadOnly = !canEditSystemConfig && SYSTEM_CONFIG_TABS.includes(tab)

  // The title row's action slot (TitleAction) - held here so it can be provided to the tab below it.
  const [titleSlot, setTitleSlot] = useState<HTMLDivElement | null>(null)
  const ExtraPage = edition.extraPages[tab]
  // A link from one tab to another, with where to focus inside it - About's diagnostics link opens
  // Logs on its Diagnostics sub-tab.
  const openTab = useCallback((next: TabKey, focus?: string) => {
    setTab(next)
    setFocusKey(focus ?? null)
  }, [])

  const trackChange = useCallback((prefix: string, field: string, value: string) => {
    setPending(prev => {
      const filtered = prev.filter(p => !(p.prefix === prefix && p.field === field))
      return [...filtered, { prefix, field, value }]
    })
  }, [])

  const performSave = async () => {
    setSaving(true)
    setMsgStatus(null)
    try {
      const prefixes = new Set(pending.map(p => p.prefix))
      let needsRestart = false
      for (const p of pending) {
        setSavingField(`${p.prefix}.${p.field}`)
        if (p.value === '') {
          const result = await settingsApi.deleteField(p.prefix, p.field)
          if (result.requiresRestart) needsRestart = true
        } else {
          const result = await settingsApi.saveField(p.prefix, p.field, p.value)
          if (result.requiresRestart) needsRestart = true
        }
      }
      setPending([])
      for (const prefix of prefixes) {
        queryClient.invalidateQueries({ queryKey: keys.settings(prefix) })
      }
      // A setting can change what /api/v1/config reports - an edition's own switches, such as which
      // Microsoft 365 source types an Enterprise Server offers, which the Sources page reads to
      // decide what Add Source offers. It is cached indefinitely, so refresh it after a save.
      queryClient.invalidateQueries({ queryKey: ['config'] })
      if (needsRestart) {
        setRestartNeeded(true)
        setMsgStatus(null)
      } else {
        setMsgStatus('saved')
      }
    } catch (e) {
      setMsgReason(apiErrorText(e))
      setMsgStatus('saveFailed')
    } finally {
      setSaving(false)
      setSavingField(null)
    }
  }

  // Single choke point every tab's Save button goes through. If the embedding model is among the
  // pending changes, block on a confirmation modal first - the whole save (not just that one
  // field) waits, so a partial save mid-confirmation can never happen and cancelling leaves every
  // other pending change intact.
  const save = () => {
    const embeddingModelChanging = pending.some(p => p.prefix === 'embedding' && p.field === 'model')
    if (embeddingModelChanging) {
      setConfirmEmbeddingChange(true)
      return
    }
    void performSave()
  }

  const confirmEmbeddingChangeAndSave = () => {
    setConfirmEmbeddingChange(false)
    void performSave()
  }

  const cancel = () => {
    setPending([])
    setMsgStatus(null)
  }

  const handleRestartNow = async () => {
    setRestartNeeded(false)
    if (!(await restartNow())) setMsgStatus('restartFailed')
  }

  // The rail's entries, in the reader's language and with each tab's glyph. The kit SettingsRail
  // (drawn by SettingsWindow from these groups) is a grouped column from md and a flat row
  // scrolling sideways below md and on a short screen, keeping the active entry in view on
  // whichever axis scrolls.
  const discardLabels = useDiscardLabels()
  const railGroups = useMemo<RailGroup<TabKey>[]>(() => navGroups.map(g => ({
    label: t(g.label),
    items: g.tabs.map(key => ({ key, label: t(key), icon: TAB_ICONS[key] })),
  })), [navGroups, t])

  return (
    // THE KIT'S SETTINGS WINDOW (since 2026-10-14): the dialog at the settings panel's size - nearly
    // full-screen on a phone, always with a margin of backdrop round it, an overlay CLIPPED rather
    // than scrollable so a wheel over the rail never moves the whole window - the rail, the titled
    // content column and the save bar. The consoles' Settings windows are the same part.
    <SettingsWindow
      onClose={onClose}
      closable={!saving}
      // Unsaved changes are the pending list the Save bar already holds. Closing with any of them -
      // Escape, the backdrop - asks first rather than dropping them the way the backdrop used to.
      dirty={isDirty}
      discardLabels={discardLabels}
      ariaLabel={t('Settings')}
      groups={railGroups}
      active={tab}
      onChange={key => { setFocusKey(null); setTab(key) }}
      banner={restartNeeded && (
        <RestartBanner
          onRestartNow={handleRestartNow}
          onRestartLater={() => setRestartNeeded(false)}
        />
      )}
      // EVERY TAB OPENS WITH ITS NAME, since 2026-09-30 (F21 of that day's review). Rendered by the
      // window, once, so a tab can neither forget it nor restate it as a heading of its own. Its
      // action slot is where TitleAction portals a tab's one primary action ("Add connection").
      title={t(tab)}
      titleActionsRef={setTitleSlot}
      notice={systemConfigReadOnly && <ReadOnlyConfigNotice />}
      footer={
        <SaveBar
          dirty={isDirty}
          saving={saving}
          status={msg ? { tone: msgStatus === 'saved' ? 'success' : 'danger', text: msg } : null}
          progressText={saving && savingField && pending.length > 1
            ? t('Saving {field}...', { field: savingField })
            : undefined}
          onCancel={cancel}
          onSave={save}
          labels={{ cancel: t('Cancel'), save: t('Save'), saving: t('Saving...') }}
        />
      }
    >
      <SettingsTitleSlot.Provider value={{ slot: titleSlot, disabled: systemConfigReadOnly }}>
        {/* A real <fieldset disabled>, not per-control props: it natively disables every
            descendant input/select/textarea/button in one place, and every control these tabs
            use bottoms out in one of those (Select and ToggleSwitch both render a <button>).
            Threading a readOnly prop through eight tab components instead would mean each new
            control is opt-in to the restriction - i.e. one forgotten prop is a hole. Note this
            is presentation only; SettingsController/ModelPricingController enforce the rule.
            min-w-0 because a fieldset defaults to min-width:min-content, which would otherwise
            stop this flex child from shrinking. */}
        <fieldset
          disabled={systemConfigReadOnly}
          className="min-w-0 m-0 p-0 border-0"
        >
          {/* One tab at a time: the window's content column re-fades whenever the tab changes
              (the layout's contentKey), so a tab needs no Presence of its own. */}
          {tab === 'Account'              && <AccountTab />}
          {tab === 'About'                && <AboutTab onClose={onClose} onOpenTab={openTab} />}
          {tab === 'Connections'          && <ConnectorsTab trackChange={trackChange} pending={pending} focusKey={focusKey} />}
          {tab === 'Skills'               && <SkillsTab />}
          {tab === 'Memory'               && <MemoryTab />}
          {FEATURES.workflows && tab === 'Workflows' && (
            <Suspense fallback={<TabFieldsSkeleton />}><WorkflowsTab /></Suspense>
          )}
          {tab === 'API Tokens'           && <ApiTokensTab />}
          {tab === 'AI Assistants'        && <AssistantsTab onOpenTab={setTab} />}
          {tab === 'AI Providers'         && <ProvidersTab trackChange={trackChange} pending={pending} onOpenTab={setTab} canEdit={canEditSystemConfig} />}
          {tab === 'Agents'               && <ChatTab trackChange={trackChange} pending={pending} />}
          {tab === 'Guardrails'           && <GuardrailsTab trackChange={trackChange} pending={pending} onOpenTab={openTab} />}
          {tab === 'Conversion'           && <DocumentsTab trackChange={trackChange} pending={pending} />}
          {tab === 'Search'               && <RetrievalTab trackChange={trackChange} pending={pending} />}
          {tab === 'Network'              && <NetworkTab trackChange={trackChange} pending={pending} />}
          {tab === 'Data Retention'       && <DataRetentionTab trackChange={trackChange} pending={pending} />}
          {tab === 'Usage & Cost'         && <UsageCostTab trackChange={trackChange} pending={pending} onOpenTab={openTab} />}
          {/* Pages only one edition has, supplied by that edition's module so the component is
              imported by it and by nothing else. */}
          {ExtraPage && <ExtraPage trackChange={trackChange} pending={pending} focusKey={focusKey} onOpenTab={openTab} />}
        </fieldset>
      </SettingsTitleSlot.Provider>

      {/* Blocking confirmation gate for embedding-model changes - see save and performSave above.
          A warning, not a deletion, so the kit ConfirmDialog in its warning tone. */}
      {confirmEmbeddingChange && (
        <ConfirmDialog
          tone="warning"
          title={t('Change embedding model?')}
          description={t('Changing the embedding model does not update your existing search index by itself.')}
          consequences={[
            t('Every connector indexed with the old model needs a full re-index before it works correctly with the new model.'),
            t('Re-indexing is manual, per connector. Until you do it, affected connectors silently return degraded or wrong results.'),
            t('Re-indexing can take a long time for large connectors and consumes real embedding compute/cost.'),
          ]}
          confirmLabel={t('Save anyway')}
          cancelLabel={t('Cancel')}
          onConfirm={confirmEmbeddingChangeAndSave}
          onCancel={() => setConfirmEmbeddingChange(false)}
        />
      )}
    </SettingsWindow>
  )
}

// Shown above a system-config tab an ADMIN can read but not change. Says who CAN change it, so
// the disabled controls read as a deliberate permission boundary rather than a broken page - the
// single most common way a read-only surface gets misreported as a bug.
function ReadOnlyConfigNotice() {
  const { t } = useTranslation()
  return (
    <Callout tone="neutral" icon={Eye}>
      {t('View only - system configuration applies to the whole deployment, so only a super admin can change it.')}
    </Callout>
  )
}
