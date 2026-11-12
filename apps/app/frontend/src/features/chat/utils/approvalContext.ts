// WHAT A WRITE WAITING FOR APPROVAL IS ABOUT (2026-10-31, the maintainer: "which video, which user
// with which comment and what the agent wants to comment"). The server builds it once, when the request
// is made (ApprovalContext.java) - a YouTube reply's video, viewer, comment and reply - and it reaches
// the page with the approvals inbox's rows and, for a live prompt, as a 'tool-approval-context' event.
// Pure, so node --test reaches it: the card that draws it is ApprovalContextCard.tsx.

export type ApprovalFieldKind = 'TEXT' | 'QUOTE' | 'LINK'

export interface ApprovalContextField {
  key: string
  value: string
  href?: string | null
  kind: ApprovalFieldKind
  /**
   * The call's argument this field shows, when a person may rewrite it before approving (the YouTube
   * reply's `text`, 2026-11-02). The server decides what is editable; a page only offers it.
   */
  argument?: string | null
}

export interface ApprovalContext {
  title?: string | null
  fields: ApprovalContextField[]
}

/** One line of the card: its label in the reader's words, the value, and a link only when it is one. */
export interface ApprovalContextRow {
  key: string
  label: string
  value: string
  href: string | null
  kind: ApprovalFieldKind
  /** The argument a person may edit through this row, or null. */
  argument: string | null
  /** Links drawn after the value on this row's line (foldContextLinks) - the viewer's: the video, the comment. */
  links?: ApprovalLink[]
  /**
   * How long ago the comment was written, in the reader's words (foldCommentAge) - drawn as a quiet caption
   * with the row's label, never inside it, so a long label does not wrap or push the values aside.
   */
  age?: string
}

/** A link on a row's line: what it opens, in the reader's words or as the server named it. */
export interface ApprovalLink {
  key: string
  label: string
  href: string
}

const KINDS: readonly ApprovalFieldKind[] = ['TEXT', 'QUOTE', 'LINK']

// A link the card may follow: a whole http(s) address and nothing else. The server holds it to the
// same rule; the kit's ExternalLink checks again.
function safeHref(raw: unknown): string | null {
  if (typeof raw !== 'string') return null
  const h = raw.trim()
  return /^https?:\/\/[^\s]+$/i.test(h) ? h : null
}

// An argument a field may name: a plain identifier, as the server holds it (ApprovalContext.ARGUMENT).
function argumentOf(raw: unknown, kind: ApprovalFieldKind): string | null {
  if (typeof raw !== 'string' || kind === 'LINK') return null
  const a = raw.trim()
  return /^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(a) ? a : null
}

/**
 * Whether a link's words are only a value of its own address's query - "v2" on ".../watch?v=v2": an id, which
 * names nothing a person can recognize (the server holds a card to the same rule, ApprovalContexts.missing).
 */
export function labeledByItsId(value: string, href: string | null | undefined): boolean {
  const v = value.trim()
  if (!href || v === '' || /\s/.test(v)) return false
  const q = href.indexOf('?')
  if (q < 0) return false
  for (const pair of href.slice(q + 1).split('&')) {
    const eq = pair.indexOf('=')
    if (eq <= 0) continue
    try {
      if (decodeURIComponent(pair.slice(eq + 1)) === v) return true
    } catch {
      // Not an encoded value: not this one.
    }
  }
  return false
}

/**
 * A context as the server sent it - an object, or its JSON (a chat event's data) - or null when it is
 * missing or not one. A field with no key or no value is left out; one of an unknown kind is TEXT, and
 * a LINK without a usable address is TEXT too.
 */
export function parseApprovalContext(raw: unknown): ApprovalContext | null {
  let value: unknown = raw
  if (typeof raw === 'string') {
    if (raw.trim() === '') return null
    try {
      value = JSON.parse(raw)
    } catch {
      return null
    }
  }
  if (!value || typeof value !== 'object') return null
  const source = value as { title?: unknown; fields?: unknown }
  if (!Array.isArray(source.fields)) return null
  const fields: ApprovalContextField[] = []
  for (const f of source.fields) {
    if (!f || typeof f !== 'object') continue
    const field = f as Record<string, unknown>
    const key = typeof field.key === 'string' ? field.key.trim() : ''
    const text = typeof field.value === 'string' ? field.value : ''
    if (key === '' || text.trim() === '') continue
    const rawKind = typeof field.kind === 'string' ? field.kind.toUpperCase() : 'TEXT'
    let kind: ApprovalFieldKind = (KINDS as readonly string[]).includes(rawKind) ? rawKind as ApprovalFieldKind : 'TEXT'
    // A LINK needs its address; a QUOTE may carry one too - where the words can be opened (a comment).
    const href = kind === 'LINK' || kind === 'QUOTE' ? safeHref(field.href) : null
    if (kind === 'LINK' && href === null) kind = 'TEXT'
    fields.push({ key, value: text, href, kind, argument: argumentOf(field.argument, kind) })
  }
  if (fields.length === 0) return null
  return { title: typeof source.title === 'string' ? source.title : null, fields }
}

/**
 * The rows the card shows, in the server's order. The keys this page knows get their label in the
 * reader's language - the reply as the reply TO BE POSTED, so it cannot be read as one already there,
 * and the channel it is posted AS (`replyAs`, 2026-11-05: a team switched between two channels had
 * drafts for both) just before it; any other key is shown as the server named it.
 */
export function approvalContextRows(context: ApprovalContext | null | undefined, t: (s: string) => string): ApprovalContextRow[] {
  if (!context) return []
  return context.fields.map(f => ({
    key: f.key,
    label: labelOf(f.key, t),
    // A video an older build could only name by its id ("8QFVNhzw5GE", 2026-11-05) is offered by what it is.
    value: f.key === 'video' && labeledByItsId(f.value, f.href) ? t('Open video') : f.value,
    href: f.kind === 'LINK' || f.kind === 'QUOTE' ? safeHref(f.href) : null,
    kind: f.kind === 'LINK' && !safeHref(f.href) ? 'TEXT' : f.kind,
    argument: argumentOf(f.argument, f.kind),
  }))
}

/**
 * THE LINKS BESIDE THE VIEWER (2026-11-02, the maintainer: "Video link and comment link should be shown
 * next to the viewer"). The viewer's row takes, after their name, the video (by its title), their
 * comment where it sits on the video, and - once a reply was posted - the reply; the video's own row
 * goes, and a posted reply is labeled as posted rather than to be posted. Rows without a viewer stay as
 * they are. Every row gets its `links`, empty unless it is the viewer's.
 */
export function foldContextLinks(rows: readonly ApprovalContextRow[], t: (s: string) => string): ApprovalContextRow[] {
  const viewer = rows.find(r => r.key === 'viewer')
  if (!viewer) return rows.map(r => ({ ...r, links: [] }))
  const links: ApprovalLink[] = []
  const video = rows.find(r => r.key === 'video' && r.kind === 'LINK' && r.href)
  if (video?.href) links.push({ key: 'video', label: video.value, href: video.href })
  const comment = rows.find(r => r.key === 'comment' && r.href)
  if (comment?.href) links.push({ key: 'comment', label: t('View comment'), href: comment.href })
  const reply = rows.find(r => r.key === 'reply' && r.href)
  if (reply?.href) links.push({ key: 'reply', label: t('View reply'), href: reply.href })
  return rows
    .filter(r => r !== video)
    .map(r => r === viewer ? { ...r, links }
      : r === reply ? { ...r, label: t('Reply posted'), links: [] }
        : { ...r, links: [] })
}

/**
 * HOW OLD THE COMMENT IS (2026-11-03: the YouTube team answers old comments too, newest first). The server
 * sends when the comment was written as an ISO time (`commentedAt`, YouTubeApprovalContext.java); the
 * comment's row takes it as an age in the reader's language - its `age`, "2 years ago", which the card
 * draws as a caption with the label (the Approvals page's alignment review, 2026-11-03: folded into the
 * label, "Their comment (5 months ago)" widened the label column of every card) - and the time's own row
 * goes. Without a comment row it stays, its value the age. A time that is not one is left as it was.
 * `now` is for tests.
 */
export function foldCommentAge(rows: readonly ApprovalContextRow[], locale: string, now: number = Date.now()): ApprovalContextRow[] {
  const when = rows.find(r => r.key === 'commentedAt')
  if (!when) return [...rows]
  const age = timeAgo(when.value, locale, now)
  if (age === null) return [...rows]
  const comment = rows.find(r => r.key === 'comment')
  if (!comment) return rows.map(r => r === when ? { ...r, value: age } : r)
  return rows
    .filter(r => r !== when)
    .map(r => r === comment ? { ...r, age } : r)
}

const AGE_UNITS: ReadonlyArray<[Intl.RelativeTimeFormatUnit, number]> = [
  ['year', 365 * 86_400], ['month', 30 * 86_400], ['week', 7 * 86_400], ['day', 86_400], ['hour', 3_600], ['minute', 60],
]

/** How long ago an ISO time was, in the reader's language ("2 years ago", "2 nam truoc"), or null when it is not a time. */
export function timeAgo(iso: string, locale: string, now: number = Date.now()): string | null {
  const at = Date.parse(iso)
  if (!Number.isFinite(at)) return null
  const seconds = Math.round((at - now) / 1000)
  for (const [unit, size] of AGE_UNITS) {
    if (Math.abs(seconds) >= size) {
      return new Intl.RelativeTimeFormat(locale, { numeric: 'always' }).format(Math.trunc(seconds / size), unit)
    }
  }
  return new Intl.RelativeTimeFormat(locale, { numeric: 'auto' }).format(0, 'second')
}

/**
 * A YOUTUBE COMMENT, DRAWN AS ONE (2026-11-05, the maintainer: "the title is the viewer comment (hyperlink),
 * subtitle is date time of the comment, viewer's username (hyperlink), video title (hyperlink), Agent's
 * drafted reply, Add notes. No need the reply on behalf of"). A context is a comment's when it quotes one
 * (a `comment` field of kind QUOTE): the server's YouTube reply (YouTubeApprovalContext) and a dashboard's
 * comment (topicBars.commentContext) both do; a card made from a write's own arguments does not, and keeps
 * the label/value card.
 */
export function isCommentContext(context: ApprovalContext | null | undefined): boolean {
  return !!context && context.fields.some(f => f.key === 'comment' && f.kind === 'QUOTE')
}

/** A comment as its card draws it: the words for the title, one caption line under it, then the draft. */
export interface CommentCard {
  /** The viewer's words - trimmed - and where they sit on the video, when the server could say. */
  comment: { text: string; href: string | null }
  /** When they were written: in the reader's locale (`at`), as an age (`ago`), and as sent (`iso`). */
  written: { at: string; ago: string; iso: string } | null
  /** Who wrote it, and their channel. */
  viewer: { name: string; href: string | null } | null
  /**
   * For a reply, the comment that opened its thread and who wrote it (2026-11-10, the maintainer: "which comment belong
   * to which conversation") - the question another viewer answered, say - or null.
   */
  thread: { author: string; text: string; href: string | null } | null
  /** The video it is on - by its title, or "Open video" when only its id is known. */
  video: { title: string; href: string | null } | null
  /**
   * The reply: labeled with whose draft it is ("Reply Writer's draft"), as posted once it was (with "View
   * reply" among its links), or as the reply to post when nobody is named. Null on a comment with none.
   */
  reply: ApprovalContextRow | null
  /** Any row this page does not fold into the comment (a key it does not know), drawn label over value. */
  rest: ApprovalContextRow[]
}

// What a comment's card folds into its title and caption line, or does not draw at all: the channel a reply
// is posted AS (`replyAs`) is always the channel's owner - one for another channel is refused before it is
// kept and withdrawn while it waits (ApprovalChannelTest) - so the card no longer says it.
const FOLDED = new Set(['comment', 'commentedAt', 'viewer', 'video', 'replyAs', 'reply', 'threadStart', 'threadAuthor'])

/**
 * The comment card for a context, or null when the context is not a comment's (isCommentContext). `author`
 * names whose draft the reply is - the agent that wrote it, on the Approvals page; `timeZone` and `now` are
 * for tests.
 */
export function commentCard(
  context: ApprovalContext | null | undefined,
  t: (s: string, params?: Record<string, string | number>) => string,
  locale: string,
  opts: { author?: string | null; now?: number; timeZone?: string } = {},
): CommentCard | null {
  if (!context || !isCommentContext(context)) return null
  const rows = approvalContextRows(context, t)
  const find = (key: string) => rows.find(r => r.key === key) ?? null
  const comment = rows.find(r => r.key === 'comment' && r.kind === 'QUOTE')!
  const when = find('commentedAt')
  const viewer = find('viewer')
  const video = find('video')
  const reply = find('reply')
  const opened = rows.find(r => r.key === 'threadStart' && r.kind === 'QUOTE') ?? null
  const opener = find('threadAuthor')
  const written = when ? commentTime(when.value, locale, opts.now ?? Date.now(), opts.timeZone) : null
  const author = opts.author?.trim()
  return {
    comment: { text: comment.value.trim(), href: comment.href },
    written,
    viewer: viewer ? { name: viewer.value.trim(), href: viewer.kind === 'LINK' ? viewer.href : null } : null,
    thread: opened && opener ? { author: opener.value.trim(), text: opened.value.trim(), href: opened.href } : null,
    video: video ? { title: video.value.trim(), href: video.kind === 'LINK' ? video.href : null } : null,
    reply: reply && (reply.href
      ? { ...reply, label: t('Reply posted'), links: [{ key: 'reply', label: t('View reply'), href: reply.href }] }
      : { ...reply, label: author ? t("{name}'s draft", { name: author }) : reply.label, links: [] }),
    // A date that is not one is not lost: it stays a row of its own, as it was.
    rest: rows.filter(r => !FOLDED.has(r.key) || (r === when && written === null)).map(r => ({ ...r, links: [] })),
  }
}

/** When a comment was written, in the reader's locale and as an age, or null when the value is not a time. */
export function commentTime(iso: string, locale: string, now: number = Date.now(), timeZone?: string): CommentCard['written'] {
  const ago = timeAgo(iso, locale, now)
  if (ago === null) return null
  const at = new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short', timeZone }).format(new Date(Date.parse(iso)))
  return { at, ago, iso }
}

function labelOf(key: string, t: (s: string) => string): string {
  switch (key) {
    case 'video': return t('Video')
    case 'viewer': return t('Viewer')
    case 'comment': return t('Their comment')
    case 'commentedAt': return t('Commented')
    case 'replyAs': return t('Reply as')
    case 'reply': return t('Reply to post')
    case 'threadStart': return t('Opening comment')
    case 'threadAuthor': return t('Opened by')
    default: return key
  }
}
