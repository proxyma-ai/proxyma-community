#!/usr/bin/env node
// PROXYMA FOR CLAUDE DESKTOP: the bridge inside the extension Proxyma packs (AccessController.claudeExtension).
//
// Claude Desktop speaks MCP over standard input and output, one JSON-RPC message a line; Proxyma speaks it as
// Streamable HTTP at PROXYMA_URL, with the install's token. This passes each message across and each answer back -
// a JSON body or an event stream - and nothing else: no dependency, Node's own fetch (18 and later, which Claude
// Desktop ships). A Proxyma that restarted forgets the session, so the bridge opens a new one with the same
// initialize and sends the message again. ASCII only, like every file of the repository.
'use strict'

const url = process.env.PROXYMA_URL
const token = process.env.PROXYMA_TOKEN
let session = null
let initialize = null

function out(message) {
  process.stdout.write(JSON.stringify(message) + '\n')
}

function failed(message, text) {
  if (message && message.id !== undefined && message.id !== null) {
    out({ jsonrpc: '2.0', id: message.id, error: { code: -32000, message: text } })
  }
}

// The JSON-RPC messages of an event stream's body: each event's data lines joined.
function events(body) {
  const found = []
  for (const block of body.split(/\r?\n\r?\n/)) {
    const data = block.split(/\r?\n/).filter(l => l.startsWith('data:')).map(l => l.slice(5).replace(/^ /, '')).join('\n')
    if (data.trim()) {
      try { found.push(JSON.parse(data)) } catch { /* not a message: a comment or a keep-alive */ }
    }
  }
  return found
}

async function post(message) {
  const headers = {
    'Content-Type': 'application/json',
    Accept: 'application/json, text/event-stream',
    Authorization: 'Bearer ' + token,
  }
  if (session) headers['Mcp-Session-Id'] = session
  const res = await fetch(url, { method: 'POST', headers, body: JSON.stringify(message) })
  const sid = res.headers.get('mcp-session-id')
  if (sid) session = sid
  const text = await res.text()
  const type = res.headers.get('content-type') || ''
  const messages = !text.trim() ? [] : type.includes('text/event-stream') ? events(text) : [].concat(JSON.parse(text))
  return { status: res.status, messages }
}

async function forward(message) {
  if (message.method === 'initialize') {
    initialize = message
    session = null
  }
  let answer
  try {
    answer = await post(message)
    // A session Proxyma no longer knows - it restarted: a new one, then the message again.
    if ((answer.status === 404 || answer.status === 400) && session && initialize && message.method !== 'initialize') {
      session = null
      await post(initialize)
      await post({ jsonrpc: '2.0', method: 'notifications/initialized' })
      answer = await post(message)
    }
  } catch (e) {
    failed(message, 'Proxyma cannot be reached at ' + url + '. Open Proxyma and try again.')
    return
  }
  if (answer.status >= 400 && answer.messages.length === 0) {
    failed(message, answer.status === 401
      ? 'Proxyma refused the extension\'s token. Download the extension again from Proxyma: Settings > AI Assistants.'
      : 'Proxyma answered with HTTP ' + answer.status + '.')
    return
  }
  for (const m of answer.messages) out(m)
}

if (!url || !token) {
  process.stderr.write('PROXYMA_URL and PROXYMA_TOKEN are not set: download the extension again from Proxyma.\n')
}

// One message at a time, in order: an answer never overtakes the request before it.
let chain = Promise.resolve()
let pending = ''
process.stdin.setEncoding('utf8')
process.stdin.on('data', chunk => {
  pending += chunk
  let at
  while ((at = pending.indexOf('\n')) >= 0) {
    const line = pending.slice(0, at).trim()
    pending = pending.slice(at + 1)
    if (!line) continue
    let message
    try { message = JSON.parse(line) } catch { continue }
    chain = chain.then(() => forward(message))
  }
})
process.stdin.on('end', () => { chain.then(() => process.exit(0)) })
