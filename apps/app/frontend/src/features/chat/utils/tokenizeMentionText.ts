export type MessageToken =
  | { kind: 'text'; value: string }
  | { kind: 'mention'; name: string }
  | { kind: 'skill'; name: string }

// Walks `value` and splits it into plain-text runs and "@Name"/"/Name" mention/skill tokens -
// shared by MentionRichInput's rebuildFromValue (turns tokens into contentEditable chip DOM
// nodes while composing) and MessageBubble (renders them as read-only badges once sent). Exact
// substring matching against the known `mentions`/`skillMentions` name lists - not a boundary
// regex - because a real connector/skill name can itself contain whitespace or punctuation, which
// a regex-only heuristic can't tell apart from ordinary surrounding text. Longest-name-first so a
// shorter name that happens to be a prefix of a longer one never wins the match.
export function tokenizeMentionText(value: string, mentions: string[], skillMentions: string[]): MessageToken[] {
  const names = [...mentions].sort((a, b) => b.length - a.length)
  const skillNames = [...skillMentions].sort((a, b) => b.length - a.length)
  const tokens: MessageToken[] = []
  let buf = ''
  let i = 0
  outer: while (i < value.length) {
    if (value[i] === '@') {
      for (const name of names) {
        const token = '@' + name
        if (value.startsWith(token, i)) {
          if (buf) { tokens.push({ kind: 'text', value: buf }); buf = '' }
          tokens.push({ kind: 'mention', name })
          i += token.length
          continue outer
        }
      }
    }
    if (value[i] === '/') {
      for (const name of skillNames) {
        const token = '/' + name
        if (value.startsWith(token, i)) {
          if (buf) { tokens.push({ kind: 'text', value: buf }); buf = '' }
          tokens.push({ kind: 'skill', name })
          i += token.length
          continue outer
        }
      }
    }
    buf += value[i]
    i++
  }
  if (buf) tokens.push({ kind: 'text', value: buf })
  return tokens
}
