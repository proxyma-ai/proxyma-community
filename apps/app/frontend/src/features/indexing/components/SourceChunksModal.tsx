import { useState } from 'react'
import { DialogBody, DialogHeader, Disclosure, EmptyState, Readout, Skeleton, TEXT, cn } from '@/components/ui/kit'
import { ListPager } from '@/components/ui/ListPager'
import { useConnectorItemsPage } from '../hooks/useConnectors'
import type { ConnectorConfig, TrackedItem } from '../types/indexing.types'
import { useTranslation } from '@/i18n'
import { ModalShell } from '@/components/ui/ModalShell'
import { DocumentChunks } from './DocumentChunks'

interface Props {
  connector: ConnectorConfig
  onClose: () => void
}

// Every indexed chunk in a source, one item at a time.
//
// A LIST OF ITEMS THAT EXPAND, NOT ONE BULK FETCH. "All chunks in this source" sounds like one
// request and must not be one: a folder of two hundred documents is tens of thousands of chunks
// and tens of megabytes of text, which is a response nobody can read, a modal that takes seconds
// to open, and a payload the browser holds for as long as it is up. Paging the ITEMS - which the
// detail modal already does - and fetching one item's chunks when its row is opened costs a small
// request per row a reader actually asked to see, and needs no new endpoint at all.
export function SourceChunksModal({ connector, onClose }: Props) {
  const { t } = useTranslation()
  // Twenty items a page, Previous and Next (a keyset list - see useConnectorItemsPage).
  const { data, isLoading, page, hasMore, setPage } = useConnectorItemsPage(connector.id)

  const items = data?.items ?? []

  return (
    <ModalShell onClose={onClose} size="xl" fill ariaLabel={connector.name}>
      <DialogHeader
        title={connector.name}
        subtitle={t('Every indexed chunk in this source. Open an item to read what it was split into.')}
      />
      <DialogBody scroll flush>
        {isLoading ? (
          // Rows shaped like the item rows that are coming: a name each, on the rows' own padding.
          <div className="divide-y divide-line/20" aria-hidden="true">
            {Array.from({ length: 8 }, (_, i) => (
              <div key={i} className="px-4 sm:px-6 py-3"><Skeleton className="h-3.5 w-2/5" /></div>
            ))}
          </div>
        ) : items.length === 0 ? (
          <EmptyState className="px-4 sm:px-6">{t('No items yet')}</EmptyState>
        ) : (
          <div className="divide-y divide-line/20 animate-fade-in">
            {items.map(item => (
              <ItemChunks key={item.id} connectorId={connector.id} item={item} />
            ))}
            <ListPager className="px-4 sm:px-6" page={page} pages={null} hasMore={hasMore} onPage={setPage} />
          </div>
        )}
      </DialogBody>
    </ModalShell>
  )
}

// One collapsed item. `enabled: open` in DocumentChunks is what keeps this modal cheap - a closed
// row has issued no request, so opening the modal on a large source costs exactly one page of item
// metadata.
function ItemChunks({ connectorId, item }: { connectorId: string; item: TrackedItem }) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)

  return (
    <Disclosure
      className="px-4 sm:px-6 py-3"
      open={open}
      onToggle={setOpen}
      summary={<span className={cn(TEXT.label, 'block truncate')}>{item.title}</span>}
      meta={(item.currentChunks ?? 0) > 0 && (
        <Readout className="animate-fade-in">{t('{count} chunk|{count} chunks', { count: item.currentChunks ?? 0 })}</Readout>
      )}
    >
      <DocumentChunks connectorId={connectorId} documentId={item.id} enabled={open} />
    </Disclosure>
  )
}
