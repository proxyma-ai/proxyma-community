import { test } from 'node:test'
import assert from 'node:assert/strict'
import { SAFE_DOWNLOAD_TYPE, SVG_TYPE, extensionOf, previewBlobType, previewKind } from './previewKind.ts'

test('office formats by extension, case-insensitive', () => {
  assert.equal(previewKind('Report.DOCX'), 'docx')
  assert.equal(previewKind('budget.xlsx'), 'xlsx')
  assert.equal(previewKind('deck.pptx'), 'pptx')
  assert.equal(previewKind('macro.xlsm'), 'xlsx')
})

test('legacy binary office formats get no preview, not a broken one', () => {
  assert.equal(previewKind('old.doc'), 'none')
  assert.equal(previewKind('old.xls'), 'none')
  assert.equal(previewKind('old.ppt'), 'none')
})

test('images, pdf, csv and text', () => {
  assert.equal(previewKind('photo.JPG'), 'image')
  assert.equal(previewKind('spec.pdf'), 'pdf')
  assert.equal(previewKind('rows.csv'), 'csv')
  assert.equal(previewKind('notes.md'), 'text')
})

test('the MIME type decides only when the name does not', () => {
  assert.equal(previewKind('pasted', 'image/png'), 'image')
  assert.equal(previewKind('download', 'application/pdf'), 'pdf')
  assert.equal(previewKind('data.xlsx', 'application/octet-stream'), 'xlsx')
  assert.equal(previewKind('blob', 'application/octet-stream'), 'none')
})

test('a preview blob is never typed as anything a tab would run', () => {
  // The server's or the file's own type is not trusted: an .html is octet-stream however it came.
  assert.equal(previewBlobType('text', 'page.html', 'text/html'), SAFE_DOWNLOAD_TYPE)
  assert.equal(previewBlobType('docx', 'contract.docx', 'text/html'), SAFE_DOWNLOAD_TYPE)
  assert.equal(previewBlobType('none', 'run.hta', 'application/hta'), SAFE_DOWNLOAD_TYPE)
  assert.equal(previewBlobType('csv', 'rows.csv', 'text/csv'), SAFE_DOWNLOAD_TYPE)
})

test('an image keeps a raster type taken from its name, and a PDF stays a PDF', () => {
  assert.equal(previewBlobType('image', 'photo.JPG', 'text/html'), 'image/jpeg')
  assert.equal(previewBlobType('image', 'chart.png', 'application/octet-stream'), 'image/png')
  assert.equal(previewBlobType('image', 'pasted', 'image/webp'), 'image/webp')
  assert.equal(previewBlobType('image', 'pasted', 'image/x-unknown'), SAFE_DOWNLOAD_TYPE)
  assert.equal(previewBlobType('pdf', 'spec.pdf', 'text/html'), 'application/pdf')
})

test('an SVG is called an SVG - the caller draws it from a data: URL, never a blob of this origin', () => {
  assert.equal(previewBlobType('image', 'logo.svg', 'text/html'), SVG_TYPE)
  assert.equal(previewBlobType('image', 'pasted', SVG_TYPE), SVG_TYPE)
  // A raster name wins over a claimed SVG type: drawn as a PNG, script and all, it is only bytes.
  assert.equal(previewBlobType('image', 'logo.png', SVG_TYPE), 'image/png')
})

test('extensionOf ignores dotfiles and trailing dots', () => {
  assert.equal(extensionOf('.env'), '')
  assert.equal(extensionOf('name.'), '')
  assert.equal(extensionOf('a.b.tar.gz'), 'gz')
})
