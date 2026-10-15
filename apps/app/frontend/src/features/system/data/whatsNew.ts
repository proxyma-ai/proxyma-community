/**
 * What each version changed, in the words a user of it would use.
 *
 * <p><b>Bundled rather than fetched.</b> Proxyma runs on machines with no internet at all - that is
 * a product promise, not an edge case - so a panel that fetches its own contents would be blank
 * exactly where being offline is the point. The full notes live at proxyma.ai/docs/release-notes/
 * and the panel links to them for anyone who wants more.
 *
 * <p><b>Keep each line to one sentence, and keep it about the reader.</b> This is not the changelog
 * and not the commit log: no internal refactoring and no mechanism. <b>New and changed things only</b>
 * (the maintainer's rule, 2026-10-09): no fixes, no known issues and no upgrade notes about older
 * versions - say what the product does now, never what it used to get wrong.
 *
 * <p>A version absent from this map shows nothing, which is the correct behavior for a patch that
 * only fixed things.
 */
export interface WhatsNewEntry {
  /** One short line, shown as the panel's heading under the version. */
  summary: string
  /** Three or four at most. More than that is a changelog, and nobody reads a changelog in a modal. */
  highlights: string[]
}

export const WHATS_NEW: Record<string, WhatsNewEntry> = {
  '0.19.0': {
    summary: 'Run conversations side by side, and choose how much the assistant may change.',
    highlights: [
      'Leave a conversation working and start another; a badge beside Chat counts the ones still running.',
      'Beside the model, choose Ask, Auto or Read-only.',
      'What you were typing in the chat is kept when you go to another page.',
    ],
  },
  '0.18.1': {
    summary: 'Connect as many Jira, Confluence, Bitbucket and SVN servers as you use.',
    highlights: [
      'Settings > Connections lists your connections, each with its own server and token.',
      'Whether the assistant may read or write is set on each connection.',
    ],
  },
  '0.16.0': {
    summary: 'Use Proxyma from Claude, ChatGPT or Gemini, and on Desktop in a window of its own.',
    highlights: [
      'Settings > AI Assistants connects the assistant you already pay for to your sources.',
      'Desktop: Proxyma opens in its own window instead of a browser tab.',
      'A thinking model shows "Thinking" with a token count or the seconds so far.',
    ],
  },
  '0.15.0': {
    summary: 'Clearer costs, a faster graph view, and conversations that keep their history.',
    highlights: [
      'Usage & Cost charts cost over time and lists every call, cache use included.',
      'Condensing a conversation keeps every message on screen.',
      'Settings > Agents can keep an Anthropic conversation cached for 5 minutes or 1 hour.',
    ],
  },
  // The first version that can actually SHOW this panel - the code shipped in 0.4.1, so a
  // 0.4.0 install has no panel to open and its entry below is only ever reached by somebody
  // arriving on 0.4.0 from a later downgrade.
  '0.4.1': {
    summary: 'Proxyma now tells you what changed after an update.',
    highlights: [
      'This panel, the first time you open a new version.',
      'Check now and Download on the Updates page show that they are working.',
    ],
  },
  '0.4.0': {
    summary: 'Sources that live on more than one server, and Linux gets a proper installer.',
    highlights: [
      "A source can point at its own server and use its own credential - set it in that source's Configure dialog.",
      'Linux installs from a Debian package and updates itself from Settings > Updates.',
      'Conversations are named after what you asked about.',
    ],
  },
}

/**
 * The entry for a version, or null when there is nothing worth interrupting somebody for.
 *
 * <p>Tolerates a `v` prefix and any build suffix, so `v0.4.0` and `0.4.0-rc1` both find `0.4.0`.
 * The running version comes from the backend and its exact shape has changed before.
 */
export function whatsNewFor(version: string): WhatsNewEntry | null {
  if (!version) return null
  const normalized = version.replace(/^v/, '').split('-')[0]
  return WHATS_NEW[normalized] ?? null
}
