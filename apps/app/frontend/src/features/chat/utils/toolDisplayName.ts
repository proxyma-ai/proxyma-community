// How a tool's backend name is rendered anywhere the user sees it: the tool row in the thread,
// the detail modal's title, the enable/consent prompt. Extracted here because the identical
// toPascalCase helper was copy-pasted into all three - which meant a display override added to
// one of them would silently disagree with the other two (the row saying "Agent" while the modal
// it opens still said "Delegate").

// Names whose camelCase form is not what we want the user to read. Keyed by the exact backend
// tool name. Everything absent from this map falls through to the derived form below, so adding
// a tool needs no entry here unless its name reads badly.
const DISPLAY_NAME_OVERRIDES: Record<string, string> = {
  // "Delegate" names the mechanism (the main agent handing work off); "Agent" names what the user
  // actually sees happen - a second agent doing a piece of the task, with its own tool calls
  // nested under this row.
  delegate: 'Agent',
  // The REST passthroughs. "Call Bitbucket Api" names the mechanism; the modal it titles shows the
  // method and path underneath, so the title only has to say whose API it was.
  callJiraApi: 'Jira request',
  callConfluenceApi: 'Confluence request',
  callBitbucketApi: 'Bitbucket request',
}

// Words a derived name must not lower-case, or abbreviations nobody says out loud. Shared with
// toolActivity's derive(), so a row and the title of the modal it opens spell a product the same way.
export const NOUNS: Record<string, string> = {
  jira: 'Jira',
  confluence: 'Confluence',
  bitbucket: 'Bitbucket',
  sharepoint: 'SharePoint',
  svn: 'SVN',
  teams: 'Teams',
  url: 'URL',
  pr: 'pull request',
  prs: 'pull requests',
  repo: 'repository',
  repos: 'repositories',
}

// searchIndexedSources -> "Search indexed sources", searchJiraIssues -> "Search Jira issues",
// read_text_file -> "Read text file". SENTENCE CASE since 2026-09-30, like every other title in the
// app: this is a display title (the detail modal, a failed row, the enable prompt, the admin
// feature list), and "Search Jira Issues" was the one Title Case heading left in the thread.
function humanize(name: string): string {
  const words = name.replace(/([a-z0-9])([A-Z])/g, '$1 $2').split(/[\s_-]+/).filter(Boolean)
  const text = words.map(w => NOUNS[w.toLowerCase()] ?? w.toLowerCase()).join(' ')
  return text.charAt(0).toUpperCase() + text.slice(1)
}

// `t` is optional and applies ONLY to the override map, which is the finite hand-written half.
// `humanize` builds a title out of the tool's own backend name at runtime ("searchIndexedSources"
// -> "Search indexed sources"), so there is no key for a catalog to hold - the same limit
// toolActivity's derive() has, and documented at the top of that file.
export function toolDisplayName(name: string, t?: (s: string) => string): string {
  const override = DISPLAY_NAME_OVERRIDES[name]
  if (override) return t ? t(override) : override
  const mcp = mcpToolParts(name)
  if (mcp) return `${mcp.server}: ${humanize(mcp.tool)}`
  return humanize(name)
}

/**
 * A tool of a connected MCP server, as the backend advertises it (McpToolNames):
 * `mcp__<server slug>_<4 hex of the connection id>__<tool>`. The server is given back as the words
 * of its slug - the connection's name, lower-cased - and the tool as the server named it. Null for
 * any other tool.
 */
export function mcpToolParts(name: string): { server: string; tool: string } | null {
  const m = /^mcp__(.+?)__(.+)$/.exec(name)
  if (!m) return null
  const server = m[1].replace(/_[0-9a-f]{4}$/, '').split(/[_-]+/).filter(Boolean).join(' ')
  return { server: server || 'MCP', tool: m[2] }
}
