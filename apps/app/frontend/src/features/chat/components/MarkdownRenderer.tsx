import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { FileText, Image as ImageIcon } from 'lucide-react'
import { FilePreviewDialog } from './FilePreviewDialog'
import { ENV } from '@/config/env'
import { useSettingsModalStore } from '@/store/settingsModalStore'
import type { TabKey } from '@/features/settings/components/SettingsModal'
import {
  AudioPlayer, Chip, CodeBlock, ExternalLink, InlineCode, Lightbox, PROSE_CHAT, ProseTable, Tooltip, cn,
} from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import { parseBlocks, type Block, type ListBlock } from '../utils/parseMarkdownBlocks'
import { isConversationFileLink, mediaKind, mediaSource, type MediaKind } from '../utils/mediaSource'

interface Props {
  content: string
  className?: string
}

// Falls back to the URL's own filename when the model didn't give a real ![alt](...) description
// (e.g. it echoed the mediaUrl's own alt-less form) - always shows something more useful than a
// blank title in the audio player below.
function filenameFromUrl(url: string): string {
  try {
    const path = new URL(url, window.location.origin).pathname
    const last = path.split('/').filter(Boolean).pop()
    return last ? decodeURIComponent(last) : url
  } catch {
    return url
  }
}

// Click-to-fullscreen for chat images (webFetch results, generated charts, etc.) - a thumbnail
// alone doesn't let the user actually read a dense screenshot/diagram at chat-bubble width.
//
// The image itself gets no frame at all - no border, no corner radius. It used to carry
// rounded-control, which resolves to a 9999px pill for Proxyma and turned every small square
// image (e.g. a 160x160 logo) into a circle with its corners cut off; a plain rounded-lg box
// still read as a card wrapped around the picture rather than as the picture. An image is its
// own object here, so it's rendered bare. The video below keeps its frame - that one is a
// player, and its own controls chrome needs an edge to sit against.
function InlineImage({ src, alt, crossOrigin, remote, onError }: {
  src: string; alt: string; crossOrigin?: 'use-credentials'; remote?: boolean; onError?: () => void
}) {
  const [open, setOpen] = useState(false)
  const { t } = useTranslation()

  return (
    <>
      {/* A BUTTON round the picture, so a keyboard reaches the full-size view too (CHAT-11) - it
          was an <img> with a click handler. Named by the picture's own words. */}
      <button
        type="button"
        onClick={() => setOpen(true)}
        aria-label={alt ? t('Open image: {name}', { name: alt }) : t('Open image')}
        className="block max-w-full my-2 cursor-zoom-in focus-ring rounded-sm"
      >
        <img
          src={src} alt={alt} crossOrigin={crossOrigin}
          // A picture from another host was asked for by the reader; it still gets no referrer.
          referrerPolicy={remote ? 'no-referrer' : undefined}
          onError={onError}
          className={cn('max-w-full mouse:hover:opacity-90 transition-opacity', remote && 'animate-fade-in')}
        />
      </button>
      {open && (
        // The kit's Lightbox: no panel, no close button (no dialog has one) - a click anywhere
        // round the image, or Escape, closes it, and focus goes to the image.
        <Lightbox src={src} alt={alt} crossOrigin={crossOrigin} ariaLabel={t('Image')} onClose={() => setOpen(false)} />
      )}
    </>
  )
}

// The kit's AudioPlayer (title + play/pause) in place of the browser's bare <audio controls> bar,
// which shows no title at all - the user has no way to tell what an audio result is without
// playing it first.
function InlineAudio({ src, title, crossOrigin }: { src: string; title: string; crossOrigin?: 'use-credentials' }) {
  const { t } = useTranslation()
  return <AudioPlayer src={src} title={title} crossOrigin={crossOrigin} labels={{ play: t('Play'), pause: t('Pause') }} />
}

// This deployment's own picture, sound or video, drawn the way its kind is drawn everywhere in an
// answer - with the reader's cookies, since the endpoint checks the conversation is theirs.
function OwnMedia({ kind, src, alt, title }: { kind: MediaKind; src: string; alt: string; title: string }) {
  if (kind === 'video') {
    return <video src={src} controls crossOrigin="use-credentials" className="max-w-full rounded-lg border border-line/30 my-2" />
  }
  if (kind === 'audio') return <InlineAudio src={src} title={title} crossOrigin="use-credentials" />
  return <InlineImage src={src} alt={alt} crossOrigin="use-credentials" />
}

// MEDIA FROM ANOTHER HOST LOADS ONLY WHEN THE READER ASKS, AND THE ASK NAMES THE HOST (2026-10-14,
// security review swx-02; `mediaSource.ts` has the reasoning). Drawn inline as soon as the line
// streamed in, it was a request to wherever the model was told to send one, carrying whatever the
// model was told to put in its address.
// - A picture is a chip that says where the request would go, the whole address its tooltip; once
//   asked for it is drawn here, with no cookies and no referrer. A picture that then does not load -
//   gone, or refused by the page's Content-Security-Policy - becomes a link to open it in a tab.
// - A sound or a video is that link from the start: a player of another host's media on this page
//   is more than a reader asked for by clicking.
function RemoteMedia({ kind, url, host, alt, title }: { kind: MediaKind; url: string; host: string; alt: string; title: string }) {
  const [state, setState] = useState<'asked' | 'shown' | 'failed'>('asked')
  const { t } = useTranslation()
  if (kind !== 'image' || state === 'failed') {
    return (
      <ExternalLink href={url} title={title ? `${title} - ${url}` : url} className={cn('text-brand-legible', state === 'failed' && 'animate-fade-in')}>
        {kind === 'video' ? t('Video from {host}', { host }) : kind === 'audio' ? t('Audio from {host}', { host }) : t('Image from {host}', { host })}
      </ExternalLink>
    )
  }
  if (state === 'shown') return <InlineImage src={url} alt={alt} remote onError={() => setState('failed')} />
  return (
    <Tooltip label={url}>
      <Chip variant="file" icon={<ImageIcon />} label={t('Load image from {host}', { host })} onClick={() => setState('shown')} className="max-w-[24rem] my-1" />
    </Tooltip>
  )
}

// In-app destinations, not web addresses: a tool that fails for a missing credential hands the
// agent an `app://settings/<tab-slug>` markdown link (see SettingsLinks.java on the backend), and
// the link below opens the Settings modal on that tab. Without this, the user got a
// "Settings > Connector Credentials" breadcrumb to go hunting for; with it, they are one click from
// the field to fill. Settings is a modal with no route of its own (see settingsModalStore), which
// is why this is a scheme handled here rather than an ordinary href.
//
// The map is the whole contract: an unrecognized slug falls through to the plain-text branch, the
// same as any other non-http href, so a hallucinated `app://` link can never render as something
// that looks clickable but goes nowhere.
const SETTINGS_TAB_BY_SLUG: Record<string, TabKey> = {
  'account':              'Account',
  'connectors':           'Connections',
  // SLUGS THAT NO LONGER NAME A TAB, all pointing at the page their settings moved to. They are
  // baked into the stored history of every conversation where a tool ever reported a missing token
  // or a disabled write tool, and an unrecognized slug falls through to the plain-text branch
  // below - so dropping one quietly turns each of those buttons back into the breadcrumb it was
  // introduced to replace.
  'connector-credentials': 'Connections',
  'tools-permission':     'Connections',
  'mcp-tools':            'Connections',
  // The 2026-09-02 tab merges. Same rule as the three above: the destination absorbed the
  // settings, so the old slug still lands somewhere that answers the question it was asked about.
  'privacy':              'About',
  'documentation':        'About',
  'search-engine':        'Search',
  'embedding':            'Search',
  'cost-settings':        'Usage & Cost',
  'skills':               'Skills',
  'memory':               'Memory',
  'api-tokens':           'API Tokens',
  'ai-assistants':        'AI Assistants',
  // 'providers' was the tab's name until 0.13.0; links written before then still land on it.
  'providers':            'AI Providers',
  'llm-gateway':          'AI Providers',
  'agents':               'Agents',
  'retrieval':            'Search',
  'network':              'Network',
  'license':              'License',
  // Only the build with add-ons lists the tab; elsewhere the modal falls back to its default tab.
  'add-ons':              'Add-ons',
  'usage-and-cost':       'Usage & Cost',
  'logs':                 'Logs',
  // The tab was Audit Logs until 2026-10-14; a link written before then still lands on it.
  'audit-logs':           'Logs',
}

// The optional second segment is a focus target within the tab - on Connections, a connection's id
// (its edit dialog), a service key ('jira', 'confluence', ...) or a section ('smb',
// 'connector-management'). A UUID is lowercase hex and dashes, which the pattern already admits.
// It is passed through uninterpreted: the receiving tab decides what (if anything) it means, and
// ignores one it doesn't recognize, so an unknown focus segment still opens the tab rather than
// falling through to plain text.
function settingsTargetFromHref(href: string): { tab: TabKey; focus?: string } | null {
  const m = href.match(/^app:\/\/settings\/([a-z-]+)(?:\/([a-z0-9-]+))?\/?$/i)
  if (!m) return null
  const tab = SETTINGS_TAB_BY_SLUG[m[1].toLowerCase()]
  return tab ? { tab, focus: m[2]?.toLowerCase() } : null
}

// An earlier conversation the agent found with searchConversations - opened in the app, not as a
// web address. Only a UUID-shaped id is accepted, so nothing else can pose as one.
const CONVERSATION_LINK_RE = /^app:\/\/chat\/([0-9a-f-]{36})\/?$/i

function renderInline(text: string): (string | JSX.Element)[] {
  const result: (string | JSX.Element)[] = []
  let remaining = text
  let key = 0

  while (remaining.length > 0) {
    const boldMatch = remaining.match(/\*\*(.+?)\*\*/)
    // Single-asterisk only, never _underscore_: identifiers like snake_case_name and
    // __init__ are everywhere in this product's content, and treating those as emphasis
    // mangles them. The lookarounds keep this off the asterisks of a **bold** run, so bold
    // and italic can't fight over the same characters.
    // The inner (?!\s) / (?<!\s) follow standard markdown in requiring the delimiters to hug their
    // text: without them "2 * 3 * 4" and shell globs turn into accidental emphasis, which is a
    // realistic thing to find in this product's content.
    const italicMatch = remaining.match(/(?<!\*)\*(?!\*)(?!\s)([^*\n]+?)(?<!\s)\*(?!\*)/)
    const codeMatch = remaining.match(/`([^`]+)`/)
    // Checked ahead of linkMatch below: for the same "![alt](url)" occurrence, this match starts
    // at the '!' (one index earlier) so it always wins the idx-sort and consumes the whole thing,
    // rather than leaving a stray '!' before a plain link render.
    const imageMatch = remaining.match(/!\[([^\]]*)\]\(([^)]+)\)/)
    const linkMatch = remaining.match(/\[([^\]]+)\]\(([^)]+)\)/)

    const matches = [
      boldMatch ? { idx: boldMatch.index!, len: boldMatch[0].length, type: 'bold' as const, match: boldMatch } : null,
      italicMatch ? { idx: italicMatch.index!, len: italicMatch[0].length, type: 'italic' as const, match: italicMatch } : null,
      codeMatch ? { idx: codeMatch.index!, len: codeMatch[0].length, type: 'code' as const, match: codeMatch } : null,
      imageMatch ? { idx: imageMatch.index!, len: imageMatch[0].length, type: 'image' as const, match: imageMatch } : null,
      linkMatch ? { idx: linkMatch.index!, len: linkMatch[0].length, type: 'link' as const, match: linkMatch } : null,
    ].filter(Boolean).sort((a, b) => a!.idx - b!.idx)

    if (matches.length === 0) {
      result.push(remaining)
      break
    }

    const first = matches[0]!
    if (first.idx > 0) {
      result.push(remaining.slice(0, first.idx))
    }

    if (first.type === 'bold') {
      // Weight alone did not read as emphasis: an agent message's body is ink-secondary, and
      // semibold in the SAME color barely separated from it at chat text size. Emphasis now shifts
      // COLOR as well as weight, to ink-primary - the strongest text token, which each theme
      // defines as darker than body in light mode and brighter in dark mode, so bold stands out in
      // both without this component knowing which is active.
      // Recurse into the captured span so nested markdown (e.g. a **[link](url)** citation)
      // still gets parsed instead of showing up as literal bracket/paren text inside the <strong>.
      result.push(<strong key={key++} className="font-bold text-ink-primary">{renderInline(first.match[1])}</strong>)
    } else if (first.type === 'italic') {
      // Same problem, worse: a slant at 13px is nearly invisible against body color, so italic
      // takes the ink-primary lift too (without the weight, which is what still distinguishes it
      // from bold).
      // Recursed for the same reason as bold above.
      result.push(<em key={key++} className="italic text-ink-primary">{renderInline(first.match[1])}</em>)
    } else if (first.type === 'code') {
      result.push(<InlineCode key={key++}>{first.match[1]}</InlineCode>)
    } else if (first.type === 'image') {
      // webFetch/webSearch (see ../proxyma-docs/architecture/mcp-and-api.md) return media as a relative
      // /api/v1/chat/conversations/{id}/media/{fileName} URL scoped to this conversation - resolve
      // it against the API origin (may differ from the frontend's own origin in server mode) and
      // send credentials, since the endpoint is conversation-ownership-gated by cookie auth. The
      // agent's own files and the user's attachments are drawn the same way.
      // An image on any other host is NOT drawn until the reader asks (RemoteMedia, swx-02).
      const alt = first.match[1]
      const rawSrc = first.match[2]
      const source = mediaSource(rawSrc)
      const kind = mediaKind(rawSrc)
      const title = alt || filenameFromUrl(rawSrc)
      if (source.kind === 'own') {
        result.push(
          <OwnMedia key={key++} kind={kind} src={`${ENV.apiBaseUrl}${source.path}`} alt={alt} title={title} />
        )
      } else if (source.kind === 'remote') {
        result.push(
          <RemoteMedia key={key++} kind={kind} url={source.url} host={source.host} alt={alt} title={title} />
        )
      } else {
        result.push(alt || rawSrc)
      }
    } else if (first.type === 'link') {
      // The system prompt tells the model to only ever emit a real http(s) URL here (never a
      // bare filename/local path, e.g. for a local-file citation) - but that's an instruction,
      // not a guarantee. Render anything else as plain text instead of a dead/misleading <a
      // href> that would otherwise resolve relative to the current page (making a bare filename
      // look like a server path was exposed).
      const href = first.match[2]
      const settingsTarget = settingsTargetFromHref(href)
      if (settingsTarget) {
        // A button, not an <a>: the destination is a modal in this app, so there is no URL to
        // navigate to, middle-click, or copy. Styled exactly like the anchor below so it reads as
        // the same kind of link in the middle of a sentence.
        result.push(
          <button key={key++} type="button"
                  onClick={() => useSettingsModalStore.getState().open(settingsTarget.tab, settingsTarget.focus)}
                  className={PROSE_CHAT.link}>
            {renderInline(first.match[1])}
          </button>
        )
      } else if (CONVERSATION_LINK_RE.test(href)) {
        result.push(
          <Link key={key++} to={`/chat/${href.match(CONVERSATION_LINK_RE)![1].toLowerCase()}`}
                className={PROSE_CHAT.link}>
            {renderInline(first.match[1])}
          </Link>
        )
      } else if (isConversationFileLink(href)) {
        // A file in this conversation - one the agent made with runPython, or an upload - opens in
        // the preview, which has the download. A chip rather than an anchor: the endpoint serves
        // it as an attachment, so following the link would only download it.
        result.push(<ConversationFileLink key={key++} href={href} label={first.match[1]} />)
      } else if (/^https?:\/\//i.test(href)) {
        // Citations and web results in an answer leave the app, so they carry the same out-arrow
        // as every other outbound link. It matters more here than anywhere else: a link inside
        // generated prose is the one place a reader cannot tell from context where it goes.
        result.push(
          <ExternalLink key={key++} href={href} className="text-brand-legible">
            {renderInline(first.match[1])}
          </ExternalLink>
        )
      } else {
        result.push(...renderInline(first.match[1]))
      }
    }

    remaining = remaining.slice(first.idx + first.len)
  }

  return result
}

// The kit's PROSE_CHAT list: list-outside + explicit left padding rather than the previous
// list-inside, where a wrapped line ran back under its own marker, which is what made long bullets
// hard to scan, and nested levels had no visual offset at all. Only the outermost list carries
// vertical margin, so a nested list sits tight under its parent item instead of opening a gap
// mid-list.
function ListRenderer({ list, depth }: { list: ListBlock; depth: number }) {
  const cls = PROSE_CHAT.list(list.ordered, depth)
  const items = list.items.map((item, i) => (
    <li key={i}>
      {renderInline(item.text)}
      {item.children?.map((child, ci) => (
        <ListRenderer key={ci} list={child} depth={depth + 1} />
      ))}
    </li>
  ))
  return list.ordered ? <ol className={cls}>{items}</ol> : <ul className={cls}>{items}</ul>
}

function BlockRenderer({ block }: { block: Block }) {
  const { t } = useTranslation()
  switch (block.type) {
    case 'heading': {
      // The kit's type scale (PROSE_CHAT): a title, a section, then a 14px semibold heading at the
      // body size. REAL HEADINGS, so a long answer can be walked by heading (CHAT-31) - they were
      // divs. Three levels down from the page's own, since an answer sits inside a thread.
      const cls = block.level === 1 ? PROSE_CHAT.h1 : block.level === 2 ? PROSE_CHAT.h2 : PROSE_CHAT.h3
      const Heading = block.level === 1 ? 'h3' : block.level === 2 ? 'h4' : 'h5'
      return <Heading className={cls}>{renderInline(block.content)}</Heading>
    }
    case 'code':
      return (
        // The kit CodeBlock, wrapped, so it never scrolls sideways: no overflow-x, and no
        // scrollbar to hide. With its copy button (CHAT-14): a command the agent writes is one a
        // reader is about to run, and selecting it by hand was the only way to take it.
        <CodeBlock wrap size="sm" className="my-2" text={block.content} copyLabel={t('Copy code')} copiedLabel={t('Copied')}>{block.content}</CodeBlock>
      )
    case 'list':
      return block.list ? <ListRenderer list={block.list} depth={0} /> : null
    case 'blockquote':
      return (
        <div className={PROSE_CHAT.quote}>
          {renderInline(block.content)}
        </div>
      )
    case 'hr':
      return <hr className={PROSE_CHAT.hr} />
    case 'table':
      // Ruled between rows, with a caption-ink header - the kit's ProseTable (`.prose-doc table`'s
      // look); it was a full grid with a filled header row.
      return (
        <ProseTable
          headers={(block.headers ?? []).map(h => renderInline(h))}
          rows={(block.rows ?? []).map(row => row.map(cell => renderInline(cell)))}
          nowrap={(ri, ci) => isOneToken(block.rows?.[ri]?.[ci] ?? '')}
        />
      )
    case 'paragraph':
    default:
      return <p className={PROSE_CHAT.p}>{renderInline(block.content)}</p>
  }
}

// A cell holding ONE short token - an issue key, a version, a date, a file name - never wraps. At
// phone width "PROJ-318" broke at its hyphen into "PROJ-" over "318" (the review's F25), which
// reads as two values. Judged on the cell's visible text, so a linked, bold key counts; a cell of
// prose still wraps, and a table wider than the thread scrolls sideways in its HScroll.
function isOneToken(cell: string): boolean {
  const text = cell.replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1').replace(/[*_`]/g, '').trim()
  return text.length > 0 && text.length <= 32 && !/\s/.test(text)
}

export function MarkdownRenderer({ content, className }: Props) {
  const blocks = useMemo(() => parseBlocks(content), [content])

  return (
    <div className={className}>
      {blocks.map((block, i) => <BlockRenderer key={i} block={block} />)}
    </div>
  )
}

// /api/v1/chat/conversations/{id}/files/{name} (runPython's outputs) or .../attachments/{name} -
// exactly that shape (`isConversationFileLink`), since the preview fetches it with the cookies.
function ConversationFileLink({ href, label }: { href: string; label: string }) {
  const [open, setOpen] = useState(false)
  const { t } = useTranslation()
  const name = decodeURIComponent(href.slice(href.lastIndexOf('/') + 1))
  return (
    // The kit's file chip, the same one - at the same 14rem cap - an attachment is in the composer
    // and in a sent message (CHAT-30).
    <>
      <Tooltip label={t('Preview {name}', { name })}>
        <Chip variant="file" icon={<FileText />} label={label || name} onClick={() => setOpen(true)} className="max-w-56" />
      </Tooltip>
      {open && <FilePreviewDialog source={{ name, url: href }} onClose={() => setOpen(false)} />}
    </>
  )
}
