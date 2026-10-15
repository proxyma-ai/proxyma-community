import { test } from 'node:test'
import assert from 'node:assert/strict'
import { translateServerText } from './serverMessage.ts'

// A stand-in language, so the test reads without knowing one: every translation is upper case.
const catalog = {
  'A provider needs a name.': 'A PROVIDER NEEDS A NAME.',
  'File exceeds maximum allowed size of {0}MB': 'TOO LARGE - THE LIMIT IS {0}MB',
  'Could not fetch {0}: {1}': 'NO FETCH OF {0}: {1}',
  'Could not fetch {0} because {1}, so nothing was crawled.': 'NOTHING CRAWLED AT {0}, BECAUSE {1}.',
  'The address {0} no longer serves a web page - it now returns {1}.': 'NO PAGE AT {0} ANY MORE - IT RETURNS {1}.',
  "'{0}' is used by {1}. Remove {2} from Sources first.": "{1} USES '{0}'. REMOVE {2} FIRST.",
  // The page's own strings name their gaps, and are not templates for anything the server says.
  '{count} sources': '{count} SOURCES',
  'Sources': 'SOURCES',
}

test('a sentence with nothing variable in it is looked up as it stands', () => {
  assert.equal(translateServerText('A provider needs a name.', catalog), 'A PROVIDER NEEDS A NAME.')
})

test('a sentence put together from parts is matched to its template, the values carried across', () => {
  assert.equal(translateServerText('File exceeds maximum allowed size of 200MB', catalog), 'TOO LARGE - THE LIMIT IS 200MB')
  assert.equal(translateServerText("'Team wiki' is used by 3 sources. Remove them from Sources first.", catalog),
    "3 sources USES 'Team wiki'. REMOVE them FIRST.")
})

test('the most specific template wins, whatever order the catalog lists them in', () => {
  assert.equal(
    translateServerText('Could not fetch https://example.com because it timed out, so nothing was crawled.', catalog),
    'NOTHING CRAWLED AT https://example.com, BECAUSE it timed out.')
  assert.equal(translateServerText('Could not fetch https://example.com: connection refused', catalog),
    'NO FETCH OF https://example.com: connection refused')
})

test('a sentence carried inside another is translated too', () => {
  assert.equal(
    translateServerText('Could not fetch https://example.com: File exceeds maximum allowed size of 20MB', catalog),
    'NO FETCH OF https://example.com: TOO LARGE - THE LIMIT IS 20MB')
})

test('what the catalog does not know comes back exactly as it was sent', () => {
  for (const text of ['Connection reset by peer', 'tier_limit', '', 'File exceeds the limit', '429 Too Many Requests: {"error":"slow down"}']) {
    assert.equal(translateServerText(text, catalog), text)
  }
  assert.equal(translateServerText('A provider needs a name.', undefined), 'A provider needs a name.')
})

test('a template matches the WHOLE sentence, never a part of a longer one', () => {
  const longer = 'Sync failed. File exceeds maximum allowed size of 200MB and was skipped.'
  assert.equal(translateServerText(longer, catalog), longer)
})

test('a named gap is the page own string and is never matched against server text', () => {
  assert.equal(translateServerText('12 sources', catalog), '12 sources')
})

test('characters that mean something in a pattern are matched as themselves', () => {
  assert.equal(
    translateServerText('The address https://a.example/x?y=1 no longer serves a web page - it now returns application/pdf.', catalog),
    'NO PAGE AT https://a.example/x?y=1 ANY MORE - IT RETURNS application/pdf.')
  // The dot in the template is a dot: this near-miss is not the sentence.
  assert.equal(translateServerText('A provider needs a nameX', catalog), 'A provider needs a nameX')
})

test('a value may span lines', () => {
  assert.equal(translateServerText('Could not fetch https://example.com: first line\nsecond line', catalog),
    'NO FETCH OF https://example.com: first line\nsecond line')
})
