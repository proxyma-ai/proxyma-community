import { useMemo, useState, type ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, FolderSearch } from 'lucide-react'
import {
  Button, Caption, DialogBody, DialogFooter, DialogHeader, Divider, Field, FormColumns, FormStack, InlineNotice, Input,
  MetaList, Presence, ProgressRow, StaticValue, SwitchRow,
} from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import { formatDate } from '@/utils/formatters'
import { useFileUpload } from '@/hooks/useFileUpload'
import { useRelinkFolder } from '../hooks/useRelinkFolder'
import { keys } from '@/services/queryKeys'
import { useToastStore } from '@/store/toastStore'
import { useSettings } from '@/features/settings/hooks/useSettings'
import { useCatalogType } from '../hooks/useConnectorCatalog'
import { FieldSpecForm } from './FieldSpecForm'
import {
  fieldProblems, fieldValuesChanged, initialFieldValues, submittedFieldValues, type FieldProblem,
} from '../utils/fieldForm'
import { useWebCrawlLimits } from '../hooks/useWebCrawlLimits'
import { FileTypeFilter } from './FileTypeFilter'
import { ConnectionPicker, useConnectionChoice } from './ConnectionPicker'
import { SubfoldersSwitch, WebCrawlFields, type WebCrawlValues } from './SourceScopeFields'
import { apiErrorText } from '@/services/apiError'
import { indexingApi, type ConnectorConfigUpdate } from '../services/indexingApi'
import type { ConnectorConfig } from '../types/indexing.types'
import { ModalShell, useFormDirty } from '@/components/ui/ModalShell'
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

export function ConnectorSettingsModal({ connector, onClose }: Props) {
  const qc = useQueryClient()
  const { t } = useTranslation()
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
  const [recursive, setRecursive] = useState(connector.recursive ?? true)
  const [fileTypes, setFileTypes] = useState<string[]>(
    connector.fileTypes ? connector.fileTypes.split(',').filter(Boolean) : [],
  )

  // A LOCAL folder's location. Held until Save, like every other field here: choosing a folder only
  // fills this in, and saving relinks the source to it (see useRelinkFolder).
  const uploadsAreHowFilesArrive = useFileUpload()
  const canRelink = connector.type === 'files' && connector.ownedByMe && !uploadsAreHowFilesArrive
  const relinkFolder = useRelinkFolder(connector)
  // Null until a folder is chosen. The source's current path is never sent to the page (see
  // ConnectorController: an absolute path does not reach a client), so the row shows its name.
  const [folderPath, setFolderPath] = useState<string | null>(null)
  // While the native chooser is on screen - `browse` only asks, so the hook keeps no busy flag.
  const [browsing, setBrowsing] = useState(false)

  // WEB_PAGE. A crawl saved before it recorded a bound shows - and saves - the server's default for
  // it (useWebCrawlLimits), the value the crawl has been running with: it said 200 pages here while
  // the server read the missing bound as 100.
  const [crawlEdits, setCrawlEdits] = useState<Partial<WebCrawlValues>>({})
  const crawl: WebCrawlValues = {
    maxDepth: web.maxDepth ?? String(limits.defaultDepth),
    maxPages: web.maxPages ?? String(limits.defaultPages),
    sameDomainOnly: web.sameDomainOnly !== 'false',
    renderJs: web.renderJs === 'true',
    ...crawlEdits,
  }
  const [includePattern, setIncludePattern] = useState(web.includePattern ?? '')

  // A FRAMEWORK TYPE'S FIELDS - its descriptor's, with the values the source was saved with
  // (`fields`, which only such a type has). A secret is never sent back; one left blank keeps
  // what is stored. A field that names WHICH thing is read (a repository) cannot move a source to
  // another: the server refuses that, since it would be a different source.
  const nativeFields = useMemo(
    () => (connector.fields && descriptor && connector.type !== WEB_TYPE ? descriptor.fields : []),
    [connector.fields, descriptor, connector.type],
  )
  const nativeInitial = useMemo(() => initialFieldValues(nativeFields, connector.fields), [nativeFields, connector.fields])
  const [nativeEdits, setNativeEdits] = useState<Record<string, string>>({})
  const nativeValues = { ...nativeInitial, ...nativeEdits }
  const [nativeProblems, setNativeProblems] = useState<Record<string, FieldProblem>>({})
  const nativeDirty = fieldValuesChanged(nativeFields, nativeInitial, nativeValues)
  const hasNative = nativeFields.length > 0

  // CONNECTION - every type that reads through one (its descriptor's protocol). Moving a source to
  // another of its owner's connections for the same service is a PATCH of `connectionId`; the
  // backend refuses one of another service, or somebody else's. A source that somehow names none
  // starts on the default, and saving then records it.
  const connection = useConnectionChoice(descriptor?.protocol ?? undefined, connector.connectionId, descriptor?.type)
  const hasConnection = !!descriptor?.protocol
  const connectionDirty = hasConnection && !!connection.selected
    && connection.selected.id !== (connector.connectionId ?? '')

  // KNOWLEDGE GRAPH - every connector type, unlike everything above it.
  const [graphEnabled, setGraphEnabled] = useState(connector.graphEnabled ?? false)
  const graphDirty = graphEnabled !== (connector.graphEnabled ?? false)

  // Every field this dialog edits, against the values it opened with - what closing would lose.
  // The connection is compared on its own, against the source's: the picker settles on the
  // default only once the list arrives, which a baseline taken at open would call a change.
  const { dirty: fieldsDirty } = useFormDirty({
    recursive, fileTypes, crawlEdits, includePattern, graphEnabled, folderPath,
  })
  const dirty = fieldsDirty || connectionDirty || nativeDirty

  // THIS TOGGLE IS ONE OF FOUR THINGS EXTRACTION NEEDS, and it was the only one the screen
  // mentioned. The others are the deployment-wide switch, a model on the knowledge-graph role,
  // and a re-index of anything already indexed. Turning this on with any of them missing produced
  // an empty graph and no explanation anywhere - found on 2026-09-06 by doing exactly that on a
  // live install. The two settings are read here so the screen can say which one is missing
  // rather than leaving the operator to discover it from a graph that never fills.
  const graphSettings = useSettings('graph')
  const graphGloballyOn = graphSettings.data?.enabled === 'true'
  const graphModelSet = Boolean(graphSettings.data?.provider)

  const save = useMutation({
    mutationFn: (update: ConnectorConfigUpdate) => indexingApi.updateConfig(connector.id, update),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: keys.connectorSummary() })
      qc.invalidateQueries({ queryKey: keys.connectorsByType() })
      addToast(t('Configuration saved - re-syncing this connector'), 'success')
      // The close moved to submit(), which is the only place that knows whether the OTHER write
      // this dialog makes also succeeded - see its note.
    },
    onError: (e: unknown) => {
      // The server's sentence - a precise "Crawl depth must be between 0 and 50" from a ProblemDetail's
      // `detail` - and never a machine code or axios's "Request failed with status code 400".
      setError(apiErrorText(e) ?? t('Failed to save configuration'))
    },
  })

  // Its own endpoint, not a field on the config PATCH: that one is per-type and re-syncs the
  // whole connector on every save, and re-indexing a corpus because someone ticked a checkbox is
  // a bill nobody agreed to. This just flips the flag; the backend's graph worker then extracts
  // what the source already holds, without re-indexing it.
  // Polled while the dialog is open and the graph is on. Five seconds is far finer than the thing
  // it watches - a passage takes about a minute on a local model - but the dialog is open only
  // while somebody is looking at it, and a number that updates late reads as a number that is
  // stuck, which is the whole complaint this answers.
  const graphProgress = useQuery({
    queryKey: keys.graphProgress(connector.id),
    queryFn: () => indexingApi.graphProgress(connector.id),
    enabled: connector.graphEnabled === true,
    refetchInterval: 5000,
  })

  const saveGraph = useMutation({
    mutationFn: (enabled: boolean) => indexingApi.setGraphEnabled(connector.id, enabled),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: keys.connectorSummary() })
      qc.invalidateQueries({ queryKey: keys.connectorsByType() })
    },
    // A TOGGLE THAT FAILS SILENTLY IS A TOGGLE THAT LIES. This had no onError at all, so a
    // rejected graph change left the switch sitting where the user put it, the modal closing on
    // the scope save's success, and nothing anywhere saying the opt-in had not been recorded -
    // the next sync then extracted nothing and the source's own settings agreed it should have.
    //
    // Its own message, not the shared one: the two saves are independent requests to different
    // endpoints and either can fail on its own, so "Failed to save configuration" over a refused
    // graph toggle would send the reader to the wrong half of the dialog.
    onError: (e: unknown) => {
      setError(apiErrorText(e) ?? t('Failed to change the knowledge-graph setting'))
    },
  })

  // Only when it changed: every save of this PATCH re-syncs the whole source.
  const connectionUpdate = (): ConnectorConfigUpdate =>
    connectionDirty ? { connectionId: connection.selected!.id } : {}

  /**
   * The two writes this dialog can make, in order, with ONE outcome between them.
   *
   * <p>They used to be fired side by side and neither waited on: the graph PATCH went first and
   * the scope PATCH went after it, and the modal closed on whichever answered. That was wrong in
   * three ways at once, and all three were visible the day the backend started refusing a file
   * source's scope save. The refusal was reported over a graph toggle that had SUCCEEDED, so
   * turning the knowledge graph on looked like the thing that had been rejected. A graph toggle
   * that failed on its own said nothing at all, because the close had already happened. And the
   * crawl-bound validation below ran after the graph flag had been written, so a rejected depth
   * still changed the other setting.
   *
   * <p>Validating first and awaiting each write in turn answers all three: nothing is written
   * until the form is valid, a failure stops the sequence with its own message on screen, and the
   * dialog closes only when everything it was asked to do actually happened.
   */
  const submit = async () => {
    setError('')

    // What the scope half of this dialog would post, or null when this type has no editable scope
    // and its connection did not change. Built BEFORE anything is written.
    let scopeUpdate: ConnectorConfigUpdate | null = null
    if (connector.type === 'files') {
      scopeUpdate = { recursive, fileTypes: fileTypes.join(',') }
      if (canRelink && folderPath) scopeUpdate.path = folderPath
    } else if (connector.type === 'webpage') {
      const depth = Number(crawl.maxDepth)
      const pages = Number(crawl.maxPages)
      if (!Number.isInteger(depth) || depth < 0 || depth > limits.maxDepth) {
        setError(t('Crawl depth must be a whole number between 0 and {max}.', { max: limits.maxDepth }))
        return
      }
      if (!Number.isInteger(pages) || pages < 1 || pages > limits.maxPages) {
        setError(t('Page limit must be a whole number between 1 and {max}.', { max: limits.maxPages }))
        return
      }
      scopeUpdate = {
        maxDepth: depth,
        maxPages: pages,
        sameDomainOnly: crawl.sameDomainOnly,
        renderJs: crawl.renderJs,
        includePattern: includePattern.trim(),
      }
    } else if (hasFileScope) {
      // Only what changed, unlike a folder's dialog above: every save of this PATCH re-syncs the
      // whole source, and a remote repository or share is the expensive kind to walk again. SVN
      // and SharePoint read through a connection, so a move rides along in the same request.
      const update: ConnectorConfigUpdate = connectionUpdate()
      if (hasRecursive && recursive !== (connector.recursive ?? true)) update.recursive = recursive
      if (fileTypes.join(',') !== (connector.fileTypes ?? '')) update.fileTypes = fileTypes.join(',')
      if (hasNative && nativeDirty) {
        const found = fieldProblems(nativeFields, nativeValues, nativeFields.filter(f => f.type === 'SECRET').map(f => f.key))
        setNativeProblems(found)
        if (Object.keys(found).length > 0) return
        Object.assign(update, submittedFieldValues(nativeFields, nativeValues))
      }
      scopeUpdate = Object.keys(update).length > 0 ? update : null
    } else if (hasNative) {
      // Checked here first, as the add dialog checks them; the server checks again.
      const found = fieldProblems(nativeFields, nativeValues, nativeFields.filter(f => f.type === 'SECRET').map(f => f.key))
      setNativeProblems(found)
      if (Object.keys(found).length > 0) return
      const update: ConnectorConfigUpdate = connectionUpdate()
      if (nativeDirty) Object.assign(update, submittedFieldValues(nativeFields, nativeValues))
      scopeUpdate = Object.keys(update).length > 0 ? update : null
    } else if (connectionDirty) {
      // Connection-only types reach the same PATCH, which is why its backend no longer refuses a
      // type with no editable scope outright - see ConnectorService.updateConnectorConfig.
      scopeUpdate = connectionUpdate()
    }

    try {
      // Graph first, because it is the one every connector type has and the cheap one: it flips a
      // flag, where the scope save re-syncs the whole source.
      if (graphDirty) await saveGraph.mutateAsync(graphEnabled)
      if (scopeUpdate) await save.mutateAsync(scopeUpdate)
    } catch {
      // Each mutation's own onError has already put its own message on screen - which one failed
      // is the thing the reader needs and the thing this used to get wrong. Nothing to add here,
      // and the dialog deliberately stays open.
      return
    }
    onClose()
  }

  const hasScope = connector.type === 'files' || connector.type === 'webpage' || hasFileScope || hasNative
  const busy = save.isPending || saveGraph.isPending
  const title = t('Configure {name}', { name: connector.name })

  return (
    <ModalShell
      onClose={onClose}
      closable={!busy}
      dirty={dirty}
      ariaLabel={title}
      // No scrolling inside the dialog (maintainer's rule, 2026-09-27): wider rather than taller.
      // A folder's file-type picker and a website's crawl scope are each most of a screen tall, so
      // for those the body below is two columns - the scope on the left, the rest on the right.
      size={connector.type === 'files' ? 'wide' : hasScope ? 'form' : 'lg'}
    >
      {requestClose => (<>
        <DialogHeader
          icon={<SourceTypeMark type={connector.type} size={20} className="text-brand-legible" />}
          title={title}
        />

        <DialogBody>
          <ScopeColumns on={hasScope}>
            {canRelink && (
              <Field
                className="md:col-start-2"
                label={t('Folder')}
                hint={t('Moved or renamed the folder? Choose where it is now. The source keeps its settings and is indexed again from there.')}
              >
                {/* Not edited here - the button under it is how it changes. */}
                <StaticValue className="break-all">{folderPath ?? connector.name}</StaticValue>
                <div className="flex items-center gap-3 mt-2">
                  <Button
                    variant="secondary"
                    loading={browsing}
                    onClick={async () => {
                      setBrowsing(true)
                      try {
                        const p = await relinkFolder.browse()
                        if (p) setFolderPath(p)
                      } finally {
                        setBrowsing(false)
                      }
                    }}
                    disabled={save.isPending}
                  >
                    {!browsing && <FolderSearch size={14} />} {t('Relink folder')}
                  </Button>
                  <Presence show={!!relinkFolder.error}><InlineNotice tone="danger">{relinkFolder.error}</InlineNotice></Presence>
                </div>
              </Field>
            )}

            <Presence show={!!hasScope}>
              <InlineNotice tone="info" size="md" className="md:col-start-2">
                {t('Saving re-syncs this connector in full and drops anything the new scope excludes.')}
              </InlineNotice>
            </Presence>

            {/* The blocks below wait on the type's descriptor, so they arrive rather than appear. */}
            <Presence show={hasNative} className={hasFileScope ? 'md:col-start-2' : 'md:col-start-1 md:row-start-1 md:row-span-5'}>
              <div>
                <FieldSpecForm
                  fields={nativeFields}
                  values={nativeValues}
                  onChange={(key, value) => {
                    setNativeEdits(e => ({ ...e, [key]: value }))
                    setNativeProblems(p => {
                      if (!(key in p)) return p
                      const next = { ...p }
                      delete next[key]
                      return next
                    })
                    setError('')
                  }}
                  problems={nativeProblems}
                  disabled={save.isPending}
                />
              </div>
            </Presence>

            {hasScope && (!hasNative || hasFileScope) && (
              connector.type !== 'webpage' ? (
              <>
                <Presence show={hasRecursive} className="md:col-start-2">
                  <SubfoldersSwitch checked={recursive} onChange={setRecursive} disabled={save.isPending} />
                  {/* A rule between the switch and the picker only while they are stacked. */}
                  <Divider className="md:hidden mt-4" />
                </Presence>
                <div className="md:col-start-1 md:row-start-1 md:row-span-5">
                  <FileTypeFilter value={fileTypes} onChange={setFileTypes} disabled={save.isPending} />
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
                  values={crawl}
                  onChange={patch => { setCrawlEdits(c => ({ ...c, ...patch })); setError('') }}
                  limits={limits}
                  disabled={save.isPending}
                >
                  <Input
                    label={t('Only follow URLs matching')}
                    placeholder="/docs/"
                    value={includePattern}
                    onChange={e => setIncludePattern(e.target.value)}
                    disabled={save.isPending}
                    hint={t('Optional. Leave empty to follow every in-scope link.')}
                  />
                </WebCrawlFields>
              </FormStack>
            ))}

            {/* Every type that reads through a connection. Changing it moves the source to another
                server or account of the same service, and the save re-syncs it from there. The
                picker's own "Add a connection" covers a server this user has not set up yet. */}
            <Presence show={hasConnection} className="md:col-start-2">
              <FormStack>
                <Divider />
                <ConnectionPicker choice={connection} disabled={save.isPending} />
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
                    onChange={setGraphEnabled}
                    disabled={busy}
                  />
                  <Caption className="max-w-[60ch]">
                    {t('Maps people, systems and how they connect. One AI call per passage, so indexing costs two to three times as much.')}
                  </Caption>
                </div>
                {/* Said "re-index this source to extract from what it already holds" until
                    2026-09-15, because extraction only ran inside indexing. It now runs on its own
                    over what is already indexed, so switching it on is enough - and since that
                    starts spending at once, this sentence is where the consent lives. */}
                <Presence show={graphEnabled && !connector.graphEnabled}>
                  <Caption>
                    {t('Runs in the background after you save, over what is already indexed. Search is unaffected.')}
                  </Caption>
                </Presence>
                <Presence show={!!(graphEnabled && !graphSettings.isLoading && !graphGloballyOn)}>
                  <InlineNotice tone="warning">
                    {t('Knowledge-graph extraction is off for this deployment. Turn it on under Settings > Search.')}
                  </InlineNotice>
                </Presence>
                <Presence show={!!(graphEnabled && !graphSettings.isLoading && graphGloballyOn && !graphModelSet)}>
                  <InlineNotice tone="warning">
                    {t('No model is set for knowledge-graph extraction, so nothing will be extracted. Choose one under Settings > Search.')}
                  </InlineNotice>
                </Presence>
                <Presence show={!graphEnabled && !!connector.graphEnabled}>
                  <InlineNotice tone="warning">
                    {t('Turning this off deletes what this source has contributed to the graph. Its index and search are not affected.')}
                  </InlineNotice>
                </Presence>
                {/* TWO ROWS OF ONE SHAPE, since 2026-10-01 (maintainer's feedback): each is a label, its
                    figures with a percentage, and a full-width bar of the same height - so how big the
                    job is and how far it has got read the same way twice. The first counts ITEMS; the
                    second is THE BAR THAT MOVES, the passages of the items open right now: an item is
                    only read once every passage in it is, and a real document holds hundreds at about a
                    minute each, so the first bar stands still for hours. Under both, what the graph
                    holds so far and what failed. */}
                {connector.graphEnabled && connector.graphItemsExtracted !== undefined && (() => {
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
                                ? t('The knowledge-graph model at {host} is not answering. It is asked again at {time}.', {
                                    host: outage.host, time: formatDate(outage.retryAt),
                                  })
                                : t('The knowledge-graph model is not answering. It is asked again at {time}.', {
                                    time: formatDate(outage.retryAt),
                                  })}
                            </span>
                            {outage.reason && (
                              <span className="block text-xs text-ink-caption">{t('Last error: {reason}', { reason: outage.reason })}</span>
                            )}
                            {outage.throughProxy && (
                              <span className="block">
                                {t('The request went through the proxy - add the host to No proxy if it is on your network.')}
                              </span>
                            )}
                          </InlineNotice>
                        )}
                      </Presence>
                      <ProgressRow
                        label={t('Items')}
                        figures={t('{done} of {total}', { done: done.toLocaleString(), total: total.toLocaleString() })}
                        value={percent(done + failed, total)}
                      />
                      <Presence show={chunksTotal > 0}>
                        <ProgressRow
                          label={t('Passages in the item being read')}
                          figures={t('{done} of {total}', { done: chunksDone.toLocaleString(), total: chunksTotal.toLocaleString() })}
                          value={percent(chunksDone, chunksTotal)}
                        />
                      </Presence>
                      <MetaList
                        className="text-xs text-ink-caption data-readout"
                        items={[
                          connector.graphEntities !== undefined && t('{entities} entities, {relations} relationships', {
                            entities: connector.graphEntities.toLocaleString(),
                            relations: (connector.graphRelations ?? 0).toLocaleString(),
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

        {/* BOTH mutations gate both buttons, not just the scope save. Now that submit() runs them
            in sequence there is a window where the graph PATCH is in flight and the scope one has
            not started, and a button disabled on `save.isPending` alone is live through all of it -
            so a second click would re-enter submit() and write the flag twice. The label already
            read both; only the `disabled` was half the condition. */}
        <DialogFooter>
          <Button variant="secondary" onClick={requestClose} disabled={busy}>
            {t('Cancel')}
          </Button>
          <Button onClick={submit} loading={busy}>
            {busy
              ? t('Saving...')
              : hasScope ? t('Save and re-sync') : t('Save')}
          </Button>
        </DialogFooter>
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
