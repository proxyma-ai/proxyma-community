import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import {
  Caption, Card, EmptyState, FadeSwap, InlineNotice, LoadingState, MetaList, Presence, TEXT, Title, cn,
} from '@/components/ui/kit'
import { useDebounce } from '@/hooks/useDebounce'
import { indexingApi } from '../services/indexingApi'
import { keys } from '@/services/queryKeys'
import { GraphCanvas, DEFAULT_VIEW, type GraphCanvasHandle, type GraphView } from './GraphCanvas'
import { GraphViewOptions } from './GraphViewOptions'
import type { GraphNode } from '../types/indexing.types'
import { useTranslation } from '@/i18n'
import { ModalShell } from '@/components/ui/ModalShell'
import { withReason } from '@/services/apiError'

interface Props {
  connectorId: string
  /** Omitted for the whole-source view. Present for one item's own graph. */
  documentId?: string
  title: string
  onClose: () => void
}

/**
 * Nodes per page.
 *
 * <p>Big enough that the first page is already a graph rather than a handful of dots, small enough
 * that it arrives quickly on a source with thousands of entities. The layout re-settles on each
 * page, so a very small page would also mean re-heating the simulation many times over.
 */
const PAGE_SIZE = 250

/**
 * The FIRST page is smaller, since 2026-09-24: it is the one the reader waits on with an empty
 * canvas, and a hundred of the most connected nodes is already the shape of the graph. The pages
 * after it are cheap on the server - they reuse the first page's ranking (see
 * KnowledgeGraphService.ranked) - and the canvas reveals each one a few stars a frame, so the
 * field keeps filling while the rest arrives.
 */
const FIRST_PAGE_SIZE = 100

// The knowledge graph for one item or one whole source.
//
// ONE COMPONENT FOR BOTH SCOPES, chosen by whether documentId is passed. The two endpoints return
// the same shape and the picture means the same thing at either scale; splitting it would leave
// two canvases to keep in step for the sake of one query key.
//
// THE WHOLE VIEWPORT, as of 2026-09-12. It was max-w-5xl and min(760px, 88dvh) - a dialog-sized
// box for the one thing in this product that is worse at every size below the one it fits in. A
// force layout in a 1024px box is a hairball; the same layout with the screen to spread into is
// a map. Everything that used to sit beside the graph is now ON it: the heading floats over the
// top edge, the controls over the left, the detail over the right, so none of them costs the
// picture any width.
//
// It is inset rather than edge-to-edge - a margin of backdrop and a radius - because
// a full-bleed surface with no visible boundary reads as a new PAGE, and this is a thing you
// opened over the page you were on and will close again. The inset is also how it closes: there is no close button (no
// dialog has one), so the backdrop round it is what a tap or click goes to, beside Escape.
export function KnowledgeGraphModal({ connectorId, documentId, title, onClose }: Props) {
  const { t } = useTranslation()
  // TWO PIECES OF STATE, NOT ONE, and this is the centre of what changed.
  //
  // `selected` is a CLICK and it holds. `hovered` is a preview and it is gone the moment the
  // pointer leaves. There used to be one `selected` driven by hover, so the detail panel followed
  // the cursor and let go as soon as you moved toward it - you could see a node's type and its
  // counts, and you could not read them, because reading meant moving the mouse.
  //
  // Holding the selection is also what makes the graph walkable: click a node, see what it
  // connects to, click one of those. That is the "view the tree" behaviour, and it is a property
  // of selection persisting rather than a separate mode.
  const [selected, setSelected] = useState<GraphNode | null>(null)
  const [hovered, setHovered] = useState<GraphNode | null>(null)
  const [view, setView] = useState<GraphView>(DEFAULT_VIEW)
  const controls = useRef<GraphCanvasHandle | null>(null)

  // ONE PAGE AT A TIME, MOST IMPORTANT FIRST, and drawn as it arrives.
  //
  // A source with a thousand entities used to be one long request that showed a spinner until the
  // very last leaf node had been fetched - and the nodes that arrived last were the ones that
  // matter least, so a reader was waiting on the dust to see the hubs. The view is ranked by
  // connectedness on the server, so the first page IS the picture and every page after it fills
  // in detail. The canvas keeps the stars it has already placed - see the layout effect in
  // GraphCanvas - so the graph grows rather than restarting on each page.
  // DEBOUNCED, because the star limit is a SLIDER. Dragging it from 100 to 1500 passes through
  // fourteen values, and each one is a different query key - so without this, one drag would start
  // fourteen graph queries and draw whichever finished last.
  const cap = useDebounce(view.maxNodes, 250)

  const graph = useInfiniteQuery({
    queryKey: keys.connectorGraph(connectorId, documentId, cap),
    initialPageParam: 0,
    queryFn: ({ pageParam }) => {
      const window = { cap, offset: pageParam, limit: pageParam === 0 ? FIRST_PAGE_SIZE : PAGE_SIZE }
      return documentId
        ? indexingApi.documentGraph(connectorId, documentId, window)
        : indexingApi.connectorGraph(connectorId, window)
    },
    getNextPageParam: (last, _pages, lastOffset) =>
      last.hasMore ? lastOffset + (lastOffset === 0 ? FIRST_PAGE_SIZE : PAGE_SIZE) : undefined,
    // Extraction is not running while this modal is open, so the graph cannot change underneath
    // it. Refetching on every focus would re-run the layout and throw away wherever the reader
    // had panned to.
    staleTime: 5 * 60_000,
    refetchOnWindowFocus: false,
  })

  // The pages are fetched back to back rather than on a scroll, because there is nothing to
  // scroll - the reader is looking at the picture and the rest of it should simply turn up.
  const { hasNextPage, isFetchingNextPage, fetchNextPage } = graph
  useEffect(() => {
    if (hasNextPage && !isFetchingNextPage) void fetchNextPage()
  }, [hasNextPage, isFetchingNextPage, fetchNextPage, graph.data?.pages.length])

  const pages = graph.data?.pages
  const nodes = useMemo(() => pages?.flatMap(p => p.nodes) ?? [], [pages])
  const edges = useMemo(() => pages?.flatMap(p => p.edges) ?? [], [pages])
  const first = pages?.[0]

  const onSelect = useCallback((node: GraphNode | null) => setSelected(node), [])
  const onHover = useCallback((node: GraphNode | null) => setHovered(node), [])
  const onReady = useCallback((handle: GraphCanvasHandle) => { controls.current = handle }, [])

  // What the panel describes: whatever the pointer is over, falling back to what is selected. The
  // fallback is the point - move off a node and the panel keeps showing the one you CHOSE, rather
  // than emptying and taking the reason you opened it with it.
  const described = hovered ?? selected
  // The dialog is named by its floating heading - the one title it has.
  const titleId = useId()
  const state = graph.isPending ? 'loading' : graph.isError ? 'error' : nodes.length === 0 ? 'empty' : 'graph'

  return (
    <ModalShell
      onClose={onClose}
      // The kit's `viewport` panel: the whole window but a margin - see the note above on why this
      // stays a thing opened OVER something, with a strip of backdrop to tap on every side.
      size="viewport"
      panelClassName="overflow-hidden"
      ariaLabelledBy={titleId}
    >
        {/* Loading, a failure, nothing yet and the graph itself fade from one to the next. */}
        <FadeSwap swapKey={state} className="absolute inset-0">
          {graph.isPending ? (
            <LoadingState centered />
          ) : graph.isError ? (
            <div className="absolute inset-0 flex items-center justify-center px-8 animate-fade-in">
              <InlineNotice tone="danger" size="md">{withReason(t('Failed to load the graph'), graph.error)}</InlineNotice>
            </div>
          ) : nodes.length === 0 ? (
            // Two different reasons land here and the copy says neither, deliberately: this modal
            // cannot tell "the graph is off for this source" from "it is on and found nothing".
            // Both are answered by the same sentence and the same place to go and look. The box is
            // centred on the empty canvas; the sentence in it is the kit's left-aligned one.
            <div className="absolute inset-0 flex items-center justify-center px-8">
              <EmptyState title={t('Nothing in the knowledge graph for this yet.')}>
                {t('Turn on "Build a knowledge graph" in this source\'s settings. Items already indexed are extracted in the background.')}
              </EmptyState>
            </div>
          ) : (
            <>
              <GraphCanvas
                label={t('Knowledge graph: {entities} entities, {relations} relationships', {
                  entities: nodes.length.toLocaleString(), relations: edges.length.toLocaleString(),
                })}
                nodes={nodes}
                edges={edges}
                view={view}
                selected={selected}
                onSelect={onSelect}
                onHover={onHover}
                onReady={onReady}
              />

              <GraphViewOptions
                view={view}
                onChange={setView}
                onReset={() => controls.current?.resetView()}
                onCenter={() => controls.current?.centerView()}
                hasSelection={!!selected}
              />

              {/* WHAT THE PICTURE MEANS, in one line. The canvas is monochrome - see GraphCanvas -
                  so there are no colours to label, and the thing worth saying is the rule the eye
                  is already following. */}
              <Caption className="absolute left-3 bottom-3 pointer-events-none">
                {t('Bigger and brighter means more connections')}
              </Caption>

              {/* Bottom-right, and pointer-events-none so it can never eat a drag that passes
                  beneath it. It describes the HOVER if there is one and the SELECTION otherwise -
                  see `described` above. */}
              <Presence show={!!described} collapse={false}>
                {described && (
                <Card variant="solid" padding="sm" className="absolute right-3 bottom-3 max-w-[45%] pointer-events-none">
                  <p className={cn(TEXT.label, 'truncate')}>{described.name}</p>
                  <MetaList
                    className="mt-0.5 text-xs text-ink-caption"
                    items={[
                      described.type ?? t('Unclassified'),
                      t('{count} mention|{count} mentions', { count: described.mentions }),
                      t('{count} link|{count} links', { count: described.degree }),
                    ]}
                  />
                  {/* Only on the node being HELD, and only while it is the one being described -
                      the way out of a selection, stated once, where the selection is. */}
                  <Presence show={!!selected && described.id === selected.id}>
                    <Caption className="mt-1">
                      {t('Click another node to follow it, or the background to clear')}
                    </Caption>
                  </Presence>
                </Card>
                )}
              </Presence>
            </>
          )}
        </FadeSwap>

        {/* THE HEADING FLOATS, and does not reserve a row. A fixed header would take 76px off the
            top of the one surface in this product that wants the whole box. It is
            pointer-events-none so a drag beginning over the title still rotates the graph - there
            is nothing in it to press. */}
        <div className="absolute left-3 right-3 top-3 flex items-start gap-3 pointer-events-none">
          <Card variant="solid" padding="sm" className="min-w-0 ml-auto max-w-[min(32rem,60%)] order-2 text-right">
            <Title id={titleId} className="truncate">{title}</Title>
            <Caption className="mt-0.5">
              {documentId
                ? t('What this item put into the knowledge graph.')
                : t('Every item in this source, in one graph. A thing two files both mention is one node here.')}
              {first && !graph.isPending && nodes.length > 0 && (
                <> {' '}{t('{entities} entities, {relations} relationships.', {
                  entities: String(first.totalNodes), relations: String(first.totalEdges),
                })}</>
              )}
            </Caption>
            {/* WHAT IS STILL COMING, while it is coming. Without it a graph that is still filling
                in looks like a graph that is finished and smaller than the count above it - the
                reader would read the difference as data that failed to arrive. */}
            <Presence show={(isFetchingNextPage || hasNextPage) && nodes.length > 0}>
              <Caption className="mt-1">
                {t('Drawing {done} of {total}...', {
                  done: String(nodes.length),
                  total: String(Math.min(cap, Number(first?.totalNodes ?? 0))),
                })}
              </Caption>
            </Presence>
            <Presence show={!!(first?.truncated && !hasNextPage && !isFetchingNextPage)}>
              <Caption tone="warning" className="mt-1">
                {t('Showing the {count} most connected - raise the star limit to see more.', {
                  count: String(nodes.length),
                })}
              </Caption>
            </Presence>
            <Caption className="mt-1">
              {t('Drag to rotate, scroll to zoom, shift-drag to pan')}
            </Caption>
          </Card>
        </div>
    </ModalShell>
  )
}
