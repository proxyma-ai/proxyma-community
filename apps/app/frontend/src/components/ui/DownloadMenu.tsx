import { useState } from 'react'
import { Download, FileJson, FileSpreadsheet } from 'lucide-react'
import { Button, MenuRow, Popover } from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import { withReason } from '@/services/apiError'
import { useToastStore } from '@/store/toastStore'
import type { LogDownload } from '@/utils/downloadFile'

/**
 * Download, for a log: the entries under the reader's current filters and order, as CSV for a
 * spreadsheet or JSON for a program. The server stops at its row limit and says so; the page says
 * it again, with what to do about it.
 */
export function DownloadMenu({ onDownload }: { onDownload: (format: 'csv' | 'json') => Promise<LogDownload> }) {
  const { t } = useTranslation()
  const addToast = useToastStore(s => s.addToast)
  const [busy, setBusy] = useState(false)
  const run = async (format: 'csv' | 'json') => {
    setBusy(true)
    try {
      const { truncated } = await onDownload(format)
      addToast(truncated
        ? t('Downloaded the newest 10,000 entries. Choose a shorter period or a filter for the rest.')
        : t('Log downloaded'), truncated ? 'info' : 'success')
    } catch (e) {
      addToast(withReason(t('Could not download the log'), e), 'danger')
    } finally {
      setBusy(false)
    }
  }
  return (
    <Popover
      placement="bottom-end"
      ariaLabel={t('Download')}
      trigger={({ open, toggle, ref }) => (
        // The kit Button takes the anchor's ref, and its `loading` is the spinner in the glyph's place.
        <Button
          ref={ref}
          variant="secondary"
          aria-haspopup="menu"
          aria-expanded={open}
          loading={busy}
          onClick={toggle}
        >
          {!busy && <Download size={14} aria-hidden="true" />}
          {t('Download')}
        </Button>
      )}
    >
      {close => (
        <>
          <MenuRow
            icon={<FileSpreadsheet size={16} aria-hidden="true" />}
            title={t('CSV')}
            description={t('Opens in a spreadsheet')}
            onSelect={() => { close(); void run('csv') }}
          />
          <MenuRow
            icon={<FileJson size={16} aria-hidden="true" />}
            title={t('JSON')}
            description={t('Every field, for another program')}
            onSelect={() => { close(); void run('json') }}
          />
        </>
      )}
    </Popover>
  )
}
