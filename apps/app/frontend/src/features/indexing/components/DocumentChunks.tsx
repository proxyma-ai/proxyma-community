import { useQuery } from '@tanstack/react-query'
import { DialogBody, DialogHeader, EmptyState, Eyebrow, InlineNotice, LIST_PAGE_SIZE, LoadingState, TEXT, cn, usePagedList } from '@/components/ui/kit'
import { ListPager } from '@/components/ui/ListPager'
import { indexingApi } from '../services/indexingApi'
import { keys } from '@/services/queryKeys'
import { useTranslation } from '@/i18n'
import { ModalShell } from '@/components/ui/ModalShell'
import { withReason } from '@/services/apiError'

/**
 * What one indexed document was split into - the Sources page's item preview, read from
 * GET /api/v1/indexing/{connectorId}/items/{documentId}/preview. ONE rendering of it, used by the
 * source's chunk list and by anything else that opens a single document in place (a key account's
 * citation of an indexed file, which has no web address to follow).
 *
 * The endpoint applies the source's own read rule - its owner, or anyone when the source is
 * shared - so a reader who may not read the source gets the refusal, shown as the failure line.
 * `enabled` lets a collapsed row issue no request until it is opened.
 */
export function DocumentChunks({ connectorId, documentId, enabled = true }: {
  connectorId: string
  documentId: string
  enabled?: boolean
}) {
  const { t } = useTranslation()
  const chunks = useQuery({
    queryKey: keys.documentChunks(connectorId, documentId),
    queryFn: () => indexingApi.previewDocument(connectorId, documentId),
    enabled,
    staleTime: 5 * 60_000,
  })
  // Twenty chunks a page (LIST_PAGE_SIZE): a long document is hundreds of them.
  const { page, pages, setPage, pageItems } = usePagedList(chunks.data?.chunks ?? [], documentId)

  if (chunks.isPending) {
    return <LoadingState className="py-3" />
  }
  if (chunks.isError) {
    return <InlineNotice tone="danger" className="py-2">{withReason(t('Failed to load preview'), chunks.error)}</InlineNotice>
  }
  if ((chunks.data?.chunks.length ?? 0) === 0) {
    return <EmptyState className="py-2">{t('No content indexed for this item yet')}</EmptyState>
  }
  return (
    <div className="space-y-2 animate-fade-in">
      {pageItems.map((chunk, i) => {
        const number = page * LIST_PAGE_SIZE + i + 1
        return (
          <div key={number} className="animate-fade-in">
            <Eyebrow className="mb-0.5">{t('Chunk {number}', { number: String(number) })}</Eyebrow>
            <p className={cn(TEXT.body, 'whitespace-pre-wrap break-words')}>{chunk}</p>
          </div>
        )
      })}
      <ListPager page={page} pages={pages} onPage={setPage} />
    </div>
  )
}

/** One indexed document opened in a dialog: its title, and what it was split into. */
export function DocumentPreviewDialog({ connectorId, documentId, title, onClose }: {
  connectorId: string
  documentId: string
  title: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  return (
    <ModalShell onClose={onClose} size="xl" fill ariaLabel={title}>
      <DialogHeader title={title} subtitle={t('The indexed text of this document, as it was split for search.')} />
      <DialogBody scroll>
        <DocumentChunks connectorId={connectorId} documentId={documentId} />
      </DialogBody>
    </ModalShell>
  )
}
