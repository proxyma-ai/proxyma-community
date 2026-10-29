import type { ReactNode } from 'react'
import {
  Bot, Brain, Database, FileSearch, FileText, Folder, Globe, Plus, RefreshCw, Search, Settings, Trash2, Upload, Users, Workflow,
  Bell, Copy, Download, Filter, MoreHorizontal, Pencil, Send, Share2, Star, ThumbsUp, X,
} from 'lucide-react'
import {
  ActivityLine, Badge, BrandMark, Button, Caption, Chip, ConfirmDialog, DialogBody, DialogFooter, DialogHeader, DialogNotice,
  DialogShell, Eyebrow, FormColumns, FormStack, IconButton, IconCountButton, IconToggleButton, InlineCode, InlineNotice, Input,
  Lightbox, ListRowSkeleton, Logo, MENU_PANEL, MenuGroup, MenuIdentity, MenuRow, Modal, POPOVER_PANEL, Paragraph, ProgressBar,
  ProxymaIcon, Readout, SettingsSection, SettingsSections, SettingsWindow, Skeleton, SplashScreen, StateMark, StatusDot, StatusScreen,
  TEXT, AppearanceMenuRow, LanguageMenuRow, buttonClass, cn, menuRow, textLink,
} from '@/components/ui/kit'
import { FACES, FONT_SIZES, RADII, SHADOWS, SPACING, TYPE_ROLES } from '@/dev/kitTokens'
// The app's own 192px icon, one of the raster forms tools/design/rasterize-logo.py writes.
import ICON_192 from '../../../apps/app/frontend/public/proxyma-192.png'

/**
 * THE GUIDELINE'S OWN PICTURES - drawn with the kit, rendered to static HTML by
 * tools/docs/build-ui-guideline.mjs beside the gallery's specimens (apps/app/frontend/src/dev).
 *
 * PICTURES illustrate the foundations (type, space, color roles, the logo). OPEN draws a component
 * in its open state where the kit allows it statically: a dialog renders where it stands (the
 * build makes createPortal return its children), inside a `stage` - a frame with a transform, so
 * the dialog's fixed overlay fills the frame rather than the page. A menu whose open state is the
 * component's own is drawn from the same panel class and rows its shell renders.
 *
 * Not app code: never imported by an app, and not translated (the guideline is English).
 */
export interface Picture {
  title: string
  render: () => ReactNode
  /** Height in px of a frame that holds a fixed overlay (a dialog drawn open). */
  stage?: number
  wide?: boolean
}

const noop = () => undefined
const DISCARD = { title: 'Discard unsaved changes?', body: 'Your changes have not been saved.', keep: 'Keep editing', discard: 'Discard' }
const NAMES = { system: 'System', light: 'Light', dark: 'Dark' } as const

/** Something for a scrim to darken and blur: a page header and a few rows. */
function PageBehind() {
  return (
    <div className="p-5 space-y-4" aria-hidden="true">
      <div className="flex items-center justify-between"><Skeleton className="h-4 w-40" /><Skeleton className="h-8 w-24 rounded-md" /></div>
      <ListRowSkeleton /><ListRowSkeleton /><ListRowSkeleton />
    </div>
  )
}

const SPACE_BAR: Record<string, string> = {
  '1': 'w-1', '2': 'w-2', '3': 'w-3', '4': 'w-4', '6': 'w-6', '8': 'w-8', '12': 'w-12', '16': 'w-16', 'h-8': 'w-8', 'tap-sm': 'w-tap-sm', tap: 'w-tap',
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-1 sm:grid-cols-[9rem_1fr] gap-x-6 gap-y-1 items-baseline py-2 border-b border-line/30 last:border-b-0">
      <span className="text-2xs font-mono text-ink-caption">{label}</span>
      <div className="min-w-0">{children}</div>
    </div>
  )
}

function Lockup({ appearance }: { appearance: 'light' | 'dark' }) {
  // gl-ap-* re-declares the tokens of one appearance on this box (the build writes those classes
  // from proxyma-tokens.css), so the two sit side by side whatever the page shows.
  return (
    <div className={cn(`gl-ap-${appearance}`, 'bg-canvas border border-line/45 rounded-lg p-6 flex flex-col items-start gap-5 min-w-0')}>
      <Eyebrow>{NAMES[appearance]}</Eyebrow>
      <Logo />
      <Logo subtitle="License Manager" />
      <div className="flex items-end gap-3"><Logo markOnly size={16} /><Logo markOnly size={28} /><Logo markOnly size={48} /></div>
      <div className="flex gap-2"><Button size="sm">Primary</Button><Button size="sm" variant="secondary">Secondary</Button></div>
    </div>
  )
}

export const PICTURES: Record<string, Picture> = {
  // -- color -------------------------------------------------------------------------------------
  'brand-roles': {
    title: 'One brand color, four jobs',
    render: () => (
      <div className="grid sm:grid-cols-2 gap-4 w-full">
        <Row label="brand-solid">
          <div className="flex flex-wrap items-center gap-2"><Button size="sm">Save</Button><Badge label="Admin" variant="brand" /><Caption as="span">A fill, with brand-on-solid text.</Caption></div>
        </Row>
        <Row label="brand-legible">
          <Paragraph>Read the <a href="#color" className={textLink('brand', { inline: true })}>manual</a> first. <Caption as="span">Text and links.</Caption></Paragraph>
        </Row>
        <Row label="brand-accent">
          <div className="w-56 space-y-1"><ProgressBar value={62} label="Indexing" /><Caption>Decoration: a meter's fill, a dot. Never behind a label.</Caption></div>
        </Row>
        <Row label="brand-pale">
          <div className="flex flex-wrap items-center gap-2"><Button size="sm" variant="featured">Featured</Button><Chip label="Applies" variant="filter" pressed count={67} onClick={noop} /><Caption as="span">The pale fill, with brand-legible text.</Caption></div>
        </Row>
      </div>
    ),
    wide: true,
  },
  'status-roles': {
    title: 'Status: text by default, a solid fill only on a badge or a destructive button',
    render: () => (
      <div className="space-y-3 w-full">
        <div className="flex flex-wrap gap-2"><Badge label="Active" variant="success" /><Badge label="Expiring" variant="warning" /><Badge label="Revoked" variant="danger" /><Button size="sm" variant="danger">Delete</Button></div>
        <div className="space-y-1"><Caption tone="success">The test reached the server.</Caption><Caption tone="warning">The token expires in 3 days.</Caption><Caption tone="danger">The key was refused.</Caption></div>
        <div className="w-80 space-y-2"><InlineNotice tone="success">Connection works.</InlineNotice><InlineNotice tone="danger">401 - the token was refused.</InlineNotice></div>
      </div>
    ),
    wide: true,
  },

  // -- typography --------------------------------------------------------------------------------
  'type-roles': {
    title: 'The TEXT roles, largest first',
    render: () => (
      <div className="w-full">
        {TYPE_ROLES.map(r => (
          <Row key={r.role} label={`${r.role} - ${r.spec.split(',')[0]}`}>
            <p className={TEXT[r.role as keyof typeof TEXT]}>{r.use}</p>
            <Caption>{r.component}</Caption>
          </Row>
        ))}
      </div>
    ),
    wide: true,
  },
  faces: {
    title: 'Four faces, each for one job',
    render: () => (
      <div className="w-full">
        {FACES.map(f => (
          <Row key={f.name} label={`${f.name} - ${f.token}`}>
            <span className={cn('text-base text-ink-primary', f.className)}>{f.sample}</span>
            <Caption>{f.family}. {f.use}</Caption>
          </Row>
        ))}
      </div>
    ),
    wide: true,
  },
  'font-sizes': {
    title: 'The size steps (display type and anything that is not a role)',
    render: () => (
      <div className="flex flex-wrap items-baseline gap-x-6 gap-y-2">
        {FONT_SIZES.map(s => <span key={s.cls} className={cn('text-ink-primary', s.cls)}>{s.cls.replace('text-', '')} <span className="text-2xs data-readout text-ink-caption">{s.spec}</span></span>)}
      </div>
    ),
    wide: true,
  },
  'readout-vs-code': {
    title: 'Figures are a readout; only code is in the code face',
    render: () => (
      <div className="space-y-2 w-full">
        <Paragraph>Spent <Readout tone="default">$12.40</Readout> on <Readout tone="caption">1,284</Readout> calls, last at <Readout tone="caption">10/5/2026, 7:25 AM</Readout>.</Paragraph>
        <Paragraph>Run <InlineCode>docker compose up -d</InlineCode>, then paste the <InlineCode>pxm_</InlineCode> token.</Paragraph>
      </div>
    ),
    wide: true,
  },

  // -- space and shape ---------------------------------------------------------------------------
  spacing: {
    title: 'The steps the layout uses',
    render: () => (
      <div className="w-full space-y-2">
        {SPACING.map(s => (
          <div key={s.cls} className="flex items-center gap-3">
            <span className="w-14 shrink-0 text-2xs font-mono text-ink-caption">{s.cls}</span>
            <span className={cn('h-3 shrink-0 rounded-sm bg-brand-accent', SPACE_BAR[s.cls])} />
            <Caption as="span">{s.px}px - {s.use}</Caption>
          </div>
        ))}
      </div>
    ),
    wide: true,
  },
  radii: {
    title: 'Radius',
    render: () => (
      <div className="grid grid-cols-2 sm:grid-cols-3 gap-4 w-full">
        {RADII.map(r => <div key={r.cls} className="min-w-0"><div className={cn('h-12 border border-line bg-surface', r.cls)} /><p className="mt-1 text-2xs font-mono text-ink-primary">{r.cls} <span className="text-ink-caption">{r.px}</span></p><p className="text-2xs text-ink-caption leading-snug">{r.use}</p></div>)}
      </div>
    ),
    wide: true,
  },
  shadows: {
    title: 'Elevation: only what floats casts a shadow',
    render: () => (
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 w-full">
        <div className="min-w-0"><div className="h-14 rounded-lg bg-surface/40 border border-line/40" /><p className="mt-2 text-2xs font-mono text-ink-primary">none</p><p className="text-2xs text-ink-caption">A card in the flow: a border.</p></div>
        {SHADOWS.map(s => <div key={s.cls} className="min-w-0"><div className={cn('h-14 rounded-lg surface-float border border-line/45', s.cls)} /><p className="mt-2 text-2xs font-mono text-ink-primary">{s.cls}</p><p className="text-2xs text-ink-caption">{s.use}</p></div>)}
      </div>
    ),
    wide: true,
  },

  // -- motion ------------------------------------------------------------------------------------
  working: {
    title: 'An icon alone blinks; words wave',
    render: () => (
      <div className="space-y-3 w-full">
        <div className="flex items-center gap-4">
          <StateMark icon={FileSearch} state="running" /><StatusDot /><IconCountButton icon={Workflow} label="Agents - 2 sub-agents working" count="2 agents" working onClick={noop} />
          <Caption as="span">Blink: 1Hz, the icon in its own ink.</Caption>
        </div>
        <ActivityLine icon={Brain} state="running" label="Thinking" elapsed="4s" />
        <ActivityLine icon={Search} state="running" label="Searching install base for DX-400" />
        <Caption>Wave: a band of primary ink sweeps the words; their mark holds still.</Caption>
      </div>
    ),
    wide: true,
  },

  // -- icons -------------------------------------------------------------------------------------
  'icon-buttons': {
    title: 'iconButton: three sizes, color-only hover, state as the rest color',
    render: () => (
      <div className="space-y-3">
        <div className="flex items-center gap-2"><IconButton icon={Copy} label="Copy" size="sm" /><IconButton icon={Search} label="Search" /><IconButton icon={Settings} label="Settings" size="lg" /><IconButton icon={Trash2} label="Delete" tone="danger" /><IconButton icon={RefreshCw} label="Sync now" busy /></div>
        <div className="flex items-center gap-2"><IconToggleButton icon={ThumbsUp} label="Good answer" filled pressed onToggle={noop} /><IconToggleButton icon={Star} label="Pin" pressed={false} onToggle={noop} /><Caption as="span">A control's own state is its rest color.</Caption></div>
      </div>
    ),
  },
  lucide: {
    title: 'lucide-react, 16px, in the ink of their context',
    render: () => (
      <div className="flex flex-wrap items-center gap-4 text-ink-caption">
        {[Search, Settings, Plus, Upload, Download, FileText, Folder, Globe, Database, Users, Bot, Bell, Filter, Pencil, Send, Share2, MoreHorizontal, X].map((I, i) => <I key={i} size={16} aria-hidden="true" />)}
      </div>
    ),
  },
  'marks-in-a-row': {
    title: 'Marks beside names: one ink, one size',
    render: () => (
      <div className="space-y-2 text-sm text-ink-secondary">
        {(['jira', 'confluence', 'files', 'web', 'sharepoint'] as const).map(id => (
          <div key={id} className="flex items-center gap-2"><BrandMark id={id} className="text-ink-caption" /><span>{{ jira: 'Jira', confluence: 'Confluence', files: 'Local folder', web: 'Web page', sharepoint: 'SharePoint' }[id]}</span></div>
        ))}
      </div>
    ),
  },

  // -- the logo ----------------------------------------------------------------------------------
  'logo-lockups': {
    title: 'The lockup - the name beside the mark - at its sizes',
    render: () => <div className="flex flex-wrap items-center gap-8"><Logo size={24} /><Logo /><Logo size={40} /></div>,
    wide: true,
  },
  'logo-stacked': {
    title: 'The one stacked lockup: the Community new chat\'s hero (Logo stacked, a 72px mark)',
    render: () => (
      <div className="flex flex-wrap items-center gap-10">
        <Logo size={72} stacked />
        <Caption className="max-w-xs">Only here is the name under the mark. Enterprise Server opens the same screen on "Hi, {'{name}'}" instead, and every bar, footer, card and document keeps the name beside the mark.</Caption>
      </div>
    ),
    wide: true,
  },
  'logo-subtitles': {
    title: 'With a second line: the consoles',
    render: () => <div className="flex flex-wrap items-center gap-8"><Logo subtitle="License Manager" /><Logo subtitle="Telemetry" /></div>,
  },
  'logo-marks': {
    title: 'The mark alone',
    render: () => <div className="flex flex-wrap items-end gap-4"><ProxymaIcon size={16} /><ProxymaIcon size={24} /><ProxymaIcon size={32} /><ProxymaIcon size={48} /><ProxymaIcon size={64} /><ProxymaIcon size={128} /></div>,
  },
  'logo-appearances': {
    title: 'On each appearance (the two side by side, whatever this page shows)',
    render: () => <div className="grid sm:grid-cols-2 gap-4 w-full"><Lockup appearance="light" /><Lockup appearance="dark" /></div>,
    wide: true,
  },
  'logo-clearspace': {
    title: 'Clear space: half the mark, all round',
    render: () => (
      <div className="flex flex-wrap items-center gap-8">
        <div className="relative inline-flex border border-dashed border-brand-accent/70 rounded-sm" style={{ padding: 20 }}>
          <Logo size={40} />
        </div>
        <div className="relative inline-flex border border-dashed border-brand-accent/70 rounded-sm" style={{ padding: 32 }}>
          <Logo markOnly size={64} />
        </div>
        <Caption className="max-w-xs">The dashed line is the nearest anything else may come: half the mark's height (20px round a 40px mark, 32px round a 64px one). The gap inside the lockup is a quarter of the mark.</Caption>
      </div>
    ),
    wide: true,
  },
  'logo-minimum': {
    title: 'Minimum sizes',
    render: () => (
      <div className="flex flex-wrap items-end gap-8">
        <div className="space-y-2"><Logo markOnly size={16} /><Caption>Mark: 16px</Caption></div>
        <div className="space-y-2"><Logo size={24} /><Caption>Lockup: a 24px mark</Caption></div>
      </div>
    ),
  },
  'logo-raster': {
    title: 'A raster form: the app icon (192px PNG), shown at 96, 48, 32 and 16',
    render: () => (
      <div className="flex flex-wrap items-end gap-4">
        {[96, 48, 32, 16].map(s => <img key={s} src={ICON_192} width={s} height={s} alt={s === 96 ? 'The Proxyma app icon' : ''} />)}
      </div>
    ),
  },
  'logo-donts': {
    title: 'Never',
    render: () => (
      <div className="grid grid-cols-1 sm:grid-cols-4 gap-4 w-full">
        <div className="space-y-2 min-w-0"><span className="inline-flex items-center gap-2"><ProxymaIcon size={28} /><span className="font-brand font-bold text-brand-legible text-base tracking-wider">PROXYMA</span></span><Caption tone="danger">Upper case.</Caption></div>
        <div className="space-y-2 min-w-0"><span className="inline-flex items-center gap-2"><ProxymaIcon size={28} /><span className="font-ui font-bold text-brand-legible text-base">Proxyma</span></span><Caption tone="danger">Another face.</Caption></div>
        <div className="space-y-2 min-w-0"><span className="inline-flex items-center gap-2"><span className="inline-flex" style={{ transform: 'scaleX(1.4)' }}><ProxymaIcon size={28} /></span><span className="font-brand font-bold text-success text-base tracking-wider">Proxyma</span></span><Caption tone="danger">Stretched, or recolored.</Caption></div>
        <div className="space-y-2 min-w-0"><span className="inline-flex items-center gap-2 rounded-lg p-1 shadow-float ring-4 ring-brand-accent/40"><ProxymaIcon size={28} /></span><Caption tone="danger">A glow, a tile or a frame.</Caption></div>
      </div>
    ),
    wide: true,
  },
}

/**
 * Components drawn OPEN, keyed by registry name. Appended to the gallery's specimens, whose
 * pictures of these are a closed trigger.
 */
export const OPEN: Record<string, Picture[]> = {
  DialogShell: [{
    title: 'Open: a form dialog over a page',
    stage: 440,
    wide: true,
    render: () => (
      <>
        <PageBehind />
        <DialogShell onClose={noop} size="lg" ariaLabel="Add a web page">
          <DialogHeader icon={<Globe className="text-brand-legible" />} title="Add a web page" subtitle="The page and the pages it links to." />
          <DialogBody>
            <FormStack>
              <Input label="Address" placeholder="https://" />
              <FormColumns><Input label="Depth" defaultValue="2" /><Input label="Pages" defaultValue="80" /></FormColumns>
            </FormStack>
          </DialogBody>
          <DialogFooter start={<InlineNotice tone="success">Reachable.</InlineNotice>}>
            <Button variant="secondary">Cancel</Button><Button>Add</Button>
          </DialogFooter>
        </DialogShell>
      </>
    ),
  }],
  ConfirmDialog: [{
    title: 'Open',
    stage: 400,
    wide: true,
    render: () => (
      <>
        <PageBehind />
        <ConfirmDialog title="Delete this source?" description="Its documents leave the index. The site itself is not changed. Source:" subject="Product wiki"
          consequences={['80 documents are removed from search', 'Conversations keep their citations']}
          confirmLabel="Delete" cancelLabel="Cancel" onConfirm={noop} onCancel={noop} />
      </>
    ),
  }],
  DialogNotice: [{
    title: 'Open, in its dialog',
    stage: 360,
    wide: true,
    render: () => (
      <>
        <PageBehind />
        <DialogShell onClose={noop} size="md" ariaLabel="Not in your plan">
          <DialogBody><DialogNotice title="Microsoft 365 is part of Enterprise" detail="Community connects every other source." action={<Button>See plans</Button>}>Upgrade to connect mail, files and calendars.</DialogNotice></DialogBody>
        </DialogShell>
      </>
    ),
  }],
  Modal: [{
    title: 'Open',
    stage: 440,
    wide: true,
    render: () => (
      <>
        <PageBehind />
        <Modal title="Issue a license" subtitle="Enterprise, one deployment" onClose={noop} cornerBadge={<Badge label="Enterprise" variant="brand" />} onSubmit={noop} discardLabels={DISCARD}
          footer={formId => <><Button variant="secondary">Cancel</Button><Button type="submit" form={formId}>Issue</Button></>}>
          <FormStack><Input label="Customer" placeholder="Northwind Mills" /><Input label="Seats" defaultValue="25" /></FormStack>
        </Modal>
      </>
    ),
  }],
  SettingsWindow: [{
    title: 'Open',
    stage: 620,
    wide: true,
    render: () => (
      <SettingsWindow onClose={noop} ariaLabel="Settings" discardLabels={DISCARD}
        groups={[{ label: 'Console', items: [{ key: 'retention', label: 'Data retention', icon: Database }, { key: 'appearance', label: 'Appearance', icon: Settings }] }]}
        active="retention" onChange={noop} title="Data retention"
        notice={<InlineNotice tone="info">Only a Super admin changes these.</InlineNotice>}>
        <SettingsSections intro="How long the console keeps what it is sent.">
          <SettingsSection title="Devices"><Input label="Release idle device bindings after (days)" defaultValue="0" /></SettingsSection>
        </SettingsSections>
      </SettingsWindow>
    ),
  }],
  StatusScreen: [{
    title: 'Open',
    stage: 320,
    wide: true,
    render: () => <><PageBehind /><StatusScreen busy title="Restarting">Proxyma is starting again with the new network settings.</StatusScreen></>,
  }],
  Lightbox: [{
    title: 'Open',
    stage: 360,
    wide: true,
    render: () => <><PageBehind /><Lightbox src={ICON_192} alt="The Proxyma app icon" onClose={noop} /></>,
  }],
  SplashScreen: [{
    title: 'Starting, as the desktop shows it',
    stage: 420,
    wide: true,
    render: () => (
      <div className="absolute inset-0 flex items-center justify-center p-4 bg-black/75">
        <SplashScreen turning status="Starting" detail="This is taking a little longer than usual. Please wait." />
      </div>
    ),
  }],
  MenuSurface: [{
    title: 'Open (drawn with MENU_PANEL and the rows its shell holds)',
    stage: 400,
    render: () => (
      <div className="p-4 flex justify-end">
        <div className="relative">
          <button type="button" className={buttonClass('secondary')} aria-expanded="true" aria-haspopup="menu">Account</button>
          <div role="menu" className={MENU_PANEL}>
            <MenuIdentity>anna.weber@northwind.example</MenuIdentity>
            <MenuGroup>
              <button type="button" className={menuRow()}><Settings size={15} aria-hidden="true" />Settings</button>
              <button type="button" className={menuRow('active')}><Users size={15} aria-hidden="true" />Administration</button>
            </MenuGroup>
            <MenuGroup>
              <AppearanceMenuRow title="Appearance" names={NAMES} current="system" onChoose={noop} />
              <LanguageMenuRow title="Language" locales={['en', 'vi'] as const} current="en" nameOf={l => (l === 'en' ? 'English' : 'Ti\u1EBFng Vi\u1EC7t')} onPick={noop} />
            </MenuGroup>
            <MenuGroup><button type="button" className={menuRow('danger')}>Sign out</button></MenuGroup>
          </div>
        </div>
      </div>
    ),
  }],
  Popover: [{
    title: 'Open (drawn with POPOVER_PANEL and its rows)',
    stage: 320,
    render: () => (
      <div className="p-4 space-y-2">
        <button type="button" className={buttonClass('secondary')} aria-expanded="true" aria-haspopup="menu">Popover menu</button>
        <div role="menu" className={cn(POPOVER_PANEL, 'w-64')}>
          <MenuGroup label="Sources">
            <MenuRow icon={<Folder />} title="Local folder" description="Index a folder on this machine" onSelect={noop} />
            <MenuRow icon={<Globe />} title="Web page" onSelect={noop} />
            <MenuRow icon={<Users />} title="Microsoft 365" trailing="lock" tone="locked" onSelect={noop} />
          </MenuGroup>
          <MenuGroup><MenuRow icon={<Plus />} title="Create new skill" tone="brand" onSelect={noop} /></MenuGroup>
        </div>
      </div>
    ),
  }],
}

/** One brand mark, for the table of marks and their terms. */
export function markPicture(id: Parameters<typeof BrandMark>[0]['id']): ReactNode {
  return <BrandMark id={id} className="text-ink-primary" />
}
