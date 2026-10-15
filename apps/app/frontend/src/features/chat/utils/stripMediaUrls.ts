// The conversation-media route (/api/v1/chat/conversations/{id}/media/{fileName}) is how the chat
// UI renders an image/video/audio a tool fetched, but it is a server-internal, cookie-gated path
// that is dead outside this browser session - and it carries the real conversation id. Copying a
// response would otherwise paste the raw markdown with that URL in it, leaking both the route
// shape and the id. Rewrite every media reference to the label a reader actually wants: the
// model's own alt text when it wrote one, otherwise the file name from the URL (the file name is
// descriptive and not sensitive - only the route around it is).
//
// Applied on copy, and to a SHARED copy of a conversation - never to what the owner's own thread
// renders, where MarkdownRenderer needs the real URL to fetch the media (see its `isOwnMedia`
// branch).
//
// The files an agent made (`/files/`) and the uploads (`/attachments/`) are the same kind of path,
// owner-gated and carrying the real conversation id, so they are rewritten the same way (CHAT-16):
// a shared reader was offered a "Preview" chip that could only fail, and its URL named the
// conversation the share is built never to reveal.
const MEDIA_PATH = String.raw`\/api\/v1\/chat\/conversations\/[^\/\s)\]]+\/(?:media|files|attachments)\/`

// ![alt](url) and [text](url) alike - the leading '!' is optional so a media link that was written
// as a plain link (not an embed) is covered by the same pass.
const MEDIA_MARKDOWN_RE = new RegExp(String.raw`!?\[([^\]]*)\]\(${MEDIA_PATH}([^)\s]*)\)`, 'g')

// Second pass for a URL the model emitted as bare text rather than inside markdown syntax.
const MEDIA_BARE_RE = new RegExp(`${MEDIA_PATH}([^\\s)\\]]*)`, 'g')

function decode(fileName: string): string {
  try {
    return decodeURIComponent(fileName)
  } catch {
    return fileName
  }
}

export function stripConversationMediaUrls(markdown: string): string {
  return markdown
    .replace(MEDIA_MARKDOWN_RE, (_full, alt: string, fileName: string) =>
      alt.trim() || decode(fileName))
    .replace(MEDIA_BARE_RE, (_full, fileName: string) => decode(fileName))
}
