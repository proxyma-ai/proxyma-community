import { useState } from 'react'
import { FileJson, FileSpreadsheet, Rows3 } from 'lucide-react'
import { Button, MenuRow, Popover } from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import { withReason } from '@/services/apiError'
import { useToastStore } from '@/store/toastStore'
import type { LogDownload } from '@/utils/downloadFile'

/**
 * Download, for a log: the entries under the reader's current filters and order, as CSV for a
 * spreadsheet or JSON for a program. The server stops at its row limit and says so; the page says
 * it again, with what to do about it.
 *
 * A TEXT BUTTON, "Download log", drawn as "Clear log" is (the maintainer, 2026-11-08: "the download
 * log should be similar to the clear log button") - the quiet variant where Clear log is the
 * danger-quiet one, no box and no glyph; it was a bordered secondary button with a download glyph.
 */
export function DownloadMenu({ onDownload, more = [] }: {
  onDownload: (format: 'csv' | 'json') => Promise<LogDownload>
  /** Further downloads of the same log in another shape - the call log grouped by conversation. */
  more?: { key: string; title: string; description: string; download: () => Promise<LogDownload> }[]
}) {
  const { t } = useTranslation()
  const addToast = useToastStore(s => s.addToast)
  const [busy, setBusy] = useState(false)
  const run = async (format: 'csv' | 'json') => runDownload(() => onDownload(format))
  const runDownload = async (download: () => Promise<LogDownload>) => {
    setBusy(true)
    try {
      const { truncated } = await download()
      addToast(truncated
        ? t('Downloaded the newest 10,000 entries. Narrow the period for the rest.')
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
        // The kit Button takes the anchor's ref, and its `loading` is the spinner.
        <Button
          ref={ref}
          variant="quiet"
          aria-haspopup="menu"
          aria-expanded={open}
          loading={busy}
          onClick={toggle}
        >
          {t('Download log')}
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
          {more.map(m => (
            <MenuRow
              key={m.key}
              icon={<Rows3 size={16} aria-hidden="true" />}
              title={m.title}
              description={m.description}
              onSelect={() => { close(); void runDownload(m.download) }}
            />
          ))}
        </>
      )}
    </Popover>
  )
}
