import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { KeyRound } from 'lucide-react'
import {
  Button, Callout, Caption, CodeBlock as KitCodeBlock, CopyField, ExternalLink, FadeSwap, Presence, SettingsSections,
  StepItem, StepList, SubNavTabs, markIcon,
} from '@/components/ui/kit'
import { FEATURES } from '@/config/features'
import { useTranslation } from '@/i18n'
import { accessApi } from '@/services/accessApi'
import type { TabKey } from './SettingsModal'

// "Use the assistant you already pay for." Claude, ChatGPT and Gemini subscriptions come with no
// API key a third-party app may use, so a subscriber has nothing to paste into the AI Providers -
// but each of those vendors ships a client that speaks MCP, and Proxyma is an MCP server. This
// tab turns that around: the assistant keeps its own model and subscription, and reaches the
// user's indexed sources through /mcp.
//
// THE SNIPPETS ARE EACH CLIENT'S OWN DOCUMENTED FORMAT, checked against its documentation on
// 2026-09-26, and they differ in ways that fail silently: Gemini CLI wants `httpUrl` (its `url`
// means SSE), Copilot's key is `servers` and not `mcpServers`, Claude Desktop reaches a local HTTP
// server only through the mcp-remote bridge, and Codex's config is shared by the ChatGPT desktop
// app, the Codex CLI and its IDE extension. Change one only against that client's current docs.
//
// The address is this page's own origin, so it is right for whoever is reading: localhost on
// Desktop, the server's address on Home Server and Enterprise Server.
//
// EVERY EDITION NEEDS A TOKEN NOW. Enterprise Server's are per person, made under API Tokens.
// Desktop and the Home Server have one, the install's own (AccessGate on the backend, since
// 2026-10-14 - /mcp used to answer anyone who could reach the port), and the examples carry it.

type Client = 'claude-code' | 'claude-desktop' | 'chatgpt' | 'gemini' | 'copilot'

const TOKEN = '<your-access-token>'
const LOOPBACK = new Set(['localhost', '127.0.0.1', '[::1]', '::1'])

export function AssistantsTab({ onOpenTab }: { onOpenTab: (tab: TabKey) => void }) {
  const { t } = useTranslation()
  const [client, setClient] = useState<Client>('claude-code')
  const url = `${window.location.origin}/mcp`
  const { data: installToken } = useQuery({
    queryKey: ['access-token'],
    queryFn: accessApi.token,
    enabled: !FEATURES.auth,
    staleTime: Infinity,
  })
  const needsToken = true
  const token = FEATURES.auth ? TOKEN : (installToken ?? TOKEN)
  // A Home Server read from another machine answers /mcp only with PROXYMA_EXPOSE_MCP on.
  const remoteWithoutAuth = !FEATURES.auth && !LOOPBACK.has(window.location.hostname)

  // ONE FAMILY OF MARKS, the kit's (BrandMark.tsx records each one's source and terms). Claude
  // Code, Claude and Copilot are the vendors' own marks; OpenAI and Google give no license to show
  // theirs, so ChatGPT and Gemini are generic glyphs drawn in the same filled style rather than
  // lucide outlines beside three filled marks.
  const tabs = [
    { key: 'claude-code' as const, label: 'Claude Code', icon: markIcon('claudecode') },
    { key: 'claude-desktop' as const, label: 'Claude Desktop', icon: markIcon('claude') },
    { key: 'chatgpt' as const, label: 'ChatGPT', icon: markIcon('chatgpt') },
    { key: 'gemini' as const, label: 'Gemini', icon: markIcon('gemini') },
    { key: 'copilot' as const, label: 'GitHub Copilot', icon: markIcon('copilot') },
  ]

  return (
    // Opens like every other tab with an introduction - SettingsSections' intro, at the caption
    // size; it was a 14px paragraph here alone. Then two sections: where the assistants connect,
    // and how each one is set up.
    <SettingsSections intro={<>
      {t('Claude, ChatGPT and Gemini can search your sources through Proxyma, on their own subscription.')}{' '}
      {t('What they read from your sources goes to their provider.')}
    </>}>
      <div className="space-y-4">
      {/* A value to copy, shown as a field - the kit's CopyField. */}
      <CopyField
        label={t('Proxyma address for assistants')}
        value={url}
        copyLabel={t('Copy')}
        copiedLabel={t('Copied')}
      />

      <Presence show={FEATURES.auth}>
        <Callout
          tone="neutral"
          actions={(
            <Button variant="secondary" onClick={() => onOpenTab('API Tokens')}>
              <KeyRound size={14} aria-hidden="true" />
              {t('Open API Tokens')}
            </Button>
          )}
        >
          {t('Each assistant needs an access token. Generate one under API Tokens and put it where the examples say {token}.', { token: TOKEN })}
        </Callout>
      </Presence>

      <Presence show={!!remoteWithoutAuth}>
        <Callout tone="warning">
          {t('You are on another machine. Assistants work here only if the server sets PROXYMA_EXPOSE_MCP=true, which lets anyone holding this token use its tools.')}
        </Callout>
      </Presence>
      </div>

      <div className="space-y-4">
        <SubNavTabs tabs={tabs} active={client} onChange={setClient} />
        <FadeSwap swapKey={client}>
          {client === 'claude-code' && <ClaudeCode url={url} needsToken={needsToken} token={token} />}
          {client === 'claude-desktop' && <ClaudeDesktop url={url} needsToken={needsToken} token={token} />}
          {client === 'chatgpt' && <ChatGpt url={url} needsToken={needsToken} token={token} />}
          {client === 'gemini' && <Gemini url={url} needsToken={needsToken} token={token} />}
          {client === 'copilot' && <Copilot url={url} needsToken={needsToken} token={token} />}
        </FadeSwap>
        <Caption>
          {t('ChatGPT and Gemini on the web cannot reach a server on your computer. Use the apps above instead.')}{' '}
          <ExternalLink href="https://proxyma.ai/docs/api/#mcp">{t('MCP setup guide')}</ExternalLink>
        </Caption>
      </div>
    </SettingsSections>
  )
}

interface ClientProps { url: string; needsToken: boolean; token: string }

function ClaudeCode({ url, needsToken, token }: ClientProps) {
  const { t } = useTranslation()
  const command = `claude mcp add --transport http proxyma ${url}`
    + (needsToken ? ` --header "Authorization: Bearer ${token}"` : '')
  return (
    <StepList intro={t("Anthropic's coding assistant, in the terminal and in VS Code. It comes with a Claude Pro or Max subscription.")}>
      <StepItem text={t('Run this in a terminal:')}><CodeBlock text={command} /></StepItem>
      <StepItem text={t('Start Claude Code and ask it about your documents.')} />
    </StepList>
  )
}

function ClaudeDesktop({ url, needsToken, token }: ClientProps) {
  const { t } = useTranslation()
  const args = ['mcp-remote', url]
  if (url.startsWith('http://')) args.push('--allow-http')
  // The header travels in an environment variable: Claude Desktop on Windows does not escape
  // spaces inside args, so "Authorization: Bearer x" would arrive split in three.
  if (needsToken) args.push('--header', 'Authorization:${AUTH_HEADER}')
  const server: Record<string, unknown> = { command: 'npx', args }
  if (needsToken) server.env = { AUTH_HEADER: `Bearer ${token}` }
  const config = JSON.stringify({ mcpServers: { proxyma: server } }, null, 2)
  return (
    <StepList intro={t('The Claude app for Windows and macOS. It reaches Proxyma through a small bridge that needs Node.js 18 or later.')}>
      <StepItem text={t('Install Node.js from nodejs.org if you do not have it.')} />
      <StepItem text={t('In Claude, open Settings > Developer > Edit Config, and add this to the file:')}><CodeBlock text={config} /></StepItem>
      <StepItem text={t('Quit Claude completely and open it again.')} />
    </StepList>
  )
}

function ChatGpt({ url, needsToken }: ClientProps) {
  const { t } = useTranslation()
  const command = `codex mcp add proxyma --url ${url}`
    + (needsToken ? ' --bearer-token-env-var PROXYMA_TOKEN' : '')
  return (
    <StepList intro={t('The ChatGPT desktop app, the Codex CLI and the Codex extension for VS Code share one setting. Sign in with your ChatGPT account.')}>
      <StepItem text={t('Install the Codex CLI, then run this in a terminal:')}><CodeBlock text={command} /></StepItem>
      {needsToken && (
        <StepItem text={t('Set the environment variable PROXYMA_TOKEN to your access token, then restart the app.')} />
      )}
      <StepItem text={t('Proxyma now appears in the ChatGPT desktop app, the Codex CLI and the Codex extension.')} />
    </StepList>
  )
}

function Gemini({ url, needsToken, token }: ClientProps) {
  const { t } = useTranslation()
  const command = 'gemini mcp add -s user --transport http'
    + (needsToken ? ` --header "Authorization: Bearer ${token}"` : '')
    + ` proxyma ${url}`
  return (
    <StepList intro={t("Google's assistant in the terminal. Sign in with a Google account; a Gemini subscription raises its limits.")}>
      <StepItem text={t('Install Gemini CLI, then run this in a terminal:')}><CodeBlock text={command} /></StepItem>
      <StepItem text={t('Start Gemini CLI and ask it about your documents.')} />
    </StepList>
  )
}

function Copilot({ url, needsToken, token }: ClientProps) {
  const { t } = useTranslation()
  const server: Record<string, unknown> = { type: 'http', url }
  if (needsToken) server.headers = { Authorization: `Bearer ${token}` }
  const config = JSON.stringify({ servers: { proxyma: server } }, null, 2)
  return (
    <StepList intro={t('Copilot Chat in VS Code, in agent mode.')}>
      <StepItem text={t('Add this to .vscode/mcp.json, or run MCP: Open User Configuration from the Command Palette for every workspace:')}><CodeBlock text={config} /></StepItem>
    </StepList>
  )
}

// The kit's code block with its copy button - one line scrolls sideways (a command is copied and
// pasted whole, so it must not wrap), a block of JSON keeps its indentation. The copy falls back to
// a hidden textarea where the clipboard API is refused, as on a Home Server read over plain http.
function CodeBlock({ text }: { text: string }) {
  const { t } = useTranslation()
  return <KitCodeBlock text={text} copyLabel={t('Copy')} copiedLabel={t('Copied')} />
}
