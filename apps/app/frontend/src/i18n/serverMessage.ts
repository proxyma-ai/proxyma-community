// A sentence the SERVER wrote, in the reader's language.
//
// The backend explains itself in English: a refusal's reason, a failed sync, a limit reached. It
// cannot do otherwise - much of that is written on a background thread with no request and no
// reader yet, stored, and shown days later to whoever opens the page. So it is translated here,
// by this app's own catalog, where the language is known.
//
// A sentence with nothing variable in it is an ordinary catalog entry, keyed by its English text.
// One the backend puts together from parts - "File exceeds maximum allowed size of 200MB" - is
// keyed by its TEMPLATE, with a numbered gap where each value goes:
//
//   'File exceeds maximum allowed size of {0}MB': '<the same sentence in Vietnamese, with {0}MB>',
//
// and is recognised by matching the text against the template, the values carried across. A
// numbered gap is what marks a catalog key as one of these; the page's own strings name theirs
// ({count}, {name}). The templates come from serverMessages.generated.ts, which is read out of the
// Java source (tools/i18n/server-messages.mjs) - that file is how the catalog checker knows a
// translation here still has a sentence behind it.
//
// ENGLISH IS THE FALLBACK, ALWAYS. A sentence with no entry, a library's own error, a provider's
// reply: each comes back exactly as it was sent. Nothing here can make a message disappear.
//
// Plain .ts with no imports, so `node --test` reaches it - serverMessage.test.ts.

type Catalog = Record<string, string>

interface Template {
  key: string
  pattern: RegExp
  // How much of the template is fixed text. The most specific template is tried first, so
  // "Could not fetch {0} because {1}, so nothing was crawled." wins over "Could not fetch {0}: {1}".
  weight: number
}

const GAP = /\{(\d+)\}/g
const templatesByCatalog = new WeakMap<Catalog, Template[]>()

const escapeRegExp = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

function templatesOf(catalog: Catalog): Template[] {
  const known = templatesByCatalog.get(catalog)
  if (known) return known
  const templates: Template[] = []
  for (const key of Object.keys(catalog)) {
    if (!/\{\d+\}/.test(key)) continue
    const parts = key.split(/\{\d+\}/)
    const order = [...key.matchAll(GAP)].map(m => m[1])
    // A gap takes as little as it can, so a sentence with two of them splits where its fixed text
    // says; the last one runs to the end. A value may span lines (an error quoted from a tool).
    let source = '^'
    parts.forEach((part, i) => {
      source += escapeRegExp(part)
      if (i < order.length) source += `(?<g${i}>[\\s\\S]+?)`
    })
    templates.push({
      key,
      pattern: new RegExp(source + '$'),
      weight: parts.join('').length,
    })
  }
  templates.sort((a, b) => b.weight - a.weight)
  templatesByCatalog.set(catalog, templates)
  return templates
}

/**
 * `text` in the catalog's language, or `text` itself when the catalog has nothing for it.
 *
 * A value carried across a gap is looked up too, once: the backend nests its sentences - "Could
 * not fetch X because <another sentence>" - and the inner one should not stay English inside a
 * translated outer one.
 */
export function translateServerText(text: string, catalog: Catalog | undefined, depth = 0): string {
  if (!catalog || !text) return text
  const exact = Object.prototype.hasOwnProperty.call(catalog, text) ? catalog[text] : undefined
  if (exact !== undefined) return exact
  for (const template of templatesOf(catalog)) {
    const match = template.pattern.exec(text)
    if (!match) continue
    const names = [...template.key.matchAll(GAP)].map(m => m[1])
    const values: Record<string, string> = {}
    names.forEach((name, i) => {
      const value = match.groups?.[`g${i}`] ?? ''
      values[name] = depth < 1 ? translateServerText(value, catalog, depth + 1) : value
    })
    return catalog[template.key].replace(GAP, (whole, name: string) => values[name] ?? whole)
  }
  return text
}
