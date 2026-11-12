import { useState, useEffect, useRef } from 'react'
import { MarkdownField } from '@/components/ui/MarkdownField'
import { useQuery } from '@tanstack/react-query'
import {
  Button, Caption, DialogBody, DialogHeader, FieldSkeleton, InlineCode, ListRow,
  Presence, RadioGroupSkeleton, SettingsSection, SettingsSections, Skeleton, SwitchRow,
} from '@/components/ui/kit'
import { configApi } from '@/services/configApi'
import { settingsApi } from '../services/settingsApi'
import type { ProviderInstance } from '../services/providersApi'
import { withReason } from '@/services/apiError'
import { useSettings } from '../hooks/useSettings'
import { useResolved } from '../pages/shared'
import { useModelLimits } from '../hooks/useModelLimits'
import { useFocusLeave, useSettingGroup } from '../hooks/useSettingGroup'
import { unsetLimit, type UnsetLimit } from '../utils/modelDefaults'
import { useAuthStore } from '@/store/authStore'
import { useToastStore } from '@/store/toastStore'
import { FEATURES } from '@/config/features'
import { queryClient } from '@/app/QueryClient'
import { useTranslation } from '@/i18n'
import { Rich } from '@/i18n/kit'
import { ModalShell } from '@/components/ui/ModalShell'
import type { TabProps } from '../pages/shared'
import { ChoiceField, ProviderModelRow, useModelComboboxLabels, useProviderInstances } from '../components/settingsParts'
import { useProviderModels } from '../hooks/useProviderModels'
import { Combobox, FormColumns, IconButton, Select } from '@/components/ui/kit'
import { SaveStatus } from '@/components/ui/SaveStatus'
import { X } from 'lucide-react'

// - Agents tab: the main agent, then the agents it deploys -

const TONES = [
  { key: 'concise',  label: 'Concise' },
  { key: 'normal',   label: 'Normal' },
  { key: 'creative', label: 'Creative' },
]

// What the agent runs with until it is told otherwise - ChatSettings' own default, repeated here
// only for the moment before the settings have loaded. The output ceiling and the context window
// have no such number: unset, they are the model's own (see unsetLimit).
const DEFAULT_MAX_ITERATIONS = '50'

/**
 * The first choice of an output-ceiling or context-window group - the one that means "not set",
 * stored as '' - in words: whose number it is, and the number where there is one.
 */
function useUnsetLimitChoice() {
  const { t } = useTranslation()
  return (limit: UnsetLimit) => {
    // ONE WORDING for "not set" in both groups (the UI review, 2026-10-28, found "Provider
    // default" beside "Default (200k)"): the model's own number, whoever states it.
    const words = limit.words === 'same' ? t('Same as the agent') : t('Model default')
    return { key: '', label: limit.amount ? `${words} (${limit.amount})` : words }
  }
}

const MAX_OUTPUT_TOKENS_OPTIONS = [
  { key: '8192',   label: '8k' },
  { key: '16384',  label: '16k' },
  { key: '32768',  label: '32k' },
  // 64000, not 65536 (which this was until 2026-10-03): several current models state 64000 as
  // their ceiling and refuse a request one token over it.
  { key: '64000',  label: '64k' },
  { key: '131072', label: '128k' },
]

// The agent's and its deployed agents' alike - the maintainer's lists, 2026-10-03. A value stored
// before then that is not one of them (200k, 30 steps) still applies, and shows no choice selected.
const CONTEXT_WINDOW_OPTIONS = [
  { key: '128000',  label: '128k' },
  { key: '256000',  label: '256k' },
  { key: '500000',  label: '500k' },
  { key: '1000000', label: '1M' },
]

// NO LIMIT is stored as '0' (ChatSettings.NO_ITERATION_LIMIT), for a deployed agent as for the
// agent.
const MAX_AGENT_ITERATIONS_OPTIONS = [
  { key: '20', label: '20' },
  { key: '50', label: '50' },
  { key: '100', label: '100' },
  { key: '0', label: 'Unlimited' },
]

// Settings > Agents "Prompt cache". Offered only while the agent's provider takes an explicit cache
// instruction: Anthropic, OpenRouter, and LiteLLM on its Anthropic Messages API (cachesOnRequest).
// OpenAI, Azure, Gemini and the rest cache on their own terms and would ignore the choice, so
// showing it there would be a control that does nothing.
// Stored as the backend's ChatSettings.PromptCache values. AUTO REPLACED OFF on 2026-10-07
// (maintainer's feedback): Auto is never less than five minutes, and steps up to an hour while
// deployed agents work in the background or the reader pauses longer between turns (PromptCache.forTurn). A
// stored 'off' reads as Auto on both sides.
// How many levels of deployed agents there may be under the conversation's own agent
// (`sub-agent.max-depth`, 1 to 5). Unset is 2, the backend's default.
const DEFAULT_MAX_DEPTH = '2'
const MAX_DEPTH_OPTIONS = ['1', '2', '3', '4', '5'].map(n => ({ key: n, label: n }))

// Which models an agent may name for one it deploys (SubAgentSettings model-rule, AgentModels).
const MODEL_RULE_OPTIONS = [
  { key: 'ANY',    label: 'Any listed model' },
  { key: 'ONLY',   label: 'Only these' },
  { key: 'EXCEPT', label: 'All but these' },
]

const PROMPT_CACHE_OPTIONS = [
  { key: 'auto', label: 'Auto' },
  { key: '5m',   label: '5 minutes' },
  { key: '1h',   label: '1 hour' },
]
const PROMPT_CACHE_PROTOCOLS = new Set(['ANTHROPIC', 'OPENROUTER'])

/** Whether a provider takes the prompt-cache choice - see PROMPT_CACHE_OPTIONS. */
function cachesOnRequest(instance: ProviderInstance | undefined): boolean {
  if (!instance) return false
  return PROMPT_CACHE_PROTOCOLS.has(instance.protocol)
    || (instance.protocol === 'LITELLM' && instance.api !== 'OPENAI_CHAT')
}

/**
 * The Prompt cache field for a provider: hidden where the protocol caches on its own terms, and
 * SHOWN GREYED OUT for a LiteLLM provider on OpenAI Chat Completions, with the hint saying where
 * to change that - a field that is simply absent says nothing about why (2026-10-09, the
 * maintainer's report, when it was OpenRouter's switch that was off).
 */
function promptCacheField(instance: ProviderInstance | undefined): 'on' | 'off' | null {
  if (!instance) return null
  if (cachesOnRequest(instance)) return 'on'
  return instance.protocol === 'LITELLM' ? 'off' : null
}

/** A stored prompt-cache value as one of the choices - the retired 'off' is Auto now. */
function promptCacheChoice(stored: string): string {
  return stored === 'off' ? 'auto' : stored
}

function AgentToneField({ trackChange, pending }: TabProps) {
  const { t } = useTranslation()
  const tone = useResolved('chat', 'tone', pending, 'concise')
  return (
    <ChoiceField
      label={t('Response tone')}
      value={tone}
      onChange={v => trackChange('chat', 'tone', v)}
      options={TONES}
      horizontal
    />
  )
}

function AgentIterationsField({ trackChange, pending }: TabProps) {
  const { t } = useTranslation()
  const maxIterations = useResolved('chat', 'max-iterations', pending, DEFAULT_MAX_ITERATIONS)
  return (
    <ChoiceField
      label={t('Max agent iterations')}
      value={maxIterations}
      onChange={v => trackChange('chat', 'max-iterations', v)}
      options={MAX_AGENT_ITERATIONS_OPTIONS}
      horizontal
      figures
    />
  )
}

// Chat tab: provider radio group, model combobox, tone/max-tokens/max-iterations radio groups.
function ChatTabSkeleton({ showSystemPrompt }: { showSystemPrompt: boolean }) {
  return (
    <div className="space-y-6">
      <div className="space-y-6">
        <RadioGroupSkeleton count={3} />
        <FieldSkeleton />
        <RadioGroupSkeleton count={3} horizontal />
        <RadioGroupSkeleton count={5} horizontal />
        <RadioGroupSkeleton count={8} horizontal />
        <RadioGroupSkeleton count={3} horizontal />
        {/* The System Prompt row, last in the master-agent block (see ChatTab) - only rendered for
            canEditSystemPrompt (same check as the real tab), so it doesn't pop in for users who'll
            never see it. */}
        {showSystemPrompt && <Skeleton className="h-14 w-full rounded-lg" />}
      </div>
    </div>
  )
}

export function ChatTab({ trackChange, pending }: TabProps) {
  const { t } = useTranslation()
  const { isLoading } = useSettings('chat')
  // '' while the agent runs with its model's own limits - the first choice of each group.
  const maxTokens = useResolved('chat', 'max-output-tokens', pending, '')
  const contextWindow = useResolved('chat', 'memory-max-tokens', pending, '')
  const promptCache = promptCacheChoice(useResolved('chat', 'prompt-cache', pending, 'auto'))
  const agentProvider = useResolved('chat', 'provider', pending, '')
  const agentModel = useResolved('chat', 'model', pending, '')
  const limits = useModelLimits(agentProvider, agentModel)
  const unsetChoice = useUnsetLimitChoice()
  const { data: instances = [] } = useProviderInstances()
  const agentInstance = instances.find(p => p.id === agentProvider)
  const cacheField = promptCacheField(agentInstance)

  // SERVER-ONLY, and SUPER_ADMIN even there. The prompt carries the rules that stop the agent
  // naming its underlying model vendor or reciting its own instructions, so on desktop - where
  // the only user IS the operator and there are no roles to check - showing it hands every user
  // the text of the guardrails they are the subject of, with an editor beside it. Hidden rather
  // than read-only: a prompt anyone can read is a prompt anyone can work around, and there is no
  // desktop question that reading it answers. ChatSystemPromptController's isSuperAdmin() refuses
  // the endpoints in the same edition, so this is not the only thing standing in the way.
  const user = useAuthStore(s => s.user)
  const canEditSystemPrompt = FEATURES.auth && user?.role === 'SUPER_ADMIN'
  // READ-ONLY ON DESKTOP PROPER, since 2026-10-01 (maintainer's request - "read-only, just like
  // stock skills"), which reverses the paragraph above for exactly one edition: Desktop is one
  // person on their own machine, the operator the guardrails serve. Home Server is the same bundle
  // served to whoever reaches the address, and stays hidden; the backend
  // (ChatSystemPromptController.mayRead) draws the same line.
  const { data: appConfig } = useQuery({ queryKey: ['config'], queryFn: configApi.get, staleTime: Infinity, retry: false })
  const canReadSystemPrompt = canEditSystemPrompt
    || (!FEATURES.auth && !!appConfig && !appConfig.homeServer)

  const [systemPromptOpen, setSystemPromptOpen] = useState(false)

  if (isLoading) return <ChatTabSkeleton showSystemPrompt={canReadSystemPrompt} />

  return (
    <SettingsSections>
      {/* The main agent's own section, headed like the Deployed agents one under it: the tab is
          two kinds of agent, and only one of them was named. */}
      <SettingsSection title={t('Main agent')} caption={t('The agent that answers in your conversations.')}>
        <ProviderModelRow
          role="chat"
          providerLabel="Agent provider"
          modelLabel="Agent model"
          modelPlaceholder="gemma4:31b"
          trackChange={trackChange}
          pending={pending}
        />

        <AgentToneField trackChange={trackChange} pending={pending} />

        <ChoiceField
          label={t('Max output tokens')}
          value={maxTokens}
          onChange={v => trackChange('chat', 'max-output-tokens', v)}
          options={[unsetChoice(unsetLimit('output', limits?.maxOutputTokens)), ...MAX_OUTPUT_TOKENS_OPTIONS]}
          horizontal
          figures
        />

        <ChoiceField
          label={t('Context window')}
          value={contextWindow}
          onChange={v => trackChange('chat', 'memory-max-tokens', v)}
          options={[unsetChoice(unsetLimit('context', limits?.contextWindow)), ...CONTEXT_WINDOW_OPTIONS]}
          horizontal
          figures
        />

        <AgentIterationsField trackChange={trackChange} pending={pending} />

        {/* Offered while the agent's provider takes a cache instruction; it eases in and out as
            the provider changes. */}
        <Presence show={!!cacheField}>
          <ChoiceField
            label={t('Prompt cache')}
            hint={cacheField === 'on'
              ? t('How long the conversation stays cached. 1 hour costs more.')
              : t('To cache, switch {provider} to Anthropic Messages.', { provider: agentInstance?.name ?? '' })}
            value={promptCache}
            onChange={v => trackChange('chat', 'prompt-cache', v)}
            options={PROMPT_CACHE_OPTIONS}
            horizontal
            disabled={cacheField === 'off'}
          />
        </Presence>

        {/* Inside the master-agent block, not below the Deployed agents section where this used to
            sit: the system prompt is the MASTER agent's prompt (a deployed agent gets its own task
            brief), so its position has to say so. It carries no section label of its own -
            the row's title and description already say what it is, and an "Instructions" heading
            plus "The system prompt sets the agent's core behavior" restated it twice more.
            A kit ListRow that opens a dialog: a button, with the chevron that says so. */}
        <Presence show={canReadSystemPrompt}>
          <ListRow
            onClick={() => setSystemPromptOpen(true)}
            chevron
            title={t('System prompt')}
            caption={canEditSystemPrompt
              ? t("Overrides the agent's entire system prompt")
              : t('What the agent starts every conversation with. Read-only.')}
          />
        </Presence>
      </SettingsSection>

      <SubAgentSection trackChange={trackChange} pending={pending} />

      {systemPromptOpen && (
        <SystemPromptModal
          onClose={() => setSystemPromptOpen(false)}
          readOnly={!canEditSystemPrompt}
        />
      )}
    </SettingsSections>
  )
}

/**
 * WHICH MODELS AN AGENT MAY NAME for an agent it deploys (the maintainer's request, 2026-10-29): any
 * model a configured provider lists, only the entries chosen here, or all but them. An entry is a
 * provider - all its models - or one of its models. Stored as SubAgentSettings' model-rule and
 * model-list ("<provider id>" or "<provider id>|<model>", one per line); AgentModels applies it and
 * refuses a name no provider lists whatever the rule.
 */
function DeployedModelRule({ trackChange, pending }: TabProps) {
  const { t } = useTranslation()
  const rule = useResolved('sub-agent', 'model-rule', pending, 'ANY') || 'ANY'
  // THE LIST IS ONE SETTING, saved whole when the person leaves it (the click-to-edit rule,
  // 2026-11-02): a row's provider and its model are one entry, and a row half chosen - a provider
  // whose model is still being typed - is not an entry anybody meant. The rule above saves on change.
  const list = useSettingGroup()
  const raw = useResolved('sub-agent', 'model-list', pending, '')
  const entries = parseModelList(list.value('sub-agent', 'model-list', raw))
  const { data: instances = [] } = useProviderInstances()
  const save = (next: ModelEntry[]) =>
    list.set({ prefix: 'sub-agent', field: 'model-list', value: formatModelList(next) }, { saved: raw })
  return (
    <div className="space-y-3">
      <ChoiceField
        label={t('Models agents may choose')}
        hint={t('For agents that pick a model for others.')}
        value={rule}
        onChange={v => trackChange('sub-agent', 'model-rule', v)}
        options={MODEL_RULE_OPTIONS}
        horizontal
      />
      <Presence show={rule !== 'ANY'}>
        <div className="space-y-2" {...list.bind}>
          {entries.map((e, i) => (
            <ModelRuleEntry
              key={i}
              entry={e}
              instances={instances}
              onChange={next => save(entries.map((x, k) => (k === i ? next : x)))}
              onRemove={() => save(entries.filter((_, k) => k !== i))}
            />
          ))}
          <Button
            variant="quiet"
            size="sm"
            onClick={() => save([...entries, { provider: instances[0]?.id ?? '', model: '' }])}
            disabled={instances.length === 0}
          >
            {t('Add a provider or model')}
          </Button>
        </div>
      </Presence>
    </div>
  )
}

interface ModelEntry { provider: string; model: string }

function parseModelList(raw: string): ModelEntry[] {
  return raw.split(/\r?\n/).map(l => l.trim()).filter(Boolean).map(l => {
    const bar = l.indexOf('|')
    return bar < 0 ? { provider: l, model: '' } : { provider: l.slice(0, bar).trim(), model: l.slice(bar + 1).trim() }
  })
}

function formatModelList(entries: ModelEntry[]): string {
  return entries.filter(e => e.provider).map(e => (e.model ? `${e.provider}|${e.model}` : e.provider)).join('\n')
}

// One entry: a provider, and one of its models or every one.
function ModelRuleEntry({ entry, instances, onChange, onRemove }: {
  entry: ModelEntry
  instances: ProviderInstance[]
  onChange: (next: ModelEntry) => void
  onRemove: () => void
}) {
  const { t } = useTranslation()
  const labels = useModelComboboxLabels()
  const { options, status, errorText, onOpen } = useProviderModels(entry.provider)
  return (
    <div className="flex items-center gap-2">
      <FormColumns className="items-start flex-1 min-w-0">
        <Select
          value={entry.provider}
          onChange={v => onChange({ provider: v, model: '' })}
          options={instances.map(p => ({ value: p.id, label: p.name }))}
          aria-label={t('Provider')}
        />
        <Combobox
          value={entry.model}
          onChange={v => onChange({ ...entry, model: v })}
          placeholder={t('Every model')}
          options={options}
          status={status}
          errorText={errorText}
          onOpen={onOpen}
          labels={labels}
          aria-label={t('Model')}
        />
      </FormColumns>
      <IconButton icon={X} label={t('Remove')} tone="danger" onClick={onRemove} />
    </div>
  )
}

/** A deployed agent's choices: "Same as the agent" first, naming what that currently is. */
function followingAgent(options: { key: string; label: string }[], agentValue: string, same: string,
                        t: (text: string) => string) {
  const agents = options.find(o => o.key === agentValue)?.label
  return [{ key: '', label: agents ? `${same} (${t(agents)})` : same }, ...options]
}

// THE AGENTS THE AGENT DEPLOYS (the tree of agents, since 2026-10-28; "sub-agents" before then,
// and the settings prefix is still `sub-agent`). Every agent can deploy agents of its own, each in
// a conversation of its own, down to the maximum depth set here. Lives on this tab rather than a
// tab of its own because a deployed agent is still the agent, run by the same loop. Kept visually
// separate (divider + its own description).
//
// NO MODEL OF ITS OWN, since 2026-11-02 (the maintainer: the provider and model for deployed agents
// "contradicts with the option below"): a deployed agent runs on the model the agent deploying it
// names, from the models this section allows, else on its parent's. The pickers, and the
// `sub-agent` provider, model and endpoint settings behind them, are gone.
function SubAgentSection({ trackChange, pending }: TabProps) {
  const { t } = useTranslation()
  // No switch, since 2026-10-03 (the maintainer's request): deploying agents is how the agent
  // works. A deployed agent's writes take the same approval the agent's do, so always-on cannot
  // change, approve or delete anything nobody agreed to.
  //
  // Each value is '' while the deployed agents follow the agent - the first choice of each group,
  // which names what the agent's currently is.
  const maxIterations = useResolved('sub-agent', 'max-iterations', pending, '')
  const maxDepth = useResolved('sub-agent', 'max-depth', pending, DEFAULT_MAX_DEPTH)
  const peerMessaging = useResolved('sub-agent', 'peer-messaging', pending, 'false') === 'true'
  const agentIterations = useResolved('chat', 'max-iterations', pending, DEFAULT_MAX_ITERATIONS)
  const same = t('Same as the agent')

  return (
    <SettingsSection
      title={t('Deployed agents')}
      caption={t('Agents that handle parts of the work.')}
    >
      <ChoiceField
        label={t('Max depth')}
        hint={t('How many levels of agents may work under yours.')}
        value={maxDepth}
        onChange={v => trackChange('sub-agent', 'max-depth', v)}
        options={MAX_DEPTH_OPTIONS}
        horizontal
        figures
      />

      {/* No row padding in a stack of fields, as on Updates: the stack spaces it. */}
      <SwitchRow
        className="py-0"
        title={t('Agents may message their siblings')}
        caption={t('Agents deployed by the same agent can message each other.')}
        checked={peerMessaging}
        onChange={v => trackChange('sub-agent', 'peer-messaging', String(v))}
      />

      <DeployedModelRule trackChange={trackChange} pending={pending} />

      <ChoiceField
        label={t('Max deployed agent iterations')}
        value={maxIterations}
        onChange={v => trackChange('sub-agent', 'max-iterations', v)}
        // An agent with no limit hands that on (SubAgentSettings.maxIterations).
        options={followingAgent(MAX_AGENT_ITERATIONS_OPTIONS, agentIterations, same, t)}
        horizontal
        figures
      />

    </SettingsSection>
  )
}

// Only rendered when canReadSystemPrompt - editable for a SUPER_ADMIN in the server edition, and
// read-only on Desktop proper (see caller). See ChatService.resolveSystemPrompt and
// ChatSystemPromptController for the matching backend enforcement.
//
// There is one prompt (no smart/light variant): the built-in default or an admin's saved custom
// prompt, backed by a markdown file on disk (SystemPromptService), not a page-level settings field,
// so this dialog calls the dedicated endpoints directly - same reasoning as SkillModal.
//
// SAVED WHEN IT IS LEFT, since 2026-11-02 (the click-to-edit rule): leaving the editor writes the
// prompt if it changed, and so does closing the dialog - there is no Save, Cancel or "Discard
// unsaved changes?" any more. "Reset to default" writes the default at once. A refused save keeps
// the text in the editor (a toast says why), so leaving it again tries again; the endpoint is a
// plain overwrite, with nothing to say whether someone else saved in between.
function SystemPromptModal({ onClose, readOnly = false }: { onClose: () => void; readOnly?: boolean }) {
  const { t } = useTranslation()
  const addToast = useToastStore(s => s.addToast)
  const [resetting, setResetting] = useState(false)
  const [value, setValue] = useState('')
  const [seeded, setSeeded] = useState(false)
  // What the server holds - the text the editor opened on, then the last one saved. Refs, so a save
  // made as the dialog closes reads the text as it was left.
  const saved = useRef<string | null>(null)
  const current = useRef('')
  current.current = value
  const [status, setStatus] = useState<'saving' | 'saved' | null>(null)

  // The effective prompt (an admin's saved custom prompt, or the built-in default) - never blank,
  // so the editor always opens on real content. A reader who cannot edit is shown what a
  // conversation is actually started with instead: the same prompt with the section on delegating
  // that the server adds. The raw prompt has no such section, so the dialog used to leave out the
  // part that says how the agent works.
  const { data: prompt } = useQuery({
    queryKey: ['chat-system-prompt', readOnly ? 'assembled' : 'raw'],
    queryFn: readOnly ? settingsApi.getChatSystemPromptAssembled : settingsApi.getChatSystemPrompt,
  })
  // A prompt saved by a version before 0.1.2 may still carry these. Nothing newer does.
  const hasLegacyMarkers = value.includes('{{#IF_DELEGATION}}')

  // Seeds the editor once, so the user's own edits are never clobbered by a background refetch -
  // same one-time catch-up pattern as SkillModal's detail field.
  useEffect(() => {
    if (seeded) return
    if (prompt !== undefined) { setValue(prompt); saved.current = prompt; setSeeded(true) }
  }, [prompt, seeded])

  // Writes `text` when it differs from what the server holds.
  async function saveText(text: string) {
    if (readOnly || saved.current === null || text === saved.current) return
    setStatus('saving')
    try {
      await settingsApi.saveChatSystemPrompt(text)
      saved.current = text
      void queryClient.invalidateQueries({ queryKey: ['chat-system-prompt'] })
      setStatus('saved')
    } catch (e) {
      setStatus(null)
      addToast(withReason(t('Failed to save system prompt'), e), 'danger')
    }
  }
  // Leaving the editor - or the dialog closing - saves it.
  const leave = useFocusLeave(() => { void saveText(current.current) })
  useEffect(() => {
    if (status !== 'saved') return
    const timer = setTimeout(() => setStatus(null), 2500)
    return () => clearTimeout(timer)
  }, [status])

  // A failure says so - it was an unhandled rejection, and the button simply stopped turning.
  async function resetToDefault() {
    setResetting(true)
    try {
      const text = await settingsApi.getChatSystemPromptDefault()
      setValue(text)
      await saveText(text)
    } catch (e) {
      addToast(withReason(t('Could not load the default prompt'), e), 'danger')
    } finally {
      setResetting(false)
    }
  }

  return (
    <ModalShell
      onClose={onClose}
      // A fixed height, so the dialog does not change size as the prompt loads; the editor is
      // its one scrolling region.
      size="xl"
      fill
      ariaLabel={t('System prompt')}
    >
      <DialogHeader
        title={t('System prompt')}
        actions={!readOnly && (
          <div className="flex items-center gap-3">
            {/* The quiet word that the prompt was written, as beside the Settings window's title. */}
            <SaveStatus word={status} />
            <Button variant="hyperlink" onClick={resetToDefault} disabled={resetting}>
              {resetting ? t('Resetting...') : t('Reset to default')}
            </Button>
          </div>
        )}
      />
      <DialogBody className="flex-1 min-h-0 flex flex-col gap-3">
        {/* What the server does to this text (SystemPromptService, ChatService.resolveSystemPrompt),
            so an admin editing it knows what not to "clean up" and what they will not find in it:
            PRODUCT_NAME becomes the deployment's name, and the section on delegating is added from
            outside. The IF_DELEGATION markers are named only to a prompt that still has them. */}
        {readOnly ? (
          <Caption>
            {t('What the agent starts every conversation with.')}
          </Caption>
        ) : (
          <Caption>
            <InlineCode>{'{{PRODUCT_NAME}}'}</InlineCode>{' '}
            {t("becomes this deployment's name. Keep it as it is.")}
            {/* One sentence with its two markers as slots, so a translation orders it its own way. */}
            {hasLegacyMarkers && (<>
              {' '}
              <Rich
                text={t('Text between {open} and {close} is used only while agents can be deployed.')}
                parts={{
                  open: <InlineCode>{'{{#IF_DELEGATION}}'}</InlineCode>,
                  close: <InlineCode>{'{{/IF_DELEGATION}}'}</InlineCode>,
                }}
              />
            </>)}
          </Caption>
        )}
        {/* Read-only is a DISABLED field, as an official skill's Detail is (0.28.0 feedback): the
            reader cannot change it, so it must not look like a box waiting to be typed in. The
            wrapper hears the editor's focus leaving, which is when the prompt is saved. */}
        <div className="flex-1 min-h-0 flex flex-col" {...leave}>
          <MarkdownField
            fill
            value={value}
            disabled={readOnly}
            aria-label={t('System prompt')}
            onChange={setValue}
          />
        </div>
      </DialogBody>
      {/* No footer: nothing waits for a Save, and a dialog closes on its backdrop and on Escape - a
          lone Close button is the footer the rule forbids (.claude/rules/frontend.md). */}
    </ModalShell>
  )
}
