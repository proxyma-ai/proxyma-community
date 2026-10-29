import { useEffect, useMemo, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Download } from 'lucide-react'
import { ModalShell } from '@/components/ui/ModalShell'
import {
  ButtonLink, Caption, Card, CodeBlock, DialogBody, DialogHeader, EmptyState, ErrorState, Eyebrow, FadeSwap, HScroll, LoadingState, Presence, SheetTable, SubNavTabs, TEXT, cn,
  safeExternalHref,
} from '@/components/ui/kit'
import { http } from '@/services/httpClient'
import { useTranslation } from '@/i18n'
import { SAFE_DOWNLOAD_TYPE, SVG_TYPE, previewBlobType, previewKind, type PreviewKind } from '../utils/previewKind'
import { parseCsv } from '../utils/parseCsv'

// A file shown in place: an attachment before it is sent (a local File), one in the thread (served
// by the backend), or a file the agent made. Everything a browser can draw is drawn here, with no
// server-side conversion: images and PDFs natively, Word through docx-preview, Excel through
// read-excel-file, PowerPoint as a slide-by-slide text outline, CSV as a table and text as text.
// Anything else gets the download button alone.
//
// The three Office libraries are imported only when a file of that kind is opened, so none of them
// is in the chat page's bundle.

export interface PreviewSource {
  name: string
  // A file the browser already holds - the composer's attachment - or a URL under the API.
  blob?: Blob
  url?: string
}

// A preview is for looking, not for reading a whole export: the first rows and columns of a sheet
// or CSV, and the first stretch of a text file, which also keeps a 25 MB file from freezing the tab.
const MAX_ROWS = 500
const MAX_COLS = 60
const MAX_TEXT_CHARS = 200_000

export function FilePreviewDialog({ source, onClose }: { source: PreviewSource; onClose: () => void }) {
  const { t } = useTranslation()
  // THROUGH THE APP'S HTTP CLIENT AND TANSTACK QUERY (CHAT-36): a raw fetch bypassed the access-
  // token refresh, so a preview opened after the access cookie expired failed with "Could not load
  // this file." The bytes are kept only while the dialog shows them.
  const { data: fetched, isError: failed } = useQuery({
    queryKey: ['file-preview', source.url],
    queryFn: () => http.get<Blob>(source.url!, { responseType: 'blob' }).then(r => r.data),
    enabled: !source.blob && !!source.url,
    retry: false,
    staleTime: Infinity,
    gcTime: 0,
  })
  const blob = source.blob ?? fetched ?? null
  const kind = previewKind(source.name, blob?.type)

  // The Download button's bytes are octet-stream whatever they are: a browser only ever saves that
  // type, so "Open link in new tab" on the button cannot render an .html or .svg on this origin
  // (security review swx-10). `previewBlobType` has the reasoning.
  const downloadUrl = useMemo(() => (blob ? URL.createObjectURL(new Blob([blob], { type: SAFE_DOWNLOAD_TYPE })) : null), [blob])
  useEffect(() => () => { if (downloadUrl) URL.revokeObjectURL(downloadUrl) }, [downloadUrl])
  const viewUrl = useViewUrl(blob, kind, source.name)
  const needsViewUrl = kind === 'image' || kind === 'pdf'

  return (
    <ModalShell
      onClose={onClose}
      ariaLabel={t('Preview of {name}', { name: source.name })}
      size="full"
      // The kit's one cap for a dialog whose body scrolls (CHAT-29) - it was 90dvh here.
      cap
    >
      <DialogHeader
        title={source.name}
        actions={downloadUrl && (
          <ButtonLink variant="secondary" href={downloadUrl} download={source.name}>
            <Download size={14} aria-hidden="true" /> {t('Download')}
          </ButtonLink>
        )}
      />
      {/* Vertical only. What is wider than the dialog - a sheet, a CSV, a docx page on a narrow
          window - scrolls sideways in its own HScroll, with no horizontal scrollbar. */}
      <DialogBody flush scroll className="min-h-[12rem] overflow-x-hidden bg-canvas">
        {failed ? <Failed text={t('Could not load this file.')} />
          : !blob || (needsViewUrl && !viewUrl) ? <Loading />
          : <Body kind={kind} blob={blob} viewUrl={viewUrl ?? ''} name={source.name} />}
      </DialogBody>
      {/* No footer: its one button was Close, and a dialog closes on its backdrop and Escape. */}
    </ModalShell>
  )
}

// The address an image or a PDF is drawn from, typed by `previewBlobType` rather than by the server
// or the file. An SVG is read into a data: URL, the one form in which "Open image in new tab" cannot
// run its script as this page; anything else that is drawn is a blob URL of a type that cannot run.
function useViewUrl(blob: Blob | null, kind: PreviewKind, name: string): string | null {
  const [url, setUrl] = useState<string | null>(null)
  useEffect(() => {
    setUrl(null)
    if (!blob || (kind !== 'image' && kind !== 'pdf')) return
    const typed = new Blob([blob], { type: previewBlobType(kind, name, blob.type) })
    if (typed.type === SVG_TYPE) {
      let cancelled = false
      const reader = new FileReader()
      reader.onload = () => { if (!cancelled && typeof reader.result === 'string') setUrl(reader.result) }
      reader.readAsDataURL(typed)
      return () => { cancelled = true }
    }
    const objectUrl = URL.createObjectURL(typed)
    setUrl(objectUrl)
    return () => URL.revokeObjectURL(objectUrl)
  }, [blob, kind, name])
  return url
}

function Body({ kind, blob, viewUrl, name }: { kind: PreviewKind; blob: Blob; viewUrl: string; name: string }) {
  const { t } = useTranslation()
  switch (kind) {
    case 'image':
      return <div className="flex justify-center p-4"><img src={viewUrl} alt={name} className="max-w-full object-contain animate-fade-in" /></div>
    case 'pdf':
      // No backdrop colour of its own: the viewer paints its pages, and a white box behind it flashed
      // in a dark appearance while it loaded (CHAT-36). As tall as the capped body allows.
      return <iframe src={viewUrl} title={name} className="block w-full h-[min(38rem,calc(100dvh-10rem))] border-0" />
    case 'docx': return <DocxBody blob={blob} name={name} />
    case 'xlsx': return <SheetBody blob={blob} />
    case 'pptx': return <SlidesBody blob={blob} />
    case 'csv': return <CsvBody blob={blob} />
    case 'text': return <TextBody blob={blob} />
    default: return <EmptyState className="px-4 sm:px-6">{t('This file type cannot be previewed. Download it to open it.')}</EmptyState>
  }
}

// A preview has no shape to imitate while it loads, so this is the kit's spinner-and-caption.
function Loading() {
  const { t } = useTranslation()
  return <LoadingState centered label={t('Loading preview')} className="h-48" />
}

// Runs one async render and reports failure the same way for every format: a file that claims to
// be a .docx but is not one is common enough (a renamed export, a truncated download).
function useRender<T>(render: () => Promise<T>): { value: T | null; failed: boolean } {
  const [value, setValue] = useState<T | null>(null)
  const [failed, setFailed] = useState(false)
  const renderRef = useRef(render)
  useEffect(() => {
    let cancelled = false
    renderRef.current()
      .then(v => { if (!cancelled) setValue(v) })
      .catch(() => { if (!cancelled) setFailed(true) })
    return () => { cancelled = true }
  }, [])
  return { value, failed }
}

function Failed({ text }: { text?: string }) {
  const { t } = useTranslation()
  return <ErrorState className="px-4 sm:px-6" title={text ?? t('This file could not be previewed. Download it to open it.')} />
}

// docx-preview draws the document as white pages on its own gray wrapper, in the document's own
// fonts and colors, in either mode - dark text recolored for a dark canvas
// would misrepresent someone's document.
//
// A WORD FILE IS SOMEBODY ELSE'S CONTENT, AND IT IS DRAWN IN A FRAME WHERE NOTHING CAN RUN
// (2026-10-14, security review swx-01). It was drawn straight into this page, and docx-preview
// turns a document's altChunk - a whole HTML part - into an <iframe srcdoc> of the same origin, so
// a .docx someone was sent, or one a prompt injection had the agent write, ran its own script as
// the reader the moment the preview opened. Three things close it, each enough on its own for the
// part it covers:
// - `renderAltChunks: false` - the HTML part is not rendered at all;
// - the frame's sandbox has no `allow-scripts`, and a sandbox binds every frame inside it, so no
//   script, event handler or `javascript:` link in the document can run. `allow-same-origin` is
//   there only so this page can write the rendered pages INTO the frame - without scripts it gives
//   the document nothing - and the popup flags let its web links open in a tab of their own;
// - every link is checked after rendering: a web or mail address opens in a new tab, an anchor
//   inside the document stays, and anything else loses its href.
// `useBase64URL` makes the document's pictures and fonts data: URLs, so "Open image in new tab" on
// one cannot render it as this page either, whatever type the document claims for it (swx-10).
// The frame also keeps the document's own styles, which docx-preview writes as <style> elements,
// away from the app's.
const DOCX_OPTIONS = {
  inWrapper: true,
  ignoreLastRenderedPageBreak: true,
  renderAltChunks: false,
  useBase64URL: true,
}
const DOCX_SANDBOX = 'allow-same-origin allow-popups allow-popups-to-escape-sandbox'
const EMPTY_FRAME = '<!doctype html><html><head></head><body></body></html>'

// The links a rendered document may keep: an anchor within it, or a web or mail address that opens
// in a new tab (with no handle back on this page). Anything else is left as its text.
function neutralizeLinks(doc: Document) {
  for (const a of Array.from(doc.querySelectorAll('a[href]'))) {
    const href = a.getAttribute('href') ?? ''
    if (href.startsWith('#') && href.length > 1) continue
    if (safeExternalHref(href)) {
      a.setAttribute('target', '_blank')
      a.setAttribute('rel', 'noopener noreferrer')
    } else {
      a.removeAttribute('href')
    }
  }
}

// Pages are drawn at their printed width. On a window narrower than a page the whole document is
// scaled to fit (CSS zoom, set through the CSSOM so no inline <style> is needed) rather than
// scrolled sideways inside the frame - which would show a horizontal scrollbar, and which
// docx-preview's centered wrapper cuts off on the left anyway.
function fitToWidth(frame: HTMLIFrameElement | null) {
  const doc = frame?.contentDocument
  if (!frame || !doc?.body) return
  doc.body.style.zoom = ''
  const natural = doc.documentElement.scrollWidth
  const room = frame.clientWidth
  doc.body.style.zoom = room > 0 && natural > room ? String(Math.max(0.3, room / natural)) : ''
}

function DocxBody({ blob, name }: { blob: Blob; name: string }) {
  const frame = useRef<HTMLIFrameElement>(null)
  // The frame's empty document arrives on its load event; writing into it before then would be
  // written into the initial about:blank that the srcdoc then replaces.
  const [frameReady, setFrameReady] = useState(false)
  const [state, setState] = useState<'loading' | 'done' | 'failed'>('loading')

  useEffect(() => {
    if (!frameReady) return
    const doc = frame.current?.contentDocument
    if (!doc?.body) { setState('failed'); return }
    let cancelled = false
    doc.documentElement.style.background = 'gray'
    doc.body.style.margin = '0'
    import('docx-preview')
      .then(({ renderAsync }) => renderAsync(blob, doc.body, doc.head, DOCX_OPTIONS))
      .then(() => {
        if (cancelled) return
        neutralizeLinks(doc)
        fitToWidth(frame.current)
        setState('done')
      })
      .catch(() => { if (!cancelled) setState('failed') })
    return () => { cancelled = true }
  }, [frameReady, blob])

  useEffect(() => {
    if (state !== 'done') return
    const refit = () => fitToWidth(frame.current)
    window.addEventListener('resize', refit)
    return () => window.removeEventListener('resize', refit)
  }, [state])

  if (state === 'failed') return <Failed />
  // The frame is in place, at its full size, from the start - the pages are measured in it - and
  // fades in once they are drawn; the loading line sits over it until then.
  return (
    <div className="relative">
      <iframe
        ref={frame}
        title={name}
        sandbox={DOCX_SANDBOX}
        srcDoc={EMPTY_FRAME}
        // Only the srcdoc's own load: a browser that also reports the initial about:blank would
        // otherwise have the pages written into a document about to be thrown away.
        onLoad={e => { if (e.currentTarget.contentDocument?.URL === 'about:srcdoc') setFrameReady(true) }}
        className={cn(
          'block w-full h-[calc(90dvh-8rem)] border-0 transition-opacity duration-base ease-out',
          state === 'done' ? 'opacity-100' : 'opacity-0',
        )}
      />
      <Presence show={state === 'loading'} collapse={false} className="absolute inset-x-0 top-0">
        <Loading />
      </Presence>
    </div>
  )
}

// The kit's SheetTable - every cell ruled, the row numbers pinned at the left - at least the
// dialog's width (`min-w-full`): a narrow CSV sat in the left half of a wide dialog with the rest
// of it empty (the review's F28). Wider than the dialog, it scrolls in its HScroll. The first row
// is usually the header, so it stays in the primary ink and weight (`headerRow`).
function Table({ rows }: { rows: unknown[][] }) {
  return (
    <SheetTable
      headerRow
      rows={rows.map(row => row.slice(0, MAX_COLS).map(cell =>
        cell instanceof Date ? cell.toISOString().slice(0, 10) : cell == null ? '' : String(cell)))}
    />
  )
}

function Truncated({ shown, total }: { shown: number; total: number }) {
  const { t } = useTranslation()
  if (total <= shown) return null
  return <Caption className="px-3 py-2">{t('Showing the first {count} of {total} rows.', { count: shown, total })}</Caption>
}

function SheetBody({ blob }: { blob: Blob }) {
  const [active, setActive] = useState(0)
  const { value: sheets, failed } = useRender(async () => {
    const { default: readXlsxFile } = await import('read-excel-file/browser')
    return readXlsxFile(blob)
  })
  if (failed) return <Failed />
  if (!sheets) return <Loading />
  const sheet = sheets[active]
  return (
    <div>
      {/* The sheets as the kit's tabs - underlined words, as every tab row is; they were filled pills. */}
      {sheets.length > 1 && (
        <div className="px-3 pt-2 animate-fade-in">
          <SubNavTabs
            tabs={sheets.map((s, i) => ({ key: String(i), label: s.sheet }))}
            active={String(active)}
            onChange={key => setActive(Number(key))}
          />
        </div>
      )}
      {/* wheel={false}: the dialog scrolls ROWS with the wheel, and a table that took the wheel for
          itself would stop that at every sheet wider than the dialog. Shift+wheel, a trackpad and
          the arrow keys still scroll it sideways. */}
      <FadeSwap swapKey={active}>
        <HScroll className="p-3" wheel={false}><Table rows={sheet ? sheet.data.slice(0, MAX_ROWS) : []} /></HScroll>
      </FadeSwap>
      {sheet && <Truncated shown={MAX_ROWS} total={sheet.data.length} />}
    </div>
  )
}

function CsvBody({ blob }: { blob: Blob }) {
  const { value, failed } = useRender(async () => {
    const text = await blob.text()
    // One row past the cap, only to know whether there were more.
    return parseCsv(text, MAX_ROWS + 1)
  })
  if (failed) return <Failed />
  if (!value) return <Loading />
  return (
    <div>
      <HScroll className="p-3" wheel={false}><Table rows={value.slice(0, MAX_ROWS)} /></HScroll>
      <Truncated shown={MAX_ROWS} total={value.length} />
    </div>
  )
}

function TextBody({ blob }: { blob: Blob }) {
  const { value, failed } = useRender(() => blob.slice(0, MAX_TEXT_CHARS * 4).text())
  if (failed) return <Failed />
  if (value === null) return <Loading />
  return <div className="p-4"><CodeBlock wrap>{value.slice(0, MAX_TEXT_CHARS)}</CodeBlock></div>
}

// A .pptx is a zip of one XML file per slide; the words on a slide are its <a:t> runs, grouped into
// paragraphs by <a:p>. There is no browser library that draws slides faithfully, so the preview is
// the outline - which is what someone checking a deck the agent just wrote mostly wants to read.
function SlidesBody({ blob }: { blob: Blob }) {
  const { t } = useTranslation()
  const { value: slides, failed } = useRender(async () => {
    const { default: JSZip } = await import('jszip')
    const zip = await JSZip.loadAsync(blob)
    const files = Object.keys(zip.files)
      .map(path => ({ path, n: Number(/^ppt\/slides\/slide(\d+)\.xml$/.exec(path)?.[1]) }))
      .filter(f => f.n > 0)
      .sort((a, b) => a.n - b.n)
    const parser = new DOMParser()
    const A = 'http://schemas.openxmlformats.org/drawingml/2006/main'
    return Promise.all(files.map(async f => {
      const xml = parser.parseFromString(await zip.file(f.path)!.async('text'), 'application/xml')
      return Array.from(xml.getElementsByTagNameNS(A, 'p'))
        .map(p => Array.from(p.getElementsByTagNameNS(A, 't')).map(r => r.textContent ?? '').join(''))
        .filter(line => line.trim())
    }))
  })
  if (failed) return <Failed />
  if (!slides) return <Loading />
  if (slides.length === 0) return <Failed />
  return (
    <ol className="p-4 space-y-3">
      {slides.map((lines, i) => (
        <li key={i}><Card variant="solid" padding="sm">
          <Eyebrow className="mb-2">{t('Slide {number}', { number: i + 1 })}</Eyebrow>
          {lines.length === 0
            ? <Caption>{t('No text on this slide')}</Caption>
            : lines.map((line, j) => (
              <p key={j} className={j === 0 ? cn(TEXT.label, 'text-ink-primary') : cn(TEXT.body, 'text-ink-secondary')}>{line}</p>
            ))}
        </Card></li>
      ))}
    </ol>
  )
}
