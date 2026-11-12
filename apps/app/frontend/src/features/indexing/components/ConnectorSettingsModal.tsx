import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, FolderSearch } from 'lucide-react'
import {
  Button, Caption, DialogBody, DialogHeader, Divider, Field, FormColumns, FormStack, InlineNotice,
  MetaList, Presence, ProgressRow, StaticValue, SwitchRow, TEXT, cn,
} from '@/components/ui/kit'
import { EditableText } from '@/components/ui/EditableText'
import { quietly } from '@/components/ui/AutosaveFields'
import { combinedSaveWord, sameSet } from '@/components/ui/autosave'
import { useAutosave } from '@/components/ui/useAutosave'
import { SaveStatus } from '@/components/ui/SaveStatus'
import { useTranslation } from '@/i18n'
import { serverText } from '@/i18n/serverText'
import { formatDate } from '@/utils/formatters'
import { useFileUpload } from '@/hooks/useFileUpload'
import { useRelinkFolder } from '../hooks/useRelinkFolder'
import { keys } from '@/services/queryKeys'
import { useToastStore } from '@/store/toastStore'
import { useSettings } from '@/features/settings/hooks/useSettings'
import { useCatalogType } from '../hooks/useConnectorCatalog'
import { FieldSpecForm } from './FieldSpecForm'
import { initialFieldValues, submittedFieldValues } from '../utils/fieldForm'
import { useWebCrawlLimits } from '../hooks/useWebCrawlLimits'
import { FileTypeFilter } from './FileTypeFilter'
import { ConnectionPicker, useConnectionChoice } from './ConnectionPicker'
import { SubfoldersSwitch, WebCrawlFields } from './SourceScopeFields'
import { apiErrorText } from '@/services/apiError'
import { indexingApi, type ConnectorConfigUpdate } from '../services/indexingApi'
import type { ConnectorConfig } from '../types/indexing.types'
import { ModalShell } from '@/components/ui/ModalShell'
import { SourceTypeMark } from '../utils/sourceMarks'

// Everything a connector's scope is made of, editable after registration. Before this existed,
// "crawl one level deeper" or "also index .md" meant deleting the connector and adding it back,
// which threw away its entire indexed history to change one number.
//
// FILES, WEB_PAGE, the remote file-based types (their descriptors' fileTypeFilter) and every type
// built on the connector framework (its descriptor's fields) have something editable - an older
// type's scope IS its identity in the remote system (a Jira project key, a Confluence space key),
// decided at registration and not a setting. Every connector nonetheless
// has the knowledge-graph opt-in below, which is why this now opens for all of them and the scope
// block is the conditional part rather than the whole modal. canConfigureConnector() is still the single place that decides this, so the card
// menu, the detail modal and this component can never disagree about who offers the action.
export function canConfigureConnector(connector: ConnectorConfig): boolean {
  return connector.ownedByMe
}

// A website's crawl settings are its fields, as every type's are - but its scope has a section of its
// own below (bounds checked against the limits the server serves), so they are read here rather than
// drawn by the generic field form.
const WEB_TYPE = 'webpage'
const NO_FIELDS: Record<string, string> = {}

interface Props {
  connector: ConnectorConfig
  onClose: () => void
}

// What this dialog saves through the config PATCH, as the dialog shows it. Each field is saved on its
// own (useAutosave), and every such save re-syncs the source - so only what a change touches is sent,
// except where the endpoint has always taken the whole scope (a folder, a website).
interface Scope {
  recursive: boolean
  fileTypes: string[]
  maxDepth: string
  maxPages: string
  sameDomainOnly: boolean
  renderJs: boolean
  includePattern: string
  native: Record<string, string>
  connectionId: string
  /** A local folder's new place - sent once, with the change that moves it. */
  path: string | null
}

// How long the file-type chips wait for the next click before saving: each save re-syncs the whole
// source, and picking four types is four clicks, not four re-syncs.
const FILE_TYPES_SETTLE_MS = 1200

/**
 * A SOURCE'S OWN SETTINGS, SAVED FIELD BY FIELD (the maintainer, 2026-11-02: an existing item is edited
 * in place, each field saving on its own; a form whose fields were tested together autosaves per field
 * too). There is no Save button: a number, a pattern or a field of the type's own is click-to-edit and
 * saved when it is left (checked first - a crawl depth past the server's ceiling is never sent); a
 * switch, a choice of connection or a relinked folder is saved as it changes; the file-type chips are
 * saved once the clicks stop. A refusal is said under the settings, in the server's words, and the
 * field goes back. Adding a source is still a form (SourceDialog, ConnectorConfigModal).
 */
export function ConnectorSettingsModal({ connector, onClose }: Props) {
  const qc = useQueryClient()
  const { t, locale } = useTranslation()
  const addToast = useToastStore(s => s.addToast)
  const [error, setError] = useState('')

  const web = connector.fields ?? NO_FIELDS
  const limits = useWebCrawlLimits()

  // What the type's descriptor says can be edited: the remote file-based types' file filter and
  // subfolders (a SharePoint site is listed through a flat feed with no subfolder depth to limit),
  // and a framework type's own fields.
  const descriptor = useCatalogType(connector.type)
  const hasFileScope = connector.type !== 'files' && !!descriptor?.capabilities.fileTypeFilter
  const hasRecursive = !!descriptor?.capabilities.recursiveScope

  // A LOCAL folder's location. Choosing a folder relinks the source to it at once (useRelinkFolder).
  const uploadsAreHowFilesArrive = useFileUpload()
  const canRelink = connector.type === 'files' && connector.ownedByMe && !uploadsAreHowFilesArrive
  const relinkFolder = useRelinkFolder(connector)
  // Null until a folder is chosen. The source's current path is never sent to the page (see
  // ConnectorController: an absolute path does not reach a client), so the row shows its name.
  const [folderPath, setFolderPath] = useState<string | null>(null)
  // While the native chooser is on screen - `browse` only asks, so the hook keeps no busy flag.
  const [browsing, setBrowsing] = useState(false)

  // A FRAMEWORK TYPE'S FIELDS - its descriptor's, with the values the source was saved with
  // (`fields`, which only such a type has). A secret is never sent back; one left blank keeps
  // what is stored. A field that names WHICH thing is read (a repository) cannot move a source to
  // another: the server refuses that, since it would be a different source.
  const nativeFields = useMemo(
    () => (connector.fields && descriptor && connector.type !== WEB_TYPE ? descriptor.fields : []),
    [connector.fields, descriptor, connector.type],
  )
  const hasNative = nativeFields.length > 0

  // CONNECTION - every type that reads through one (its descriptor's protocol). Moving a source to
  // another of its owner's connections for the same service is a PATCH of `connectionId`; the
  // backend refuses one of another service, or somebody else's. A source that somehow names none
  // shows the default, which is what it reads through.
  const connection = useConnectionChoice(descriptor?.protocol ?? undefined, connector.connectionId, descriptor?.type)
  const hasConnection = !!descriptor?.protocol

  // WEB_PAGE: a crawl saved before it recorded a bound shows the server's default for it
  // (useWebCrawlLimits), the value the crawl has been running with: it said 200 pages here while
  // the server read the missing bound as 100.
  const base = useMemo<Scope>(() => ({
    recursive: connector.recursive ?? true,
    fileTypes: connector.fileTypes ? connector.fileTypes.split(',').filter(Boolean) : [],
    maxDepth: web.maxDepth ?? String(limits.defaultDepth),
    maxPages: web.maxPages ?? String(limits.defaultPages),
    sameDomainOnly: web.sameDomainOnly !== 'false',
    renderJs: web.renderJs === 'true',
    includePattern: web.includePattern ?? '',
    native: initialFieldValues(nativeFields, connector.fields),
    connectionId: connector.connectionId ?? '',
    path: null,
  }), [connector, web, limits.defaultDepth, limits.defaultPages, nativeFields])

  const scope = useAutosave<Scope>(base, async (next, patch) => {
    const update: ConnectorConfigUpdate = {}
    if (connector.type === 'files') {
      // A folder's endpoint has always taken its whole scope.
      update.recursive = next.recursive
      update.fileTypes = next.fileTypes.join(',')
      if (patch.path) update.path = patch.path
    } else if (connector.type === WEB_TYPE) {
      // So has a website's - its bounds checked by WebCrawlFields before they get here.
      update.maxDepth = Number(next.maxDepth)
      update.maxPages = Number(next.maxPages)
      update.sameDomainOnly = next.sameDomainOnly
      update.renderJs = next.renderJs
      update.includePattern = next.includePattern.trim()
    } else {
      // Only what changed: a remote repository or share is the expensive kind to walk again.
      if ('recursive' in patch) update.recursive = next.recursive
      if ('fileTypes' in patch) update.fileTypes = next.fileTypes.join(',')
      if ('native' in patch) Object.assign(update, submittedFieldValues(nativeFields, next.native))
    }
    if ('connectionId' in patch && next.connectionId) update.connectionId = next.connectionId
    await indexingApi.updateConfig(connector.id, update)
    qc.invalidateQueries({ queryKey: keys.connectorSummary() })
    qc.invalidateQueries({ queryKey: keys.connectorsByType() })
    setError('')
    addToast(t('Configuration saved - re-syncing this connector'), 'success')
    // A secret is sent once, with the change that typed it; a later change keeps what is stored.
    const native = { ...next.native }
    for (const f of nativeFields) if (f.type === 'SECRET') native[f.key] = ''
    return { ...next, native, path: null }
  }, {
    failure: t('Failed to save configuration'),
    // The server's sentence - a precise "Crawl depth must be between 0 and 50" from a ProblemDetail's
    // `detail` - and never a machine code or axios's "Request failed with status code 400".
    onError: e => setError(apiErrorText(e) ?? t('Failed to save configuration')),
  })
  const shown = scope.value ?? base
  const saveScope = (patch: Partial<Scope>) => scope.save(patch)

  // THE FILE-TYPE CHIPS settle before they are saved: the clicks are held for a moment, and saved
  // together once they stop - or as the dialog closes.
  const [typesDraft, setTypesDraft] = useState<string[] | null>(null)
  const typesRef = useRef<string[] | null>(null)
  const typesTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const shownRef = useRef(shown)
  shownRef.current = shown
  const saveRef = useRef(saveScope)
  saveRef.current = saveScope
  const flushTypes = useRef(() => {
    if (typesTimer.current) { clearTimeout(typesTimer.current); typesTimer.current = null }
    const draft = typesRef.current
    typesRef.current = null
    if (draft && !sameSet(draft, shownRef.current.fileTypes)) quietly(saveRef.current({ fileTypes: draft }))
  }).current
  const onFileTypes = (next: string[]) => {
    typesRef.current = next
    setTypesDraft(next)
    if (typesTimer.current) clearTimeout(typesTimer.current)
    typesTimer.current = setTimeout(() => { flushTypes(); setTypesDraft(null) }, FILE_TYPES_SETTLE_MS)
  }
  useEffect(() => flushTypes, [flushTypes])
  const fileTypes = typesDraft ?? shown.fileTypes

  // The picker's choice is saved as it is made; a refused move puts the picker back.
  const connectionChoice = {
    ...connection,
    choose: (id: string) => {
      // A deleted connection has no stand-in (ConnectionChoice.missing): a refused move goes back to it.
      const before = connection.missing ? connector.connectionId ?? '' : connection.selected?.id ?? ''
      connection.choose(id)
      if (id && id !== before) saveScope({ connectionId: id }).catch(() => connection.choose(before))
    },
  }

  // KNOWLEDGE GRAPH - every connector type, unlike everything above it. Its own endpoint, not a
  // field on the config PATCH: that one re-syncs the whole connector on every save, and re-indexing
  // a corpus because someone flipped a switch is a bill nobody agreed to. This just flips the flag;
  // the backend's graph worker then extracts what the source already holds, without re-indexing it.
  //
  // A TOGGLE THAT FAILS SILENTLY IS A TOGGLE THAT LIES: a refusal says so, in words of its own -
  // "Failed to save configuration" over a refused graph switch would send the reader to the wrong
  // half of the dialog - and the switch goes back.
  const graphBase = useMemo(() => ({ graphEnabled: connector.graphEnabled ?? false }), [connector.graphEnabled])
  const graph = useAutosave(graphBase, async next => {
    await indexingApi.setGraphEnabled(connector.id, next.graphEnabled)
    qc.invalidateQueries({ queryKey: keys.connectorSummary() })
    qc.invalidateQueries({ queryKey: keys.connectorsByType() })
    setError('')
    return next
  }, {
    failure: t('Failed to change the knowledge-graph setting'),
    onError: e => setError(apiErrorText(e) ?? t('Failed to change the knowledge-graph setting')),
  })
  const graphEnabled = graph.value?.graphEnabled ?? graphBase.graphEnabled
  // Whether the graph was on when the dialog opened - switching it on here starts spending at once.
  const [graphWasOn] = useState(connector.graphEnabled ?? false)

  // THIS SWITCH IS ONE OF FOUR THINGS EXTRACTION NEEDS, and it was the only one the screen
  // mentioned. The others are the deployment-wide switch, a model on the knowledge-graph role,
  // and a re-index of anything already indexed. Turning this on with any of them missing produced
  // an empty graph and no explanation anywhere - found on 2026-09-06 by doing exactly that on a
  // live install. The two settings are read here so the screen can say which one is missing
  // rather than leaving the operator to discover it from a graph that never fills.
  const graphSettings = useSettings('graph')
  const graphGloballyOn = graphSettings.data?.enabled === 'true'
  const graphModelSet = Boolean(graphSettings.data?.provider)

  // Polled while the dialog is open and the graph is on. Five seconds is far finer than the thing
  // it watches - a passage takes about a minute on a local model - but the dialog is open only
  // while somebody is looking at it, and a number that updates late reads as a number that is
  // stuck, which is the whole complaint this answers.
  const graphProgress = useQuery({
    queryKey: keys.graphProgress(connector.id),
    queryFn: () => indexingApi.graphProgress(connector.id),
    enabled: graphEnabled && !graph.busy,
    refetchInterval: 5000,
  })

  const hasScope = connector.type === 'files' || connector.type === 'webpage' || hasFileScope || hasNative
  const title = t('Configure {name}', { name: connector.name })

  return (
    <ModalShell
      onClose={() => { flushTypes(); onClose() }}
      ariaLabel={title}
      // No scrolling inside the dialog (maintainer's rule, 2026-09-27): wider rather than taller.
      // A folder's file-type picker and a website's crawl scope are each most of a screen tall, so
      // for those the body below is two columns - the scope on the left, the rest on the right.
      size={connector.type === 'files' ? 'wide' : hasScope ? 'form' : 'lg'}
    >
      {() => (<>
        <DialogHeader
          icon={<SourceTypeMark type={connector.type} size={20} className="text-brand-legible" />}
          title={title}
          actions={<SaveStatus word={combinedSaveWord(scope.status, graph.status)} />}
        />

        <DialogBody>
          <ScopeColumns on={hasScope}>
            {canRelink && (
              <Field
                className="md:col-start-2"
                label={t('Folder')}
                hint={t('Moved the folder? Choose where it is now.')}
              >
                {/* Not edited here - the button under it is how it changes, and choosing relinks. */}
                <StaticValue className="break-all">{folderPath ?? connector.name}</StaticValue>
                <div className="flex items-center gap-3 mt-2">
                  <Button
                    variant="secondary"
                    loading={browsing}
                    onClick={async () => {
                      setBrowsing(true)
                      try {
                        const p = await relinkFolder.browse()
                        if (p) {
                          await saveScope({ path: p })
                          setFolderPath(p)
                        }
                      } catch {
                        // Said under the settings by the save itself.
                      } finally {
                        setBrowsing(false)
                      }
                    }}
                  >
                    {!browsing && <FolderSearch size={14} />} {t('Relink folder')}
                  </Button>
                  <Presence show={!!relinkFolder.error}><InlineNotice tone="danger">{relinkFolder.error}</InlineNotice></Presence>
                </div>
              </Field>
            )}

            <Presence show={!!hasScope}>
              <InlineNotice tone="info" size="md" className="md:col-start-2">
                {t('Each change syncs it again and removes what is no longer included.')}
              </InlineNotice>
            </Presence>

            {/* The blocks below wait on the type's descriptor, so they arrive rather than appear. */}
            <Presence show={hasNative} className={hasFileScope ? 'md:col-start-2' : 'md:col-start-1 md:row-start-1 md:row-span-5'}>
              <div>
                <FieldSpecForm
                  fields={nativeFields}
                  values={shown.native}
                  onCommit={(key, value) => saveScope({ native: { ...shown.native, [key]: value } })}
                />
              </div>
            </Presence>

            {hasScope && (!hasNative || hasFileScope) && (
              connector.type !== 'webpage' ? (
              <>
                <Presence show={hasRecursive} className="md:col-start-2">
                  <SubfoldersSwitch checked={shown.recursive} onChange={recursive => quietly(saveScope({ recursive }))} />
                  {/* A rule between the switch and the picker only while they are stacked. */}
                  <Divider className="md:hidden mt-4" />
                </Presence>
                <div className="md:col-start-1 md:row-start-1 md:row-span-5">
                  <FileTypeFilter value={fileTypes} onChange={onFileTypes} saved />
                </div>
              </>
            ) : (
              <FormStack className="md:col-start-1 md:row-start-1 md:row-span-5">
                {/* The seed URL is the connector's identity - every indexed page hangs off it - so
                    it is shown for context but never editable here. Pointing at a different site is
                    a different connector. */}
                {web.seedUrl && (
                  <Field label={t('Starting URL')}>
                    <StaticValue className="break-all">{web.seedUrl}</StaticValue>
                  </Field>
                )}

                <WebCrawlFields
                  values={shown}
                  onCommit={patch => saveScope(patch)}
                  limits={limits}
                >
                  <Field label={t('Only follow URLs matching')} hint={t('Optional. Leave empty to follow every in-scope link.')}>
                    <EditableText
                      value={shown.includePattern}
                      label={t('Only follow URLs matching')}
                      placeholder="/docs/"
                      onCommit={includePattern => saveScope({ includePattern })}
                    />
                  </Field>
                </WebCrawlFields>
              </FormStack>
            ))}

            {/* Every type that reads through a connection. Changing it moves the source to another
                server or account of the same service, and the save re-syncs it from there. The
                picker's own "Add a connection" covers a server this user has not set up yet. */}
            <Presence show={hasConnection} className="md:col-start-2">
              <FormStack>
                <Divider />
                <ConnectionPicker choice={connectionChoice} />
              </FormStack>
            </Presence>

            {/* Every connector type. Deliberately the last block and visually separated: it is the
                only setting here that COSTS MONEY, and it is off by default. */}
            {/* THE TITLE AND ITS SWITCH ON ONE ROW, everything else under them at the column's full
                width, since 2026-09-30 (maintainer's feedback). The words used to share the row with
                the switch, which squeezed them into a narrow column, and the progress was three lines
                of monospaced counts; it is a bar now, with one line of figures under it. */}
            <FormStack className="md:col-start-2">
              <Divider />
              <div className="space-y-2">
                {/* The description directly under its title (the field rule of 2026-10-29): the row
                    is the switch's 24px, so the caption needs no margin to sit 4px under the title. */}
                <div>
                  <SwitchRow
                    className="py-0"
                    title={t('Build a knowledge graph')}
                    checked={graphEnabled}
                    onChange={on => quietly(graph.save({ graphEnabled: on }))}
                  />
                  <Caption className="max-w-[60ch]">
                    {t('Maps people, systems and their links. Indexing costs 2 to 3 times more.')}
                  </Caption>
                </div>
                {/* Extraction runs on its own over what is already indexed, so switching it on is
                    enough - and it starts spending at once, which the caption above says before the
                    switch is touched. */}
                <Presence show={graphEnabled && !graphWasOn}>
                  <Caption>
                    {t('Runs in the background.')}
                  </Caption>
                </Presence>
                <Presence show={!!(graphEnabled && !graphSettings.isLoading && !graphGloballyOn)}>
                  <InlineNotice tone="warning">
                    {t('The knowledge graph is off. Turn it on in Settings > Search.')}
                  </InlineNotice>
                </Presence>
                <Presence show={!!(graphEnabled && !graphSettings.isLoading && graphGloballyOn && !graphModelSet)}>
                  <InlineNotice tone="warning">
                    {t('No knowledge graph model is set. Choose one in Settings > Search.')}
                  </InlineNotice>
                </Presence>
                {/* Said while it is on, before the switch is touched: turning it off takes effect at once. */}
                <Presence show={graphEnabled && graphWasOn}>
                  <Caption>
                    {t('Turning this off removes this source from the graph.')}
                  </Caption>
                </Presence>
                {/* TWO ROWS OF ONE SHAPE, since 2026-10-01 (maintainer's feedback): each is a label, its
                    figures with a percentage, and a full-width bar of the same height - so how big the
                    job is and how far it has got read the same way twice. The first counts ITEMS; the
                    second is THE BAR THAT MOVES, the passages of the items open right now: an item is
                    only read once every passage in it is, and a real document holds hundreds at about a
                    minute each, so the first bar stands still for hours. Under both, what the graph
                    holds so far and what failed. */}
                {graphEnabled && connector.graphItemsExtracted !== undefined && (() => {
                  const done = graphProgress.data?.extracted ?? connector.graphItemsExtracted
                  const pending = graphProgress.data?.pending ?? connector.graphItemsPending ?? 0
                  const failed = graphProgress.data?.failed ?? connector.graphItemsFailed ?? 0
                  const total = done + pending + failed
                  const chunksTotal = graphProgress.data?.chunksTotal ?? 0
                  const chunksDone = graphProgress.data?.chunksDone ?? 0
                  const outage = graphProgress.data?.modelUnreachable ?? null
                  return (
                    <div className="pt-3 space-y-3">
                      {/* WHY NOTHING MOVES. The worker stands down for longer each time the model
                          does not answer, and this used to be a log line only. */}
                      <Presence show={!!outage}>
                        {outage && (
                          <InlineNotice tone="warning" icon={AlertTriangle}>
                            <span className="block">
                              {outage.host
                                ? t('The graph model at {host} is not answering. Retrying at {time}.', {
                                    host: outage.host, time: formatDate(outage.retryAt, locale),
                                  })
                                : t('The graph model is not answering. Retrying at {time}.', {
                                    time: formatDate(outage.retryAt, locale),
                                  })}
                            </span>
                            {outage.reason && (
                              <Caption as="span" className="block">{t('Last error: {reason}', { reason: serverText(outage.reason) })}</Caption>
                            )}
                            {outage.throughProxy && (
                              <span className="block">
                                {t('This went through the proxy. Add the host to No proxy if it is local.')}
                              </span>
                            )}
                          </InlineNotice>
                        )}
                      </Presence>
                      <ProgressRow
                        label={t('Items')}
                        figures={t('{done} of {total}', { done: done.toLocaleString(locale), total: total.toLocaleString(locale) })}
                        value={percent(done + failed, total)}
                      />
                      <Presence show={chunksTotal > 0}>
                        <ProgressRow
                          label={t('Passages in the item being read')}
                          figures={t('{done} of {total}', { done: chunksDone.toLocaleString(locale), total: chunksTotal.toLocaleString(locale) })}
                          value={percent(chunksDone, chunksTotal)}
                        />
                      </Presence>
                      <MetaList
                        className={cn(TEXT.caption, 'data-readout')}
                        items={[
                          connector.graphEntities !== undefined && t('{entities} entities, {relations} relationships', {
                            entities: connector.graphEntities.toLocaleString(locale),
                            relations: (connector.graphRelations ?? 0).toLocaleString(locale),
                          }),
                          failed > 0 && <span className="text-danger">{t('{count} item failed|{count} items failed', { count: failed })}</span>,
                        ]}
                      />
                    </div>
                  )
                })()}
              </div>
            </FormStack>

            <Presence show={!!error}>
              <InlineNotice tone="danger" size="md" icon={AlertTriangle}>{error}</InlineNotice>
            </Presence>
          </ScopeColumns>
        </DialogBody>
        {/* No footer: every field is saved on its own, and a dialog closes on its backdrop and Escape. */}
      </>)}
    </ModalShell>
  )
}

// For a source with a scope, the kit's 3:2 columns from `md` with a grid whose last row is 1fr: the
// scope spans every row on the left, and because it spans a flexible track it does not stretch the
// auto rows on the right - so the note, the toggle and the graph block stay packed at the top of
// their column. The spans are this dialog's own, so they ride on the kit grid as classes.
function ScopeColumns({ on, children }: { on: boolean; children: ReactNode }) {
  if (!on) return <FormStack>{children}</FormStack>
  return (
    <FormColumns
      ratio="3:2"
      from="md"
      density="columns"
      className="gap-y-4 md:grid-rows-[auto_auto_auto_auto_1fr] md:grid-flow-row-dense md:items-start"
    >
      {children}
    </FormColumns>
  )
}

// How far along, out of 100 - what the kit's ProgressRow draws; an empty job is at the start.
function percent(value: number, max: number): number {
  return max > 0 ? Math.min(100, (value / max) * 100) : 0
}
