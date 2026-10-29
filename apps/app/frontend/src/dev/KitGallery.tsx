import { useEffect, useMemo, useState } from 'react'
import { PanelRightClose } from 'lucide-react'
import { ChatPanel } from '@/features/chat/components/ChatPanel'
import {
  Badge, Caption, Card, Eyebrow, IconButton, InlineCode, KeyValue, KeyValueList, Logo, MetaList, Paragraph, Readout, SearchInput,
  SectionHeading, SegmentedControl, TABLE_CLASS, TD_COMPACT, TEXT, THEAD_TR, TR, TableCard, Th, Title, applyAppearanceSmoothly, cn,
  resolveAppearance, textLink, SYSTEM_DARK_QUERY, type AppearancePreference,
} from '@/components/ui/kit'
import tokensCss from '@/styles/proxyma/proxyma-tokens.css?raw'
import { KIT_GROUPS, KIT_REGISTRY, type KitEntry } from './kitRegistry'
import { SPECIMENS } from './kitSpecimens'
import {
  ANIMATIONS, APPEARANCES, BREAKPOINTS, FACES, FONT_SIZES, LAYERS, LAYOUT, MOTION, RADII, RAMPS, SEMANTIC, SHADOWS, SPACING, TYPE_ROLES,
  type Appearance,
} from './kitTokens'

/**
 * THE KIT GALLERY - every component of the kit, from the registry (kitRegistry.ts: what it is, when
 * to use it and when not, its props) beside its specimens (kitSpecimens.tsx), plus the tokens and
 * the logo, in whichever appearance is chosen at the top - System included. DEVELOPMENT ONLY: the
 * route exists only under `import.meta.env.DEV`, which Vite replaces with `false` in every build.
 * Not translated, for the same reason. kitRegistry.test.ts fails when a kit export is missing here.
 *
 * It is the place to check a kit change against everything else before it reaches a page, and the
 * page the design guideline's screenshots are taken from (`/__kit#Button`).
 */

// -- the token file, per appearance ----------------------------------------------------------

/** The token file's two blocks - Light the bare :root, Dark :root[data-mode="dark"] - each variable's value in each appearance. */
function parseTokens(css: string): Record<Appearance, Record<string, string>> {
  const block = (selector: RegExp) => {
    const m = selector.exec(css)
    if (!m) return {}
    const start = m.index + m[0].length
    const end = css.indexOf('\n}', start)
    const out: Record<string, string> = {}
    for (const v of css.slice(start, end).matchAll(/--([\w-]+):\s*([^;]+);/g)) out[v[1]] = v[2].trim()
    return out
  }
  const light = block(/^:root \{/m)
  return {
    light,
    dark: { ...light, ...block(/^:root\[data-mode="dark"\] \{/m) },
  }
}

/** "22 22 23" as #161617; anything else as written. */
function show(value: string | undefined): string {
  if (!value) return '-'
  const m = /^(\d+)\s+(\d+)\s+(\d+)$/.exec(value)
  if (!m) return value
  return '#' + [m[1], m[2], m[3]].map(n => Number(n).toString(16).padStart(2, '0')).join('')
}

const TOKENS = parseTokens(tokensCss)
const COLUMNS: { id: Appearance; name: string }[] = [{ id: 'light', name: 'Light' }, { id: 'dark', name: 'Dark' }]

// Spelled out whole so Tailwind's scanner sees every class.
const SPACE_BAR: Record<string, string> = {
  '1': 'w-1', '2': 'w-2', '3': 'w-3', '4': 'w-4', '6': 'w-6', '8': 'w-8', '12': 'w-12', '16': 'w-16', 'h-8': 'w-8', 'tap-sm': 'w-tap-sm', tap: 'w-tap',
}

// -- layout pieces ----------------------------------------------------------------------------

function Section({ id, title, kicker, summary, children }: { id: string; title: string; kicker: string; summary?: string; children: React.ReactNode }) {
  return (
    <section id={id} className="space-y-6 scroll-mt-20">
      <div className="border-b border-line/40 pb-3">
        <div className="flex items-baseline gap-3"><Title as="h2">{title}</Title><Caption as="span">{kicker}</Caption></div>
        {summary && <Caption className="mt-1">{summary}</Caption>}
      </div>
      {children}
    </section>
  )
}

function Entry({ entry }: { entry: KitEntry }) {
  const specimens = SPECIMENS[entry.name] ?? []
  return (
    <article id={entry.name} className="scroll-mt-20 space-y-4 border-b border-line/30 pb-10 last:border-b-0">
      <header className="space-y-2">
        <div className="flex flex-wrap items-center gap-2">
          <h3 className={TEXT.section}>{entry.name}</h3>
          <Badge size="sm" variant="outline" label={entry.level} />
          <Badge size="sm" variant="outline" label={entry.atomic} />
          <Caption as="span"><InlineCode>{entry.file}</InlineCode></Caption>
        </div>
        <Paragraph tone="primary">{entry.purpose}</Paragraph>
        <div className="grid md:grid-cols-2 gap-4">
          <div><Eyebrow>Use it for</Eyebrow><ul className="mt-1 space-y-1">{entry.use.map(u => <li key={u} className={TEXT.caption}>{u}</li>)}</ul></div>
          <div><Eyebrow>Not for</Eyebrow><ul className="mt-1 space-y-1">{entry.avoid.map(u => <li key={u} className={TEXT.caption}>{u}</li>)}</ul></div>
        </div>
        {entry.props.length > 0 && (
          <TableCard ariaLabel={`${entry.name} props`}>
            <table className={TABLE_CLASS}>
              <thead><tr className={THEAD_TR}><Th compact>Prop</Th><Th compact>Type</Th><Th compact>Default</Th><Th compact>What it does</Th></tr></thead>
              <tbody>
                {entry.props.map(p => (
                  <tr key={p.name} className={TR}>
                    <td className={cn(TD_COMPACT, 'font-mono text-ink-primary whitespace-nowrap')}>{p.name}{p.required && <span className="text-danger" title="Required"> *</span>}</td>
                    <td className={cn(TD_COMPACT, 'font-mono text-ink-caption break-words max-w-[16rem]')} data-label="Type">{p.type}</td>
                    <td className={cn(TD_COMPACT, 'font-mono text-ink-caption')} data-label="Default">{p.default ?? ''}</td>
                    <td className={TD_COMPACT} data-label="What it does">{p.note}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </TableCard>
        )}
        {entry.related && entry.related.length > 0 && (
          <Caption as="div">See also: <MetaList items={entry.related.map(r => <a key={r} href={`#${r}`} className={textLink('brand', { inline: true })}>{r}</a>)} /></Caption>
        )}
      </header>
      <div className="grid grid-cols-1 md:grid-cols-2 gap-x-10 gap-y-8">
        {specimens.map((s, i) => (
          <div key={s.title} id={`${entry.name}-${i + 1}`} className={cn('min-w-0 scroll-mt-20', s.wide && 'md:col-span-2')}>
            <p className="text-2xs font-mono text-ink-disabled mb-2">{s.title}</p>
            <div className="flex flex-wrap items-start gap-3 min-w-0">{s.render()}</div>
          </div>
        ))}
      </div>
    </article>
  )
}

// -- the token tables --------------------------------------------------------------------------

function Tokens() {
  return (
    <div className="space-y-10">
      <div className="space-y-3 min-w-0">
        <SectionHeading caption="What a person picks in Settings and the menus. The switch at the top applies each.">Appearances</SectionHeading>
        <KeyValueList layout="rows">{APPEARANCES.map(a => <KeyValue key={a.id} label={a.name}>{a.note}</KeyValue>)}</KeyValueList>
      </div>

      {SEMANTIC.map(group => (
        <div key={group.title} className="space-y-3">
          <SectionHeading caption="Semantic: they re-map per appearance. The swatch is the appearance shown now; the columns are each one's value.">{group.title}</SectionHeading>
          <TableCard ariaLabel={group.title}>
            <table className={TABLE_CLASS}>
              <thead><tr className={THEAD_TR}><Th compact>Token</Th><Th compact>Now</Th>{COLUMNS.map(c => <Th compact key={c.id}>{c.name}</Th>)}<Th compact>Class</Th><Th compact>Role</Th></tr></thead>
              <tbody>
                {group.tokens.map(t => (
                  <tr key={t.name} className={TR}>
                    <td className={cn(TD_COMPACT, 'font-mono text-ink-primary')}>--{t.name}</td>
                    <td className={TD_COMPACT} data-label="Now"><span className={cn('inline-block w-8 h-5 rounded-md border border-line', t.swatch)} /></td>
                    {COLUMNS.map(c => <td key={c.id} className={cn(TD_COMPACT, 'data-readout text-ink-caption')} data-label={c.name}>{show(TOKENS[c.id][t.name])}</td>)}
                    <td className={cn(TD_COMPACT, 'font-mono text-ink-caption')} data-label="Class">{t.classes}</td>
                    <td className={TD_COMPACT} data-label="Role">{t.role}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </TableCard>
        </div>
      ))}

      {RAMPS.map(ramp => (
        <div key={ramp.title} className="space-y-3">
          <SectionHeading caption={ramp.note}>{ramp.title}</SectionHeading>
          <div className="grid grid-cols-4 sm:grid-cols-6 lg:grid-cols-11 gap-2">
            {ramp.steps.map(s => (
              <div key={s.name} className="min-w-0">
                <div className={cn('h-12 rounded-md border border-line/40', s.swatch)} />
                <p className="mt-1 text-2xs font-mono text-ink-primary">{s.name}</p>
                <p className="text-2xs data-readout text-ink-caption">{show(TOKENS.light[s.name])}</p>
                {s.role && <p className="text-2xs text-ink-disabled leading-snug">{s.role}</p>}
              </div>
            ))}
          </div>
        </div>
      ))}

      <div className="space-y-3 min-w-0">
        <SectionHeading caption="One scale for every surface - the kit's TEXT roles. Nothing sets a size by hand.">Type roles</SectionHeading>
        <div className="space-y-3 min-w-0">
          {TYPE_ROLES.map(r => (
            <div key={r.role} className="grid sm:grid-cols-[8rem_1fr] gap-x-6 gap-y-1 items-baseline">
              <Caption as="span"><InlineCode>{r.role}</InlineCode> {r.spec}</Caption>
              <div>
                <p className={TEXT[r.role as keyof typeof TEXT]}>{r.use}</p>
                <Caption>{r.component}</Caption>
              </div>
            </div>
          ))}
        </div>
      </div>

      <div className="space-y-3 min-w-0">
        <SectionHeading caption="Four faces, each for one job.">Faces</SectionHeading>
        <KeyValueList layout="rows">
          {FACES.map(f => <KeyValue key={f.name} label={`${f.name} - ${f.token}`}><span className={cn('text-base text-ink-primary', f.className)}>{f.sample}</span><Caption>{f.family}. {f.use}</Caption></KeyValue>)}
        </KeyValueList>
        <div className="flex flex-wrap items-baseline gap-x-6 gap-y-2">
          {FONT_SIZES.map(s => <span key={s.cls} className={cn('text-ink-primary', s.cls)}>{s.cls.replace('text-', '')} <span className="text-2xs data-readout text-ink-caption">{s.spec}</span></span>)}
        </div>
      </div>

      <div className="grid lg:grid-cols-2 gap-10">
        <div className="space-y-3 min-w-0">
          <SectionHeading caption="The 4-point rhythm: 4, 8, 12, 16, 24, 32, 48, 64 - and the control heights.">Spacing</SectionHeading>
          {SPACING.map(s => (
            <div key={s.cls} className="flex items-center gap-3">
              <span className="w-14 text-2xs font-mono text-ink-caption">{s.cls}</span>
              <span className={cn('h-3 rounded-sm bg-brand-accent', SPACE_BAR[s.cls])} />
              <Caption as="span">{s.px}px - {s.use}</Caption>
            </div>
          ))}
        </div>
        <div className="space-y-3 min-w-0">
          <SectionHeading caption="By role: the control's 6px and the surface's 10px do almost everything.">Radius</SectionHeading>
          <div className="grid grid-cols-3 gap-3">
            {RADII.map(r => (
              <div key={r.cls}><div className={cn('h-12 border border-line bg-surface', r.cls)} /><p className="mt-1 text-2xs font-mono text-ink-primary break-all">{r.cls} <span className="text-ink-caption">{r.px}</span></p><p className="text-2xs text-ink-disabled leading-snug">{r.use}</p></div>
            ))}
          </div>
          <SectionHeading caption="One shadow, for what floats. A card in the page has a border and none.">Shadows</SectionHeading>
          <div className="grid grid-cols-3 gap-4">
            {SHADOWS.map(s => <div key={s.cls}><div className={cn('h-14 rounded-lg surface-float border', s.cls)} /><p className="mt-2 text-2xs font-mono text-ink-primary break-all">{s.cls}</p><p className="text-2xs text-ink-disabled">{s.use}</p></div>)}
          </div>
        </div>
      </div>

      <div className="grid lg:grid-cols-2 gap-10">
        <div className="space-y-3 min-w-0">
          <SectionHeading caption="Three durations and three curves; nothing bounces. Point at a row to see it ease.">Motion</SectionHeading>
          {MOTION.map(m => (
            <div key={m.token} className="flex items-center gap-3 rounded-md px-2 py-2 mouse:hover:bg-surface-alt transition-colors" >
              <span className="w-28 text-2xs font-mono text-ink-primary">{m.token}</span>
              <Readout tone="caption" className="text-2xs w-40">{m.value}</Readout>
              <Caption as="span">{m.use}</Caption>
            </div>
          ))}
          <KeyValueList layout="rows">{ANIMATIONS.map(a => <KeyValue key={a.cls} label={a.cls}>{a.use}</KeyValue>)}</KeyValueList>
        </div>
        <div className="space-y-3 min-w-0">
          <SectionHeading caption="Named layers, bottom to top.">Layers (z-index)</SectionHeading>
          <KeyValueList layout="rows">{LAYERS.map(l => <KeyValue key={l.cls} label={l.cls} figures>{l.value} - {l.use}</KeyValue>)}</KeyValueList>
          <SectionHeading caption="Tailwind's width ladder, then five device classes declared after it so they win.">Breakpoints</SectionHeading>
          <KeyValueList layout="rows">{BREAKPOINTS.map(b => <KeyValue key={b.cls} label={b.cls}>{b.query}{b.use && ` - ${b.use}`}</KeyValue>)}</KeyValueList>
          <SectionHeading caption="The page's columns.">Layout</SectionHeading>
          <KeyValueList layout="rows">{LAYOUT.map(l => <KeyValue key={l.name} label={l.name}><InlineCode>{l.value}</InlineCode> {l.use}</KeyValue>)}</KeyValueList>
        </div>
      </div>
    </div>
  )
}

/** The lockup on each appearance's canvas is the switch at the top; this is every form and size. */
function LogoSheet() {
  return (
    <div className="space-y-6">
      <Paragraph>The mark and the Orbitron wordmark &quot;Proxyma&quot; are one lockup: the gap a quarter of the mark, clear space round it of at least half the mark&apos;s height. The mark is never under 16px and the lockup never under a 24px mark. The wordmark is always &quot;Proxyma&quot; in Orbitron 700, never upper case.</Paragraph>
      <div className="grid sm:grid-cols-3 gap-4">
        {(['Lockup', 'Console lockup', 'Mark alone'] as const).map(kind => (
          <Card key={kind} className="flex flex-col items-start gap-4">
            <Eyebrow>{kind}</Eyebrow>
            {kind === 'Lockup' && <><Logo size={16} /><Logo size={24} /><Logo /><Logo size={40} /></>}
            {kind === 'Console lockup' && <><Logo subtitle="License Manager" /><Logo subtitle="Telemetry" /></>}
            {kind === 'Mark alone' && <div className="flex items-end gap-3"><Logo markOnly size={16} /><Logo markOnly size={28} /><Logo markOnly size={48} /><Logo markOnly size={80} /></div>}
          </Card>
        ))}
      </div>
      <Caption>Raster forms are generated (tools/design/rasterize-logo.py): the 512px PNG, a 256px and a 192px PNG, and nine-frame ICOs from 16 to 256px.</Caption>
    </div>
  )
}

// -- the page ---------------------------------------------------------------------------------

export function KitGallery() {
  const [appearance, setAppearance] = useState<AppearancePreference>('system')
  const [query, setQuery] = useState('')
  const [panelConversation, setPanelConversation] = useState<string | null>(null)

  // The chosen appearance, System following the OS live while it is chosen.
  useEffect(() => {
    const media = window.matchMedia(SYSTEM_DARK_QUERY)
    const apply = () => applyAppearanceSmoothly(resolveAppearance(appearance, media.matches))
    apply()
    if (appearance !== 'system') return
    media.addEventListener('change', apply)
    return () => media.removeEventListener('change', apply)
  }, [appearance])

  const q = query.trim().toLowerCase()
  const visible = useMemo(() => KIT_REGISTRY.filter(e => !q || e.name.toLowerCase().includes(q) || e.purpose.toLowerCase().includes(q)), [q])

  return (
    <div className="h-full overflow-y-auto app-sheet">
      <header className="sticky top-0 z-header header-bar border-b border-line/40">
        <div className="max-w-6xl mx-auto px-3 sm:px-6 py-2 flex flex-wrap items-center gap-x-6 gap-y-2">
          <Logo size={24} />
          <span className={TEXT.label}>Kit gallery</span>
          <span className="text-xs text-ink-caption data-readout">{KIT_REGISTRY.length} components</span>
          <span className="flex-1" />
          <div className="w-full sm:w-56"><SearchInput value={query} onChange={setQuery} placeholder="Find a component" clearLabel="Clear" /></div>
          <SegmentedControl
            ariaLabel="Appearance"
            value={appearance}
            onChange={setAppearance}
            options={[{ value: 'system', label: 'System' }, { value: 'light', label: 'Light' }, { value: 'dark', label: 'Dark' }]}
          />
        </div>
        <nav className="max-w-6xl mx-auto px-3 sm:px-6 pb-2 flex gap-x-4 gap-y-1 flex-wrap text-xs" aria-label="Groups">
          <a href="#tokens" className={cn(textLink('quiet'), 'text-xs')}>Tokens</a>
          <a href="#logo" className={cn(textLink('quiet'), 'text-xs')}>Logo</a>
          {KIT_GROUPS.map(g => <a key={g.id} href={`#group-${g.id}`} className={cn(textLink('quiet'), 'text-xs')}>{g.title}</a>)}
        </nav>
      </header>

      <main className="max-w-6xl mx-auto px-3 sm:px-6 py-10 space-y-16">
        {!q && (
          <>
            <Section id="tokens" title="Tokens" kicker="foundation" summary="Read from libs/ui-kit/tokens - the values below are the token file's own.">
              <Tokens />
            </Section>
            <Section id="logo" title="Logo" kicker="brand" summary="Every form of the mark, in the appearance chosen above.">
              <LogoSheet />
            </Section>
          </>
        )}

        {KIT_GROUPS.map(g => {
          const entries = visible.filter(e => e.group === g.id)
          if (entries.length === 0) return null
          return (
            <Section key={g.id} id={`group-${g.id}`} title={g.title} kicker={g.level} summary={g.summary}>
              <div className="space-y-10">{entries.map(e => <Entry key={e.name} entry={e} />)}</div>
            </Section>
          )
        })}

        {!q && (
          // NOT a kit part: the app's own ChatPanel, LIVE - a chat instance of its own with the
          // `kit-gallery` surface, talking to whatever backend the dev server proxies to. It is here to
          // check a panel at its real width beside nothing else; its conversations stay out of the
          // chat page's history.
          <Section id="app-parts" title="App parts" kicker="not the kit" summary="The app's live ChatPanel, at its real width.">
            <div className="w-full max-w-[420px] h-[640px] border border-line/45 rounded-lg overflow-hidden">
              <ChatPanel
                instanceKey="kit-gallery"
                surface="kit-gallery"
                skillMentions={[]}
                onConversationCreated={id => { setPanelConversation(id) }}
                header={(
                  <div className="flex items-center justify-between gap-2">
                    <span className={cn(TEXT.label, 'truncate')}>Northwind Mills</span>
                    <IconButton icon={PanelRightClose} label="Collapse" size="sm" />
                  </div>
                )}
                emptyTitle="Ask about this account"
                emptyDescription="The agent reads the account's sources and proposes; you decide."
                suggestions={['What is still missing?', 'Run the health review', 'Draft next moves']}
              />
            </div>
            <p className="text-2xs font-mono text-ink-disabled">bound: {panelConversation ?? '-'}</p>
          </Section>
        )}
        {visible.length === 0 && <Caption>No component matches.</Caption>}
      </main>
    </div>
  )
}

