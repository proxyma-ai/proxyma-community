// A fake MCP server: the other end of an "MCP server" connection, for seeing the client work
// without installing anybody's real server. No dependencies; Node 22.
//
// It speaks all three transports Proxyma's MCP client does, and can demand an OAuth sign-in through
// a minimal authorization server of its own - enough to drive discovery, dynamic client
// registration, PKCE, the callback and token refresh end to end.
//
//   node tools/testing/fake-mcp.mjs --stdio                     a local server (Settings: command
//                                                               "node", argument the path to this file
//                                                               and --stdio)
//   node tools/testing/fake-mcp.mjs --port 8798                 streamable HTTP at /mcp, SSE at /sse
//   node tools/testing/fake-mcp.mjs --port 8798 --token secret  both demand "Authorization: Bearer secret"
//   node tools/testing/fake-mcp.mjs --port 8798 --oauth         both demand an OAuth sign-in, which
//                                                               /authorize approves at once
//
// TOOLS
//   echo {text}     the text back
//   add {a, b}      the sum
//   fail {}         a tool result marked as an error
//   wait {ms}       answers after this many milliseconds (at most a minute) - calls that finish apart
//   environment {}  what the process can see of its environment: the value of FAKE_MCP_GREETING,
//                   and whether any PROXYMA_*, SPRING_* or JAVA_TOOL_OPTIONS variable leaked into it.
//                   The client starts a local server with a clean environment; this is how to check.
//
// It is test tooling. It keeps everything in memory and trusts whoever calls it.

import http from 'node:http'
import crypto from 'node:crypto'
import { createInterface } from 'node:readline'

const args = process.argv.slice(2)
const flag = name => args.includes(name)
const value = (name, fallback) => {
  const i = args.indexOf(name)
  return i >= 0 && i + 1 < args.length ? args[i + 1] : fallback
}

const TOOLS = [
  {
    name: 'echo', title: 'Echo', description: 'Returns the text it is given.',
    inputSchema: { type: 'object', properties: { text: { type: 'string', description: 'What to echo' } }, required: ['text'] },
  },
  {
    name: 'add', description: 'Adds two numbers.',
    inputSchema: { type: 'object', properties: { a: { type: 'number' }, b: { type: 'number' } }, required: ['a', 'b'] },
  },
  { name: 'fail', description: 'Always reports an error.', inputSchema: { type: 'object', properties: {} } },
  {
    name: 'environment', description: 'Reports what the process can see of its environment.',
    inputSchema: { type: 'object', properties: {} },
  },
  {
    name: 'wait', description: 'Answers after the given number of milliseconds.',
    inputSchema: { type: 'object', properties: { ms: { type: 'number' } }, required: ['ms'] },
  },
]

async function callTool(name, input) {
  const text = t => ({ content: [{ type: 'text', text: t }] })
  switch (name) {
    case 'echo': return text(String(input?.text ?? ''))
    case 'add': return text(String(Number(input?.a ?? 0) + Number(input?.b ?? 0)))
    case 'fail': return { content: [{ type: 'text', text: 'This tool always fails.' }], isError: true }
    case 'wait': {
      const ms = Math.min(60_000, Math.max(0, Number(input?.ms ?? 0)))
      await new Promise(r => setTimeout(r, ms))
      return text(`Waited ${ms} ms.`)
    }
    case 'environment': {
      const leaked = Object.keys(process.env).filter(k => /^(PROXYMA|SPRING)_|^JAVA_TOOL_OPTIONS$/i.test(k))
      return text(JSON.stringify({
        greeting: process.env.FAKE_MCP_GREETING ?? null,
        leaked,
        variables: Object.keys(process.env).length,
      }))
    }
    default: return null
  }
}

// One JSON-RPC message in, the response out (null for a notification).
async function handle(message) {
  const { id, method, params } = message
  const reply = result => ({ jsonrpc: '2.0', id, result })
  const error = (code, msg) => ({ jsonrpc: '2.0', id, error: { code, message: msg } })
  if (id === undefined || id === null) return null
  switch (method) {
    case 'initialize':
      return reply({
        protocolVersion: params?.protocolVersion ?? '2025-06-18',
        capabilities: { tools: { listChanged: false } },
        serverInfo: { name: 'fake-mcp', version: '1.0.0' },
        instructions: 'A fake MCP server for tests. Its tools are echo, add, fail, environment and wait.',
      })
    case 'ping': return reply({})
    case 'tools/list': return reply({ tools: TOOLS })
    case 'tools/call': {
      const result = await callTool(params?.name, params?.arguments)
      return result ? reply(result) : error(-32602, `Unknown tool: ${params?.name}`)
    }
    default: return error(-32601, `Method not found: ${method}`)
  }
}

// -- stdio -------------------------------------------------------------------------------------------

if (flag('--stdio')) {
  const lines = createInterface({ input: process.stdin })
  lines.on('line', line => {
    if (!line.trim()) return
    let message
    try { message = JSON.parse(line) } catch { return }
    handle(message).then(out => { if (out) process.stdout.write(JSON.stringify(out) + '\n') })
  })
  process.stderr.write('fake-mcp: stdio ready\n')
} else {
  serve(Number(value('--port', '8798')))
}

// -- HTTP: streamable HTTP, SSE, and the optional OAuth server -------------------------------------------

function serve(port) {
  const token = value('--token', null)
  const oauth = flag('--oauth')
  const issued = new Map()     // access token -> expiry
  const refreshes = new Set()
  const clients = new Map()    // client_id -> redirect_uris
  const codes = new Map()      // code -> { challenge, clientId, redirectUri }
  const sse = new Map()        // sessionId -> response

  const server = http.createServer(async (req, res) => {
    const base = `http://${req.headers.host}`
    const url = new URL(req.url, base)
    const body = await readBody(req)
    const json = (status, obj, headers = {}) => {
      res.writeHead(status, { 'content-type': 'application/json', ...headers })
      res.end(JSON.stringify(obj))
    }

    // The OAuth half.
    if (url.pathname.startsWith('/.well-known/oauth-protected-resource')) {
      return oauth ? json(200, { resource: `${base}/mcp`, authorization_servers: [base], scopes_supported: ['tools'] })
        : json(404, {})
    }
    if (url.pathname === '/.well-known/oauth-authorization-server') {
      return oauth ? json(200, {
        issuer: base, authorization_endpoint: `${base}/authorize`, token_endpoint: `${base}/token`,
        registration_endpoint: `${base}/register`, code_challenge_methods_supported: ['S256'],
        grant_types_supported: ['authorization_code', 'refresh_token'],
      }) : json(404, {})
    }
    if (oauth && url.pathname === '/register' && req.method === 'POST') {
      const request = JSON.parse(body || '{}')
      const clientId = 'client-' + crypto.randomBytes(6).toString('hex')
      clients.set(clientId, request.redirect_uris ?? [])
      return json(201, { client_id: clientId, redirect_uris: request.redirect_uris })
    }
    if (oauth && url.pathname === '/authorize') {
      const q = url.searchParams
      const redirect = q.get('redirect_uri')
      if (!clients.get(q.get('client_id'))?.includes(redirect)) return json(400, { error: 'invalid_client' })
      if (q.get('code_challenge_method') !== 'S256') return json(400, { error: 'invalid_request' })
      const code = crypto.randomBytes(16).toString('hex')
      codes.set(code, { challenge: q.get('code_challenge'), clientId: q.get('client_id'), redirectUri: redirect })
      res.writeHead(302, { location: `${redirect}?code=${code}&state=${encodeURIComponent(q.get('state'))}` })
      return res.end()
    }
    if (oauth && url.pathname === '/token' && req.method === 'POST') {
      const form = new URLSearchParams(body)
      const mint = () => {
        const access = 'at-' + crypto.randomBytes(12).toString('hex')
        const refresh = 'rt-' + crypto.randomBytes(12).toString('hex')
        issued.set(access, Date.now() + 3600_000)
        refreshes.add(refresh)
        return json(200, { access_token: access, refresh_token: refresh, token_type: 'Bearer', expires_in: 3600 })
      }
      if (form.get('grant_type') === 'authorization_code') {
        const c = codes.get(form.get('code'))
        codes.delete(form.get('code'))
        const challenge = crypto.createHash('sha256').update(form.get('code_verifier') ?? '').digest('base64url')
        if (!c || c.challenge !== challenge || c.redirectUri !== form.get('redirect_uri')) return json(400, { error: 'invalid_grant' })
        return mint()
      }
      if (form.get('grant_type') === 'refresh_token' && refreshes.delete(form.get('refresh_token'))) return mint()
      return json(400, { error: 'invalid_grant' })
    }

    // Who may reach the MCP half.
    const authorized = () => {
      const header = req.headers.authorization ?? ''
      if (token) return header === `Bearer ${token}`
      if (oauth) {
        const t = header.replace(/^Bearer /, '')
        return (issued.get(t) ?? 0) > Date.now()
      }
      return true
    }
    if ((url.pathname === '/mcp' || url.pathname === '/sse' || url.pathname === '/messages') && !authorized()) {
      res.writeHead(401, {
        'www-authenticate': oauth
          ? `Bearer resource_metadata="${base}/.well-known/oauth-protected-resource", scope="tools"`
          : 'Bearer',
      })
      return res.end()
    }

    // Streamable HTTP: one JSON response per request, no server-sent stream.
    if (url.pathname === '/mcp') {
      if (req.method === 'GET') { res.writeHead(405); return res.end() }
      if (req.method === 'DELETE') { res.writeHead(200); return res.end() }
      let message
      try { message = JSON.parse(body) } catch { return json(400, { error: 'bad json' }) }
      const headers = message.method === 'initialize' ? { 'mcp-session-id': crypto.randomUUID() } : {}
      return handle(message).then(out => {
        if (!out) { res.writeHead(202, headers); return res.end() }
        return json(200, out, headers)
      })
    }

    // SSE: a stream on GET /sse that names the endpoint to POST to, and carries the responses.
    if (url.pathname === '/sse' && req.method === 'GET') {
      const sessionId = crypto.randomUUID()
      res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache', connection: 'keep-alive' })
      res.write(`event: endpoint\ndata: /messages?sessionId=${sessionId}\n\n`)
      sse.set(sessionId, res)
      req.on('close', () => sse.delete(sessionId))
      return
    }
    if (url.pathname === '/messages' && req.method === 'POST') {
      const stream = sse.get(url.searchParams.get('sessionId'))
      if (!stream) return json(404, { error: 'no such session' })
      res.writeHead(202)
      res.end()
      handle(JSON.parse(body || '{}')).then(out => {
        if (out) stream.write(`event: message\ndata: ${JSON.stringify(out)}\n\n`)
      })
      return
    }

    json(404, { error: 'not found' })
  })
  server.listen(port, '127.0.0.1', () => {
    console.log(`listening ${server.address().port}${oauth ? ' (oauth)' : token ? ' (token)' : ''}`)
  })
}

function readBody(req) {
  return new Promise(resolve => {
    const chunks = []
    req.on('data', c => chunks.push(c))
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
  })
}
