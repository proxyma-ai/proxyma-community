import {
  Caption, Field, FieldSkeleton, Input, Presence, RadioGroupSkeleton, SecretInput, SettingsSection,
  SettingsSections, SwitchRowSkeleton, ToggleSwitch,
} from '@/components/ui/kit'
import { useSettings } from '../hooks/useSettings'
import { SignpostSection, useResolved } from '../pages/shared'
import { useTranslation } from '@/i18n'
import type { TabProps } from '../pages/shared'
import { ChoiceField, ProviderModelRow, useSecretLabels } from '../components/settingsParts'

// Search tab: the embedding block always renders (provider radio group, model input, the
// concurrency group), then three toggle sections - Query Expansion, Reranking, Knowledge Graph -
// whose nested fields only appear once enabled, so the skeleton shows the collapsed toggle row
// shape since the real enabled/disabled state is not known until data loads.
//
// ONE skeleton for what used to be two tabs. Both halves load from the same settings endpoint, so
// two stacked skeletons could only ever flash a half-rendered page.
function RetrievalTabSkeleton() {
  return (
    <div className="space-y-6">
      <RadioGroupSkeleton count={3} />
      <FieldSkeleton />
      <RadioGroupSkeleton count={5} />
      <SwitchRowSkeleton />
      <SwitchRowSkeleton />
      <SwitchRowSkeleton />
    </div>
  )
}

// - Search Engine tab -

const QUERY_EXPANSION_VARIANTS_OPTIONS = [
  { key: '2', label: '2' },
  { key: '3', label: '3' },
  { key: '4', label: '4' },
]

// GraphRAG's gleaning rounds - how many times the extractor is asked "did you miss anything"
// after its first pass. The most direct cost lever in the feature: each round is another full
// call over the same chunk, so 1 is roughly double the extraction bill and 2 is triple, for
// diminishing recall. 0 is a legitimate choice on a large corpus, which is why it is offered.
const GRAPH_GLEANINGS_OPTIONS = [
  { key: '0', label: '0' },
  { key: '1', label: '1' },
  { key: '2', label: '2' },
]

// Retrieval - Embedding and Search Engine, which were two tabs until 2026-09-02.
//
// They are ONE PIPELINE and were already adjacent for that reason: embedding turns documents into
// vectors at ingest, and everything below turns a question into the documents that come back. Two
// rail entries made that look like two subjects, and the split put "which embedding model" a click
// away from "why are the answers bad", which is the same conversation.
//
// Order is the direction the data travels: what is indexed, then what happens to a query. The
// Knowledge Graph section stays last and keeps its warning, because it is the odd one out - the
// only thing here that spends at INGEST time, per chunk of every document, whether or not anybody
// ever asks a question.
function EmbeddingFields({ trackChange, pending }: TabProps) {
  const { t } = useTranslation()
  const maxConcurrentRequests = useResolved('embedding', 'max-concurrent-requests', pending, '4')
  return (
    <>
      <ProviderModelRow
        role="embedding"
        providerLabel="Embedding provider"
        modelLabel="Embedding model"
        modelPlaceholder="qwen3-embedding:latest"
        trackChange={trackChange}
        pending={pending}
      />
      <ChoiceField
        label={t('Max concurrent embedding requests')}
        hint={t("How many batch-embed requests run at once; match your provider's capacity (e.g. Ollama's NUM_PARALLEL).")}
        value={maxConcurrentRequests}
        onChange={v => trackChange('embedding', 'max-concurrent-requests', v)}
        options={EMBEDDING_CONCURRENCY_OPTIONS}
        horizontal
        figures
      />
      <Caption tone="warning">
        {t('Changing the embedding model requires a full re-index of all connectors.')}
      </Caption>
    </>
  )
}

function QueryExpansionFields({ trackChange, pending }: TabProps) {
  const { t } = useTranslation()
  const qeVariants = useResolved('query-expansion', 'variants', pending, '3')
  return (
    <>
      <ProviderModelRow
        role="query-expansion"
        providerLabel="Query expansion provider"
        modelLabel="Query expansion model"
        modelPlaceholder="gemma4:31b"
        trackChange={trackChange}
        pending={pending}
      />
      <ChoiceField
        label={t('Variants')}
        value={qeVariants}
        onChange={v => trackChange('query-expansion', 'variants', v)}
        options={QUERY_EXPANSION_VARIANTS_OPTIONS}
        horizontal
        figures
      />
    </>
  )
}

function RerankingFields({ trackChange, pending }: TabProps) {
  return (
    <ProviderModelRow
      role="reranking"
      providerLabel="Reranking provider"
      modelLabel="Reranking model"
      modelPlaceholder="gemma4:31b"
      trackChange={trackChange}
      pending={pending}
    />
  )
}

function KnowledgeGraphFields({ trackChange, pending }: TabProps) {
  const { t } = useTranslation()
  const kgGleanings = useResolved('graph', 'gleanings', pending, '1')
  const kgTimeout = useResolved('graph', 'call-timeout-seconds', pending, '120')
  return (
    <>
      <Caption tone="warning">
        {t('Costs one LLM call per chunk on top of embedding, which can double or triple indexing cost. Use a cheap model.')}
      </Caption>
      <ProviderModelRow
        role="graph"
        providerLabel="Extraction provider"
        modelLabel="Extraction model"
        modelPlaceholder="gemma4:31b"
        trackChange={trackChange}
        pending={pending}
      />
      <ChoiceField
        label={t('Gleaning rounds')}
        hint={t('Extra passes asking the model what it missed. 1 roughly doubles the cost above and 2 triples it.')}
        value={kgGleanings}
        onChange={v => trackChange('graph', 'gleanings', v)}
        options={GRAPH_GLEANINGS_OPTIONS}
        horizontal
        figures
      />
      {/* THE SETTING THREE LOG MESSAGES TOLD OPERATORS TO RAISE, and which no screen offered
          until 2026-09-16. A local reasoning model spends most of its budget thinking before
          it answers - measured at 82 seconds for two sentences - so the 120-second default
          times out on ordinary chunks, and a timed-out chunk is one nobody read.

          Three choices since 2026-10-03 (the maintainer's request), like the agent's limits; it
          was a box for any number of seconds. A value saved before then that is not one of them
          still applies and shows no choice selected. */}
      <ChoiceField
        label={t('Extraction timeout')}
        hint={t('How long one chunk may take before it is given up on. Raise it for local or reasoning models; timed-out sources are retried later.')}
        value={kgTimeout}
        onChange={v => trackChange('graph', 'call-timeout-seconds', v)}
        // "2 minutes", in the reader's language - it was "2m".
        options={EXTRACTION_TIMEOUT_MINUTES.map(m => ({ key: String(m * 60), label: t('{count} minute|{count} minutes', { count: m }) }))}
        translated
        horizontal
        figures
      />
    </>
  )
}

// GraphSettings.callTimeoutSeconds, in seconds; two minutes is its default.
const EXTRACTION_TIMEOUT_MINUTES = [2, 5, 10]

export function RetrievalTab({ trackChange, pending }: TabProps) {
  const { t } = useTranslation()
  const { isLoading: embLoading } = useSettings('embedding')
  const { isLoading: qeLoading } = useSettings('query-expansion')
  const { isLoading: rrLoading } = useSettings('reranking')
  const { isLoading: kgLoading } = useSettings('graph')
  const { isLoading: chatLoading } = useSettings('chat')
  const { isLoading: webLoading } = useSettings(WEB_SEARCH_GROUP.prefix)
  const qeEnabled = useResolved('query-expansion', 'enabled', pending) === 'true'
  const rrEnabled = useResolved('reranking', 'enabled', pending) === 'true'
  const kgEnabled = useResolved('graph', 'enabled', pending) === 'true'
  const kgInSearch = useResolved('graph', 'search-channel', pending) === 'true'

  // One skeleton for the whole tab rather than two stacked ones. Both halves load from the same
  // settings endpoint, so a split skeleton would only ever flash a half-rendered page.
  if (embLoading || qeLoading || rrLoading || kgLoading || chatLoading || webLoading) return <RetrievalTabSkeleton />

  return (
    <SettingsSections>
      {/* INDEXING. First because nothing below it can return anything until documents have been
          embedded, and because changing the model here invalidates everything downstream. */}
      <SettingsSection title={t('Embedding')} caption={t('The model that turns documents and questions into vectors')}>
        <EmbeddingFields trackChange={trackChange} pending={pending} />
      </SettingsSection>

      <SettingsSection
        title={t('Query expansion')}
        caption={t('Generate alternative phrasings to improve recall')}
        toggle={{ checked: qeEnabled, onChange: v => trackChange('query-expansion', 'enabled', String(v)) }}
      >
        <Presence show={qeEnabled}><div className="space-y-6"><QueryExpansionFields trackChange={trackChange} pending={pending} /></div></Presence>
      </SettingsSection>

      <SettingsSection
        title={t('Reranking')}
        caption={t('LLM cross-encoder scoring for improved precision')}
        toggle={{ checked: rrEnabled, onChange: v => trackChange('reranking', 'enabled', String(v)) }}
      >
        <Presence show={rrEnabled}><div className="space-y-6"><RerankingFields trackChange={trackChange} pending={pending} /></div></Presence>
      </SettingsSection>

      {/* Sits beside the two search toggles because it is configured the same way, but it is the
          odd one out and the description has to say so: query expansion and reranking spend at
          SEARCH time, per question asked. This one spends at INGEST time, per chunk of every
          document, whether or not anyone ever asks a question. Someone reading three toggles in a
          row will assume they cost alike unless told otherwise. */}
      <SettingsSection
        title={t('Knowledge graph')}
        caption={t('Extract entities and relations while indexing, for questions whose answer spans documents')}
        toggle={{ checked: kgEnabled, onChange: v => trackChange('graph', 'enabled', String(v)) }}
      >
        <Presence show={kgEnabled}><div className="space-y-6"><KnowledgeGraphFields trackChange={trackChange} pending={pending} /></div></Presence>
      </SettingsSection>

      {/* Independent of extraction: the graph also holds exact relationships read from Jira,
          Confluence, Bitbucket and SVN metadata with no model at all. Off by default, and the
          caption says why - it helps "how is X connected" questions and can crowd plain lookups. */}
      {/* A switch and nothing under it: the heading alone, flush (a SettingsSection keeps a gap
          under its heading for fields that never come). */}
      <SignpostSection
        title={t('Knowledge graph in search')}
        caption={t('Also rank passages by how the things they name connect. Try it on your own questions before leaving it on: it helps questions about connections and can crowd out plain lookups.')}
        action={
          <ToggleSwitch
            checked={kgInSearch}
            onChange={v => trackChange('graph', 'search-channel', String(v))}
            ariaLabel={t('Knowledge graph in search')}
          />
        }
      />

      <SettingsSection title={t('Time limit')}>
        <SearchTimeoutField trackChange={trackChange} pending={pending} />
      </SettingsSection>

      {/* LAST: the one search here that is not of your sources. On this tab since 2026-10-10 (the
          maintainer's request) - it sat under AI Providers as "Other integrations". Same prefix,
          same two fields, same admin-only scope as the rest of this tab. */}
      <WebSearchSection trackChange={trackChange} pending={pending} />
    </SettingsSections>
  )
}

/** Settings > Search > Web search: the Google Custom Search credential, both fields optional. */
function WebSearchSection({ trackChange, pending }: TabProps) {
  const { t } = useTranslation()
  return (
    <SettingsSection title={t(WEB_SEARCH_GROUP.label)} caption={t(WEB_SEARCH_GROUP.description)}>
      {WEB_SEARCH_GROUP.fields.map(f => (
        <CredentialField
          key={f.key}
          prefix={WEB_SEARCH_GROUP.prefix}
          field={f.key}
          label={f.label}
          placeholder={f.placeholder}
          type={f.type}
          trackChange={trackChange}
          pending={pending}
        />
      ))}
    </SettingsSection>
  )
}

// EMBEDDING_CONCURRENCY_OPTIONS stays; its tab does not. The controls moved into RetrievalTab
// above, which is where the pipeline they belong to is now configured in one place.
const EMBEDDING_CONCURRENCY_OPTIONS = [
  { key: '1',  label: '1' },
  { key: '2',  label: '2' },
  { key: '4',  label: '4' },
  { key: '8',  label: '8' },
  { key: '16', label: '16' },
]

// How long one search may run, in minutes (ChatSettings.searchTimeoutMinutes) - the agent's
// searches of the indexed sources, the knowledge graph, earlier conversations and its memory.
// Every other tool keeps its fixed minute. Numbers, like the sub-agent's time limit.
const SEARCH_TIMEOUT_OPTIONS = [
  { key: '1', label: '1' },
  { key: '2', label: '2' },
  { key: '5', label: '5' },
  { key: '10', label: '10' },
  { key: '15', label: '15' },
]

// In Settings > Search, not Agents (maintainer, 2026-10-09): it is how long a search of the sources
// may run. Still stored with the agent's settings (chat.search-timeout-minutes) - only its place moved.
function SearchTimeoutField({ trackChange, pending }: TabProps) {
  const { t } = useTranslation()
  const minutes = useResolved('chat', 'search-timeout-minutes', pending, '5')
  return (
    <ChoiceField
      label={t('Search time limit (minutes)')}
      hint={t('How long one search of your sources may take before the agent is told it timed out. Other tools stop after one minute.')}
      value={minutes}
      onChange={v => trackChange('chat', 'search-timeout-minutes', v)}
      options={SEARCH_TIMEOUT_OPTIONS}
      horizontal
      figures
    />
  )
}

// A plain group of credential fields. The connectors no longer use this - they are connections,
// each added through ConnectionModal - and the collapsible CredentialCategory that drew a group went
// with its last user on 2026-10-10. What is left is the Web search credential (WEB_SEARCH_GROUP),
// which genuinely is just two API fields, drawn by the Search tab's WebSearchSection.
interface CredentialGroup {
  key: string
  label: string
  description: string
  fields: Array<{ key: string; label: string; placeholder: string; type?: string }>
  prefix: string
}

function CredentialField({ prefix, field, label, placeholder, type, trackChange, pending, focused }: {
  prefix: string; field: string; label: string; placeholder: string; type?: string; focused?: boolean
} & TabProps) {
  const { t } = useTranslation()
  const secretLabels = useSecretLabels()
  const value = useResolved(prefix, field, pending)
  const { data: serverData } = useSettings(prefix)
  const serverValue = serverData?.[field] ? String(serverData[field]) : ''
  const isSecret = type === 'password' || field.includes('token') || field.includes('secret') || field.includes('credential')

  return (
    <Field label={t(label)}>
      {id => isSecret ? (
        <SecretInput
          id={id}
          value={value}
          serverValue={serverValue}
          onChange={v => trackChange(prefix, field, v)}
          placeholder={placeholder}
          autoFocus={focused}
          labels={secretLabels}
        />
      ) : (
        <Input
          id={id}
          type={type ?? 'text'}
          value={value}
          onChange={e => trackChange(prefix, field, e.target.value)}
          placeholder={placeholder}
          autoFocus={focused}
        />
      )}
    </Field>
  )
}

// The web search credential - the Search tab's Web search section (WebSearchSection). It was an
// "Other integrations" section of AI Providers until 2026-10-10 (maintainer's request), where it
// read as one more provider to set up. Not a chat/embedding provider (no base URL, no Test
// Connection via GET /api/v1/providers/{provider}/models) - a plain two-field API credential,
// admin-scoped (web-search isn't in SettingsController's VIEWER_PREFIXES), as the Search tab is.
// Kept as a group so the catalog checker's DYNAMIC entry still sees its prose.
const WEB_SEARCH_GROUP: CredentialGroup = {
  key: 'web-search', label: 'Web search', prefix: 'web-search',
  // No longer "Requires". Web search works with these blank - the agent falls back to a keyless
  // search endpoint (see KeylessWebSearch). Filling them in swaps that for Google Custom Search,
  // which is worth doing on a deployment that leans on search, because the keyless path scrapes
  // result markup and can be rate-limited or broken by a redesign.
  description: 'Lets the chat agent search the web and fetch URLs, with nothing configured. A Google Custom Search API key and Search Engine ID make search more reliable.',
  fields: [
    { key: 'api-key', label: 'API key', placeholder: 'AIza...', type: 'password' },
    { key: 'search-engine-id', label: 'Search engine ID', placeholder: 'a1b2c3d4e5f6g7h8i' },
  ],
}

