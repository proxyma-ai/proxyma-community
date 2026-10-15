// Run: node --test apps/app/frontend/src/features/settings/utils/mcpForm.test.ts
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'

import { argLines, buildMcpInput, initialMcpForm, mcpFormReady, namedMap } from './mcpForm.ts'
import type { McpView } from '../services/connectionsApi.ts'

const VIEW: McpView = {
  transport: 'STDIO', command: 'npx', args: ['-y', '@scope/server', 'C:\\My Docs'], auth: 'NONE',
  headerNames: [], envNames: ['API_KEY'], shared: false, signedIn: false,
  oauthClientId: null, oauthScope: null, oauthClientRegistered: false,
}

describe('an MCP server form', () => {
  test('reads back what the server returned, with every stored value left empty', () => {
    const form = initialMcpForm(VIEW, null)
    assert.equal(form.argsText, '-y\n@scope/server\nC:\\My Docs')
    assert.deepEqual(form.env, [{ name: 'API_KEY', value: '', stored: true }])
  })

  test('a stored value left empty is sent empty - which keeps it - and a removed one is not sent', () => {
    const form = initialMcpForm({ ...VIEW, envNames: ['API_KEY', 'OLD'] }, null)
    form.env = [form.env[0], { name: 'REGION', value: 'eu', stored: false }]
    assert.deepEqual(buildMcpInput(form, false).env, { API_KEY: '', REGION: 'eu' })
  })

  test('an argument is a whole line, spaces kept, blank lines dropped', () => {
    assert.deepEqual(argLines('  -y \r\n\n@scope/server\nC:\\My Docs\n'), ['-y', '@scope/server', 'C:\\My Docs'])
  })

  test('a row with no name is dropped, and a repeated name keeps its last value', () => {
    assert.deepEqual(namedMap([
      { name: ' ', value: 'x', stored: false },
      { name: 'X-Team', value: 'a', stored: false },
      { name: 'X-Team ', value: 'b', stored: false },
    ]), { 'X-Team': 'b' })
  })

  test('a local command sends no sign-in, and a remote server no command', () => {
    const local = buildMcpInput({ ...initialMcpForm(VIEW, null), auth: 'BEARER' }, false)
    assert.equal(local.auth, 'NONE')
    assert.equal(local.headers, undefined)

    const remote = buildMcpInput({ ...initialMcpForm(null, 'https://x.example/mcp'), auth: 'HEADERS',
      headers: [{ name: 'X-Key', value: 'v', stored: false }] }, false)
    assert.equal(remote.command, undefined)
    assert.equal(remote.env, undefined)
    assert.deepEqual(remote.headers, { 'X-Key': 'v' })
  })

  test('sharing is sent only by somebody who may share', () => {
    const form = { ...initialMcpForm(null, 'https://x.example/mcp'), shared: true }
    assert.equal(buildMcpInput(form, false).shared, undefined)
    assert.equal(buildMcpInput(form, true).shared, true)
  })

  test('a client the server registered itself is not shown as one typed in', () => {
    const view: McpView = { ...VIEW, transport: 'STREAMABLE_HTTP', auth: 'OAUTH', oauthClientId: 'dyn-1', oauthClientRegistered: true }
    assert.equal(initialMcpForm(view, 'https://x.example/mcp').oauthClientId, '')
    assert.equal(initialMcpForm({ ...view, oauthClientRegistered: false }, 'https://x.example/mcp').oauthClientId, 'dyn-1')
  })

  test('ready once it names an address or a command', () => {
    assert.equal(mcpFormReady(initialMcpForm(null, ' ')), false)
    assert.equal(mcpFormReady(initialMcpForm(null, 'https://x.example/mcp')), true)
    assert.equal(mcpFormReady({ ...initialMcpForm(VIEW, null), command: '' }), false)
  })
})
