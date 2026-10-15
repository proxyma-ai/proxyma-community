// Which preview a file gets, decided from its name and, when the browser knows one, its MIME type.
// Plain .ts with no imports, so `node --test` reaches it (previewKind.test.ts).
//
// Office formats are the modern XML ones only. .doc, .xls and .ppt are binary formats no browser
// library here reads - they get the download row, which is what 'none' means - rather than a
// preview that renders garbage.

export type PreviewKind = 'image' | 'pdf' | 'docx' | 'xlsx' | 'pptx' | 'csv' | 'text' | 'none'

const BY_EXTENSION: Record<string, PreviewKind> = {
  png: 'image', jpg: 'image', jpeg: 'image', gif: 'image', webp: 'image', bmp: 'image', svg: 'image',
  pdf: 'pdf',
  docx: 'docx', docm: 'docx',
  xlsx: 'xlsx', xlsm: 'xlsx',
  pptx: 'pptx', pptm: 'pptx',
  csv: 'csv', tsv: 'csv',
  txt: 'text', md: 'text', markdown: 'text', json: 'text', xml: 'text', yaml: 'text', yml: 'text',
  log: 'text', ini: 'text', html: 'text', htm: 'text', py: 'text', js: 'text', ts: 'text',
  java: 'text', sql: 'text', sh: 'text',
}

export function extensionOf(name: string): string {
  const dot = name.lastIndexOf('.')
  return dot > 0 && dot < name.length - 1 ? name.slice(dot + 1).toLowerCase() : ''
}

export function previewKind(name: string, mime?: string): PreviewKind {
  const byName = BY_EXTENSION[extensionOf(name)]
  if (byName) return byName
  if (mime?.startsWith('image/')) return 'image'
  if (mime === 'application/pdf') return 'pdf'
  if (mime?.startsWith('text/')) return 'text'
  return 'none'
}

// THE TYPE A PREVIEW'S OWN BLOB URL CARRIES (2026-10-14, security review swx-10). A blob: URL has
// this page's origin, and "Open in new tab" renders it top-level with whatever type it was made
// with - so an uploaded or agent-written .html kept as text/html, or an .svg kept as
// image/svg+xml, would run its script as the reader. The server serves these as downloads for
// exactly that reason; the preview must not undo it.
//
// So the bytes are re-typed here, never taken from the server or the file: a raster image keeps a
// raster type so <img> draws it, a PDF stays a PDF so the frame shows it, and everything else - the
// Download button's blob included - is octet-stream, which a browser only ever saves. An SVG is
// the one image that can carry script, so it is drawn from a data: URL instead (`DATA_URL_TYPE`):
// opened in a tab, that has an origin of its own, not this one.
const RASTER_TYPE: Record<string, string> = {
  png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp', bmp: 'image/bmp',
}
const RASTER_TYPES = new Set(Object.values(RASTER_TYPE))
export const SAFE_DOWNLOAD_TYPE = 'application/octet-stream'
export const SVG_TYPE = 'image/svg+xml'

export function previewBlobType(kind: PreviewKind, name: string, mime?: string): string {
  if (kind === 'pdf') return 'application/pdf'
  if (kind !== 'image') return SAFE_DOWNLOAD_TYPE
  const ext = extensionOf(name)
  if (ext === 'svg' || (!RASTER_TYPE[ext] && mime === SVG_TYPE)) return SVG_TYPE
  if (RASTER_TYPE[ext]) return RASTER_TYPE[ext]
  return mime && RASTER_TYPES.has(mime) ? mime : SAFE_DOWNLOAD_TYPE
}
