import { useState, type ReactNode } from 'react'
import {
  Bot, Brain, Database, FileSearch, FileText, Folder, Globe, KeyRound, MessageCircleQuestion, PenLine, Plus, RefreshCw, Search, Settings,
  ShieldAlert, ShieldCheck, Tags, Trash2, Upload, Users, Workflow, ThumbsUp, Pin,
} from 'lucide-react'
import {
  ActionMenu, ActivityFeed, ActivityLine, AppHeader, AppearanceIcon, AppearanceMenu, AppearanceMenuRow, AudioPlayer, AuthLayout, AutoHideHeader,
  Avatar, AvatarStack, BackLink, Badge, Banner, BarList, BootScreen, BrandMark, BulkActionBar, Button, ButtonLink, Callout, Caption, Card,
  CardBody, CardHeader, ChartCard, ChatMessage, ChatMessageSkeleton, ChatStartHero, ChatStartLinks, Checkbox, Chip, ChoiceMark, ChoiceRow, CitationList,
  CitationRow, CitationsFold, CodeBlock, Collapse, ColumnChart, Combobox, CompareTable, ComposerShell, ConfirmDialog, CopyButton, CopyField,
  DateTimePicker, DialogBody, DialogDescription, DialogFooter, DialogHeader, DialogNotice, DialogShell, DialogTitle, Disclosure,
  DisclosureButton, DisclosureChevron, DisplayHeading, Divider, DownloadRow, DropArea, DropOverlay, Elapsed, EmptyState, EmptyValue,
  ErrorBoundary, ErrorState, ExternalLink, ExternalMark, Eyebrow, FadeSwap, FaqItem, FeatureRow, Field, FieldLabel, FieldNote, FieldSkeleton,
  FilePickerButton, FilterBar, FilterChips, FilterTiles, FormAlert, FormColumns, FormStack, Frame, HScroll, HoverActions, IconButton, IconCountButton,
  IconMenu, IconToggleButton, IdentityCell, IncludedList, InlineCode, InlineConfirm, InlineNotice, Input, ItemRow, ItemRowSkeleton,
  KeyValue, KeyValueGroup, KeyValueList, LanguageMenu, LanguageMenuRow, LegendSwatch, Lightbox, LinkCard, LinkColumn, LinkRow, List,
  ListEditor, ListGroupHeading, ListPager, ListRow, ListRowSkeleton, LoadMoreRow, LoadingState, Logo, MenuEmpty, MenuGroup, MenuIdentity,
  MenuRow, MenuSurface, MenuTrigger, MetaList, Meter, MobileMenu, Modal, NoticeCard, OneTimeSecret, OrDivider, Page, PageHeader, PageHero,
  PageSection, PageTitle, Pager, Paragraph, PasswordInput, PickBox, PlanBadge, PlanBand, PlanCard, Popover, Presence, ProgressBar,
  ProgressRow, PromptCard, PromptSection, ProseTable, ProxymaBackground, ProxymaIcon, RadioGroup, RadioGroupSkeleton, Readout,
  RefreshIcon, RowActions, RuleChecklist, RuledColumns, SaveBar, SceneControl, SceneFrame, SearchInput, SecretInput, SectionHeading,
  SegmentedControl, Select, SelectBox, SelectableListHeader, SettingsLayout, SettingsRail, SettingsSection, SettingsSections, SettingsWindow,
  SheetTable, Skeleton, Slider, SortTh, SourceListHeader, SourceRow, SourceRowSkeleton, Spinner, SplashScreen, SplitSection, StackedBar,
  StateMark, StatGrid, StatTile, StaticValue, StatusDot, StatusMark, StatusScreen, StepCard, StepItem, StepList, Stepper, SubAgentPanel,
  SubNavTabs, SubmenuRow, SwitchRow, SwitchRowSkeleton, TABLE_CLASS, TD, TD_COMPACT, THEAD_TR, TR, TR_INTERACTIVE, TableCard,
  TableEmptyRow, TableFooter, TableSkeletonRows, Textarea, Th, TierBadge, Timeline, Title, Toast, ToggleSwitch, ToolCallGroup, ToolCallRow,
  Toolbar, Tooltip, UsageRing, UserMenu, WorkingBadge, buttonClass, iconButton, menuRow, textLink, useFileDrop, MARK_PATHS, isMarkId,
  type AppearancePreference, type BarListRow, type SortState,
} from '@/components/ui/kit'

/**
 * THE SPECIMENS OF THE KIT GALLERY - for every component in kitRegistry.ts, one or more pictures of
 * it in use, each a function so a specimen with state is a small component of its own. Keyed by the
 * export's name, one key per line at two spaces (`  Name: [`), which is how kitRegistry.test.ts
 * finds them. Not translated: the gallery is development-only.
 */
export interface KitSpecimen {
  title: string
  /** Takes the whole row rather than one column. */
  wide?: boolean
  render: () => ReactNode
}

const noop = () => undefined
const DISCARD = { title: 'Discard unsaved changes?', body: 'Your changes have not been saved.', keep: 'Keep editing', discard: 'Discard' }
const NAMES: Record<AppearancePreference, string> = { system: 'System', light: 'Light', gray: 'Proxyma', black: 'Dark' }

// -- small stateful demos --------------------------------------------------------------------

function FadeSwapDemo() {
  const [n, setN] = useState(0)
  return (
    <div className="space-y-2">
      <Button variant="secondary" onClick={() => setN(v => v + 1)}>Swap</Button>
      <FadeSwap swapKey={n}><Caption>Content number {n + 1} faded in, nothing remounted.</Caption></FadeSwap>
    </div>
  )
}

function PresenceDemo() {
  const [show, setShow] = useState(true)
  return (
    <div className="w-72 space-y-2">
      <Button variant="secondary" onClick={() => setShow(s => !s)}>{show ? 'Hide' : 'Show'} the notice</Button>
      <Presence show={show}><InlineNotice tone="warning">A status note that comes and goes; what is below moves.</InlineNotice></Presence>
      <Caption>The line under it.</Caption>
    </div>
  )
}

function InputDemo() {
  const [text, setText] = useState('Quarterly report')
  return (
    <div className="w-72 space-y-4">
      <Input label="Name" value={text} onChange={e => setText(e.target.value)} hint="Shown in the source list." />
      <Input label="URL" defaultValue="htp:/" error="Enter a full address, starting with https://" />
      <Input label="Disabled" defaultValue="Read only here" disabled />
    </div>
  )
}

function SearchDemo() {
  const [v, setV] = useState('')
  return <div className="w-72"><SearchInput value={v} onChange={setV} placeholder="Search sources" clearLabel="Clear search" /></div>
}

function SecretDemo() {
  const [v, setV] = useState('')
  return (
    <div className="w-72 space-y-4">
      <Field label="API key (stored)" hint="Type or paste to replace it.">{(id, aria) => <SecretInput id={id} {...aria} value={v} serverValue="sk-proj-abcdef1234567890wxyz" onChange={setV} labels={{ reveal: 'Reveal', hide: 'Hide', show: 'Show' }} />}</Field>
      <Field label="API key (none stored)">{id => <SecretInput id={id} value="" onChange={noop} placeholder="sk-..." labels={{ reveal: 'Reveal', hide: 'Hide', show: 'Show' }} />}</Field>
    </div>
  )
}

function SelectDemo() {
  const [v, setV] = useState('gpt')
  const [tz, setTz] = useState('Asia/Ho_Chi_Minh')
  const zones = ['Africa/Cairo', 'America/New_York', 'Asia/Ho_Chi_Minh', 'Asia/Tokyo', 'Australia/Sydney', 'Europe/Berlin', 'Europe/London', 'UTC']
  return (
    <div className="w-72 space-y-4">
      <Field label="Model provider">{id => <Select id={id} value={v} onChange={setV} options={[{ value: 'gpt', label: 'OpenAI' }, { value: 'claude', label: 'Anthropic' }, { value: 'gemini', label: 'Gemini' }, { value: 'ollama', label: 'Ollama' }]} />}</Field>
      <Field label="Time zone (searchable)">{id => <Select id={id} searchable value={tz} onChange={setTz} options={zones.map(z => ({ value: z, label: z }))} searchPlaceholder="Search..." emptyLabel="No matches" />}</Field>
    </div>
  )
}

function ComboboxDemo() {
  const [v, setV] = useState('qwen3:latest')
  return (
    <div className="w-72">
      <Field label="Model">{(id, aria) => (
        <Combobox id={id} {...aria} value={v} onChange={setV} options={['qwen3:latest', 'llama3.3:70b', 'gpt-oss:20b', 'mistral-small']} status="success" hint="Typed, or picked from what the provider lists."
          onCreate={noop} labels={{ loading: 'Loading models...', failed: 'Failed to load models', empty: 'No models found', create: q => `Use "${q}"` }} />
      )}</Field>
    </div>
  )
}

const DATE_LABELS = {
  placeholder: 'Select date & time', clear: 'Clear', now: 'Now', today: 'Today', done: 'Done',
  months: ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'],
  weekdays: ['Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa', 'Su'],
  previousMonth: 'Previous month', nextMonth: 'Next month', hour: 'Hour', minute: 'Minute', period: 'AM or PM',
}

function DateDemo() {
  const [v, setV] = useState('2026-10-14T09:30')
  const [d, setD] = useState('')
  return (
    <div className="w-72 space-y-4">
      <Field label="Maintenance starts">{id => <DateTimePicker id={id} value={v} onChange={setV} labels={DATE_LABELS} locale="en-US" />}</Field>
      <Field label="Renewal date">{id => <DateTimePicker id={id} dateOnly value={d} onChange={setD} labels={{ ...DATE_LABELS, placeholder: 'Select a date' }} locale="en-US" />}</Field>
    </div>
  )
}

function CheckDemo() {
  const [c, setC] = useState(true)
  const [r, setR] = useState('ask')
  const [shift, setShift] = useState(false)
  return (
    <div className="space-y-4">
      <Checkbox checked={c} onChange={setC} hint="A caption under the choice.">Include archived pages</Checkbox>
      <RadioGroup ariaLabel="Permission" value={r} onChange={setR} options={[{ value: 'ask', label: 'Ask before each change' }, { value: 'auto', label: 'Allow changes', hint: 'Writes still show in the thread.' }, { value: 'off', label: 'Read only', disabled: true }]} />
      <div className="flex items-center gap-3"><SelectBox checked={shift} label="Select row" onToggle={v => setShift(v)} /><SelectBox checked={false} indeterminate label="Select all" onToggle={noop} /><Caption>SelectBox: a row and a header</Caption></div>
    </div>
  )
}

function SegDemo() {
  const [seg, setSeg] = useState<'day' | 'week' | 'month'>('week')
  return (
    <div className="space-y-3">
      <SegmentedControl ariaLabel="Period" value={seg} onChange={setSeg} options={[{ value: 'day', label: 'Day' }, { value: 'week', label: 'Week' }, { value: 'month', label: 'Month' }]} />
      <SegmentedControl ariaLabel="Period" variant="text" value={seg} onChange={setSeg} options={[{ value: 'day', label: '24h' }, { value: 'week', label: '7d' }, { value: 'month', label: '30d' }]} />
      <SegmentedControl ariaLabel="On conflict" value="month" onChange={noop} options={[{ value: 'day', label: 'Skip' }, { value: 'month', label: 'Overwrite', tone: 'warning' }]} />
    </div>
  )
}

function SwitchDemo() {
  const [on, setOn] = useState(true)
  return <div className="flex items-center gap-4"><ToggleSwitch checked={on} onChange={setOn} ariaLabel="Search the web" /><ToggleSwitch size="sm" checked={!on} onChange={v => setOn(!v)} ariaLabel="Small" /><ToggleSwitch checked disabled ariaLabel="Disabled" /><ToggleSwitch checked={on} ariaLabel="A picture of a switch" /></div>
}

function SliderDemo() {
  const [v, setV] = useState(0.7)
  return <div className="w-72"><Slider id="temp" label="Temperature" value={v} min={0} max={2} step={0.1} onChange={setV} readout={v.toFixed(1)} /></div>
}

function ToggleDemo() {
  const [p, setP] = useState(true)
  const [liked, setLiked] = useState(false)
  return (
    <div className="flex items-center gap-2">
      <IconToggleButton icon={Tags} label="Show labels" pressed={p} onToggle={() => setP(v => !v)} />
      <IconToggleButton icon={ThumbsUp} label="Good answer" tooltip="Good answer - tell us what worked" filled pressed={liked} onToggle={() => setLiked(v => !v)} />
      <IconToggleButton icon={Pin} size="sm" label="Pin" pressed={false} onToggle={noop} />
    </div>
  )
}

function ColumnDemo() {
  const [column, setColumn] = useState<number | null>(null)
  const costs = [0.4, 1.2, 0, 0.8, 2.6, 1.9, 0.3, 0, 1.1, 3.4, 2.2, 0.9, 1.5, 0.6].map((v, i) => ({ key: `d${i}`, value: v }))
  return (
    <ColumnChart className="w-full" values={costs} active={column} onActive={setColumn} ariaLabel="Cost per day"
      readout={<MetaList items={[column == null && 'Peak', `$${(column == null ? 3.4 : costs[column].value).toFixed(2)}`]} />}
      labels={costs.map((_, i) => String(((19 + i) % 30) + 1))}
      groups={costs.map((_, i) => (i < 11 ? 'Sep' : 'Oct'))} />
  )
}

function DisclosureDemo() {
  const [open, setOpen] = useState(false)
  return (
    <div className="w-80 space-y-3">
      <Disclosure summary={<span className="text-sm">Credentials for Jira</span>} meta="2 set"><Caption>The body of a disclosure, opened.</Caption></Disclosure>
      <Disclosure boxed summary={<span className="text-sm font-medium">Boxed disclosure</span>} defaultOpen><Caption>As an FAQ item or a decision row.</Caption></Disclosure>
      <div className="flex items-center gap-2"><DisclosureButton open={open} onToggle={() => setOpen(o => !o)} labels={{ show: 'Show steps', hide: 'Hide steps' }} controls="kit-collapse" /><DisclosureChevron open={open} /><Caption>DisclosureButton, DisclosureChevron</Caption></div>
      <Collapse open={open} id="kit-collapse"><Caption>Collapse: the animated body, inert while closed.</Caption></Collapse>
    </div>
  )
}

function ErrorBoundaryDemo() {
  const [broken, setBroken] = useState(false)
  const [reset, setReset] = useState(0)
  return (
    <ErrorBoundary resetKey={reset} fallback={() => (
      <ErrorState title="This part of the page failed" action={<Button variant="hyperlink" onClick={() => { setBroken(false); setReset(r => r + 1) }}>Try again</Button>}>The rest of the page still works.</ErrorState>
    )}>
      {broken ? <Thrower /> : <Button variant="secondary" onClick={() => setBroken(true)}>Throw while rendering</Button>}
    </ErrorBoundary>
  )
}

function Thrower(): ReactNode {
  throw new Error('a specimen that fails on purpose')
}

function LightboxDemo() {
  const [open, setOpen] = useState(false)
  return <><Button variant="secondary" onClick={() => setOpen(true)}>Open a lightbox</Button>{open && <Lightbox src="/favicon.svg" alt="The Proxyma mark" ariaLabel="Image" onClose={() => setOpen(false)} />}</>
}

function DialogDemo({ kind }: { kind: 'form' | 'confirm' | 'notice' | 'sheet' | 'modal' | 'shell' }) {
  const [open, setOpen] = useState(false)
  const close = () => setOpen(false)
  const label = { form: 'Open a form dialog', confirm: 'Open ConfirmDialog', notice: 'Open DialogNotice', sheet: 'Open a sheet (phone)', modal: 'Open Modal (a form)', shell: 'Open DialogShell (dirty)' }[kind]
  return (
    <>
      <Button variant="secondary" onClick={() => setOpen(true)}>{label}</Button>
      {open && kind === 'form' && (
        <DialogShell onClose={close} size="lg" ariaLabel="Add a web page">
          {requestClose => (
            <>
              <DialogHeader icon={<Globe className="text-brand-legible" />} title="Add a web page" subtitle="The page and the pages it links to." />
              <DialogBody>
                <FormStack>
                  <Input label="Address" placeholder="https://" />
                  <FormColumns><Input label="Depth" defaultValue="2" /><Input label="Pages" defaultValue="80" /></FormColumns>
                </FormStack>
              </DialogBody>
              <DialogFooter start={<InlineNotice tone="success">Reachable.</InlineNotice>}>
                <Button variant="secondary" onClick={requestClose}>Cancel</Button><Button onClick={close}>Add</Button>
              </DialogFooter>
            </>
          )}
        </DialogShell>
      )}
      {open && kind === 'shell' && (
        <DialogShell onClose={close} dirty discardLabels={DISCARD} size="md" cap ariaLabelledBy="kit-shell-title">
          {requestClose => (
            <>
              <DialogBody>
                <DialogTitle id="kit-shell-title">An unsaved edit</DialogTitle>
                <DialogDescription className="mt-2">Escape, the backdrop or Cancel asks before it throws the edit away.</DialogDescription>
              </DialogBody>
              <DialogFooter><Button variant="secondary" onClick={requestClose}>Cancel</Button><Button onClick={close}>Save</Button></DialogFooter>
            </>
          )}
        </DialogShell>
      )}
      {open && kind === 'confirm' && (
        <ConfirmDialog title="Delete this source?" description="Its documents leave the index. The site itself is not changed. Source:" subject="Product wiki"
          consequences={['80 documents are removed from search', 'Conversations keep their citations']}
          confirmLabel="Delete" cancelLabel="Cancel" onConfirm={close} onCancel={close} />
      )}
      {open && kind === 'notice' && (
        <DialogShell onClose={close} size="md" ariaLabel="Not in your plan">
          <DialogBody><DialogNotice title="Microsoft 365 is part of Enterprise" detail="Community connects every other source." action={<Button onClick={close}>See plans</Button>}>Upgrade to connect mail, files and calendars.</DialogNotice></DialogBody>
        </DialogShell>
      )}
      {open && kind === 'sheet' && (
        <DialogShell onClose={close} size="md" sheet ariaLabel="Conversation history">
          <DialogHeader title="Conversation history" wrapTitle />
          <DialogBody><Caption>A bottom sheet below sm, a centered dialog from it.</Caption></DialogBody>
        </DialogShell>
      )}
      {open && kind === 'modal' && (
        <Modal title="Issue a license" subtitle="Enterprise, one deployment" onClose={close} cornerBadge={<Badge label="Enterprise" variant="brand" />} onSubmit={close} discardLabels={DISCARD}
          footer={formId => <><Button variant="secondary" onClick={close}>Cancel</Button><Button type="submit" form={formId}>Issue</Button></>}>
          <FormStack><Input label="Customer" placeholder="Northwind Mills" /><Input label="Seats" defaultValue="25" /></FormStack>
        </Modal>
      )}
    </>
  )
}

function StatusScreenDemo() {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button variant="secondary" onClick={() => setOpen(true)}>Open StatusScreen</Button>
      {open && <StatusScreen busy title="Restarting" action={<Button variant="secondary" onClick={() => setOpen(false)}>Close (gallery only)</Button>}>Proxyma is starting again with the new network settings.</StatusScreen>}
    </>
  )
}

function SplashDemo() {
  const [state, setState] = useState<null | 'starting' | 'slow' | 'stopped'>(null)
  return (
    <>
      <div className="flex flex-wrap gap-2">
        <Button variant="secondary" onClick={() => setState('starting')}>Splash: starting</Button>
        <Button variant="secondary" onClick={() => setState('slow')}>after a minute</Button>
        <Button variant="secondary" onClick={() => setState('stopped')}>stopped</Button>
      </div>
      {state && (
        <div className="fixed inset-0 z-status flex flex-col items-center justify-center gap-4 p-4">
          <div className="fixed inset-0 bg-black/75 backdrop-blur-sm animate-fade-in" />
          <SplashScreen className="relative" turning={state !== 'stopped'} status={state === 'stopped' ? 'Proxyma has stopped' : 'Starting'}
            detail={state === 'slow' ? 'This is taking a little longer than usual. Please wait.' : state === 'stopped' ? 'You can close this page.' : null} />
          <Button variant="secondary" className="relative" onClick={() => setState(null)}>Close (gallery only)</Button>
        </div>
      )}
    </>
  )
}

function SettingsWindowDemo() {
  const [open, setOpen] = useState(false)
  const [tab, setTab] = useState<'retention' | 'appearance'>('retention')
  return (
    <>
      <Button variant="secondary" onClick={() => setOpen(true)}>Open SettingsWindow</Button>
      {open && (
        <SettingsWindow onClose={() => setOpen(false)} ariaLabel="Settings" discardLabels={DISCARD}
          groups={[{ label: 'Console', items: [{ key: 'retention', label: 'Data retention', icon: Database }, { key: 'appearance', label: 'Appearance', icon: Settings }] }]}
          active={tab} onChange={setTab} title={tab === 'retention' ? 'Data retention' : 'Appearance'}
          notice={<Callout title="Read-only">Only a Super admin changes these.</Callout>}>
          <SettingsSections intro="How long the console keeps what it is sent.">
            <SettingsSection title="Devices"><Input label="Release idle device bindings after (days)" defaultValue="0" /></SettingsSection>
          </SettingsSections>
        </SettingsWindow>
      )}
    </>
  )
}

function SettingsLayoutDemo() {
  const [rail, setRail] = useState<'providers' | 'agents' | 'search'>('agents')
  const [on, setOn] = useState(true)
  return (
    <div className="rounded-lg border border-line overflow-hidden h-[520px] flex flex-col surface-float w-full">
      <SettingsLayout
        rail={<SettingsRail ariaLabel="Settings" active={rail} onChange={setRail} groups={[{ label: 'AI & Knowledge', items: [{ key: 'providers', label: 'AI Providers', icon: Database }, { key: 'agents', label: 'Agents', icon: Bot }, { key: 'search', label: 'Search', icon: Search }] }]} />}
        title={rail === 'agents' ? 'Agents' : rail === 'providers' ? 'AI Providers' : 'Search'}
        titleActions={<Button size="sm"><Plus size={14} />Add</Button>}
        contentKey={rail}
        footer={<SaveBar dirty status={null} onCancel={noop} onSave={noop} labels={{ cancel: 'Cancel', save: 'Save', saving: 'Saving...' }} />}
      >
        <SettingsSections intro="How the agent works, and how long it may.">
          <SettingsSection title="Sub-agents" caption="Work handed to agents that run beside the main one." toggle={{ checked: on, onChange: setOn }}>
            <Field label="Step limit" hint="Each step is one model call.">{id => <Input id={id} defaultValue="40" />}</Field>
          </SettingsSection>
          <SettingsSection title="Danger zone" tone="danger"><Button variant="danger-quiet">Reset the agent</Button></SettingsSection>
        </SettingsSections>
      </SettingsLayout>
    </div>
  )
}

function TableDemo() {
  const [sort, setSort] = useState<SortState<'name' | 'size'> | null>({ key: 'name', dir: 'asc' })
  const onSort = (k: 'name' | 'size') => setSort(s => ({ key: k, dir: s?.key === k && s.dir === 'asc' ? 'desc' : 'asc' }))
  return (
    <TableCard ariaLabel="Users" className="w-full" footer={<TableFooter action={<Button variant="hyperlink">Show all</Button>}>Showing 2 of 312</TableFooter>}>
      <table className={TABLE_CLASS}>
        <thead><tr className={THEAD_TR}>
          <SortTh label="Name" sortKey="name" sort={sort} onSort={onSort} />
          <Th>Role</Th>
          <SortTh label="Size" sortKey="size" align="right" sort={sort} onSort={onSort} tooltip="Indexed size of what they own" />
        </tr></thead>
        <tbody>
          {[['Anna Weber', 'anna@northwind.example', 'Admin', '1.2 GB'], ['Minh Tran', 'minh@northwind.example', 'Viewer', '340 MB']].map(r => (
            <tr key={r[0]} className={`${TR} ${TR_INTERACTIVE}`}>
              <td className={TD}><IdentityCell name={r[0]} sub={r[1]} leading={<Avatar name={r[0]} size="sm" />} /></td>
              <td className={TD} data-label="Role"><Badge label={r[2]} variant="outline" /></td>
              <td className={`${TD} text-right data-readout`} data-label="Size">{r[3]}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </TableCard>
  )
}

function PagerDemo() {
  const [page, setPage] = useState(2)
  const props = { page, pages: 9, onPage: setPage, countText: '172 events', pageText: `Page ${page + 1} of 9`, previousLabel: 'Previous', nextLabel: 'Next', numbered: true, pageLabel: (p: number) => `Page ${p + 1}` }
  return <div className="w-full space-y-2"><Pager {...props} /><ListPager {...props} pages={1} /><Caption>ListPager hides itself on one page (the second pager above is that).</Caption></div>
}

function ListEditorDemo() {
  const [headers, setHeaders] = useState([{ name: 'X-Title', value: 'Proxyma' }])
  return (
    <div className="w-80">
      <ListEditor items={headers} addLabel="Add header" removeLabel="Remove header" onAdd={() => setHeaders(h => [...h, { name: '', value: '' }])}
        onRemove={i => setHeaders(h => h.filter((_, j) => j !== i))}
        renderRow={(h, i) => <><Input className="flex-1" value={h.name} aria-label="Header" onChange={e => setHeaders(r => r.map((x, j) => j === i ? { ...x, name: e.target.value } : x))} /><Input className="flex-1" value={h.value} aria-label="Value" onChange={e => setHeaders(r => r.map((x, j) => j === i ? { ...x, value: e.target.value } : x))} /></>} />
    </div>
  )
}

function SelectableHeaderDemo() {
  const [picked, setPicked] = useState<'none' | 'some' | 'all'>('some')
  return <div className="w-80"><SelectableListHeader state={picked} onToggle={() => setPicked(p => (p === 'all' ? 'none' : 'all'))} label="Select every entry shown">12 entries</SelectableListHeader></div>
}

function FilterDemo() {
  const [filter, setFilter] = useState<'all' | 'jira' | 'web'>('all')
  const [search, setSearch] = useState('')
  return (
    <FilterBar className="w-full"
      filters={<FilterChips ariaLabel="Type" active={filter} onChange={setFilter} options={[{ key: 'all', label: 'All', count: 12 }, { key: 'jira', label: 'Jira', count: 4 }, { key: 'web', label: 'Web', count: 8 }]} />}
      search={<SearchInput value={search} onChange={setSearch} placeholder="Search" clearLabel="Clear search" />}
    >
      <span className="flex items-center gap-2 text-xs text-ink-caption">Shared only <ToggleSwitch size="sm" checked={false} onChange={noop} ariaLabel="Shared only" /></span>
    </FilterBar>
  )
}

function FilterTilesDemo() {
  const [k, setK] = useState<'all' | 'active' | 'expiring' | 'expired'>('all')
  return (
    <FilterTiles className="w-full" label="Licenses by state" active={k} onChange={setK} clearKey="all"
      tiles={[{ key: 'all', label: 'All', value: 48 }, { key: 'active', label: 'Active', value: 41 }, { key: 'expiring', label: 'Expiring', value: 4, sub: 'within 30 days' }, { key: 'expired', label: 'Expired', value: 3 }]} />
  )
}

function DropDemo() {
  const drop = useFileDrop({ onDrop: noop })
  return (
    <DropArea className="w-full" active={drop.active} handlers={drop.handlers} label="Click or drop files here" hint="PDF, Word, Markdown, up to 50 MB each" onPick={noop}
      secondaryAction={<button type="button" className={textLink()}>or select a folder</button>} />
  )
}

function ChatDemo() {
  const [open, setOpen] = useState(true)
  const [citations, setCitations] = useState(false)
  return (
    <div className="space-y-3 w-full max-w-3xl">
      <ChatMessage role="user">Which customers run firmware 4.2 on the DX-400 drives?</ChatMessage>
      <ActivityLine icon={Brain} state="running" label="Thinking" elapsed="4s" />
      <ActivityLine icon={FileSearch} state="done" label="Searched 3 sources" elapsed="12s" open={open} onToggle={() => setOpen(o => !o)} />
      <Presence show={open}>
        <div className="space-y-1">
          <ToolCallRow icon={Search} state="done" label="Searched install base for DX-400" elapsed="2s" onOpen={noop} />
          <ToolCallRow icon={FileText} state="failed" label="Read sbom-dx400.json" error="File not found in the index" onOpen={noop} />
        </div>
      </Presence>
      <ChatMessage role="assistant" footer={(
        <CitationsFold label="2 sources" open={citations} onToggle={() => setCitations(o => !o)}>
          <CitationList>
            <CitationRow mark={<BrandMark id="confluence" />} typeLabel="Confluence page" name="DX-400 install base" score="82%" />
            <CitationRow mark={<BrandMark id="files" />} typeLabel="File" name="sbom-dx400-4.2.json" score="64%" />
          </CitationList>
        </CitationsFold>
      )} actions={<CopyButton getText={() => 'Three customers run 4.2.'} label="Copy answer" copiedLabel="Copied" />}>
        Three customers run 4.2: Northwind Mills, Arcadia Water and Halden Port.
      </ChatMessage>
      <ChatMessage role="user" queued>Queued: and on the DX-600?</ChatMessage>
    </div>
  )
}

function ToolGroupDemo() {
  const [open, setOpen] = useState(false)
  return (
    <ToolCallGroup open={open} onToggle={() => setOpen(o => !o)} toggleLabels={{ show: 'Show 3 earlier steps', hide: 'Hide earlier steps' }}
      rows={[
        { key: 'a', shown: open, node: <ToolCallRow icon={Search} state="done" label="Searched SBOMs for libfoo" elapsed="1s" /> },
        { key: 'b', shown: open, node: <ToolCallRow icon={FileText} state="done" label="Read advisory.md" elapsed="1s" /> },
        { key: 'c', shown: true, node: <ToolCallRow icon={Search} state="running" label="Searching tickets for SEC-412" /> },
      ]} />
  )
}

function SubAgentDemo() {
  const [open, setOpen] = useState(true)
  return (
    <SubAgentPanel icon={Bot} title="Check SBOMs for the drive line" state="running" backgroundLabel="Background" elapsed="1m 12s" open={open} onToggle={() => setOpen(o => !o)}
      toggleLabels={{ show: 'Show steps', hide: 'Hide steps' }} onStop={noop} stopLabel="Stop this sub-agent">
      <ToolCallRow icon={Search} state="running" label="Searching SBOMs for libfoo" />
    </SubAgentPanel>
  )
}

function ChoiceDemo() {
  const [picked, setPicked] = useState<string[]>(['drives'])
  const toggle = (k: string) => setPicked(p => (p.includes(k) ? p.filter(x => x !== k) : [...p, k]))
  return (
    <PromptCard icon={MessageCircleQuestion} title="Which product lines are in scope?" ariaLabel="Question" actions={<><Button variant="secondary">Skip</Button><Button>Send answer</Button></>}>
      <div className="space-y-4">
        <div className="space-y-1.5">
          <ChoiceRow checked={picked.includes('drives')} multi label="Drives" description="DX-400, DX-600" onToggle={() => toggle('drives')} />
          <ChoiceRow checked={picked.includes('controllers')} multi label="Controllers" onToggle={() => toggle('controllers')} />
        </div>
        <PromptSection eyebrow="Deadline" title="Is the 24-hour early warning already sent?">
          <ChoiceRow checked={false} label="Yes" onToggle={noop} />
        </PromptSection>
      </div>
    </PromptCard>
  )
}

function SourcesDemo() {
  return (
    <div className="-mx-3 sm:-mx-4 w-full">
      <SourceListHeader columns={['Name', 'Items', 'Last sync', 'Status']} />
      <ListGroupHeading label="Jira" count={2} />
      <SourceRow mark={<BrandMark id="jira" />} name="SEC - Security" items="1,204" synced="3 min ago" phoneMeta={['1,204 items', '3 min ago']} onOpen={noop} healthyMark={<StatusMark state="ok-graph" label="Indexed and in the knowledge graph" />} />
      <SourceRow mark={<BrandMark id="confluence" />} name="PRD - Product docs" tag="shared" items="380+" synced="Syncing" status="Indexing 40 of 380" healthMark={<StatusMark state="working" />} phoneMeta={['380+ items']} onOpen={noop}
        nameLink={cls => <ExternalLink href="https://example.atlassian.net/wiki/spaces/PRD" className={cls}>PRD - Product docs</ExternalLink>} />
      <SourceRow mark={<BrandMark id="files" />} name="SBOMs" items="12" synced="Yesterday" status="2 failed" healthMark={<StatusMark state="failed" label="2 failed" />} phoneMeta={['12 items']} onOpen={noop} />
      <SourceRowSkeleton />
    </div>
  )
}

function MenuSurfaceDemo() {
  return (
    <MenuSurface trigger={({ open, toggle, controls }) => (
      <button type="button" className={buttonClass('secondary')} onClick={toggle} aria-expanded={open} aria-haspopup="menu" aria-controls={controls}>MenuSurface</button>
    )}>
      {close => (
        <>
          <MenuIdentity>anna.weber@northwind.example</MenuIdentity>
          <MenuGroup>
            <button type="button" className={menuRow()} onClick={close}><Settings size={15} aria-hidden="true" />Settings</button>
            <button type="button" className={menuRow('active')} onClick={close}><Users size={15} aria-hidden="true" />Administration</button>
          </MenuGroup>
          <MenuGroup>
            <AppearanceRowDemo />
            <LanguageMenuRow title="Language" locales={['en', 'vi'] as const} current="en" nameOf={l => (l === 'en' ? 'English' : 'Tiếng Việt')} onPick={noop} />
          </MenuGroup>
          <MenuGroup><button type="button" className={menuRow('danger')} onClick={close}>Sign out</button></MenuGroup>
        </>
      )}
    </MenuSurface>
  )
}

function AppearanceRowDemo() {
  const [p, setP] = useState<AppearancePreference>('system')
  return <AppearanceMenuRow title="Appearance" names={NAMES} current={p} onChoose={setP} />
}

function SubmenuDemo() {
  const [k, setK] = useState<'compact' | 'comfortable'>('comfortable')
  return (
    <MenuSurface trigger={({ open, toggle, controls }) => (
      <button type="button" className={buttonClass('secondary')} onClick={toggle} aria-expanded={open} aria-haspopup="menu" aria-controls={controls}>A menu with a submenu</button>
    )}>
      {() => (
        <MenuGroup>
          <SubmenuRow icon={<Settings size={15} aria-hidden="true" />} title="Density" value={k === 'compact' ? 'Compact' : 'Comfortable'} valueText={k}
            options={[{ key: 'compact', label: 'Compact' }, { key: 'comfortable', label: 'Comfortable' }]} current={k} onPick={setK} closeOnPick />
        </MenuGroup>
      )}
    </MenuSurface>
  )
}

function TabsDemo() {
  const [t, setT] = useState<'overview' | 'users' | 'usage' | 'audit'>('overview')
  return <SubNavTabs ariaLabel="Sections" active={t} onChange={setT} tabs={[{ key: 'overview', label: 'Overview' }, { key: 'users', label: 'Users', count: 12 }, { key: 'usage', label: 'Usage & Cost' }, { key: 'audit', label: 'Logs', locked: true, lockReason: 'Part of Enterprise' }]} />
}

function MarketingSceneDemo() {
  const [state, setState] = useState<'playing' | 'paused' | 'done'>('playing')
  return (
    <SceneFrame scale="md" height="h-64" label="The chat answering from two sources" caption="The agent searches, then answers with its sources."
      controls={<SceneControl state={state} onPause={() => setState('paused')} onPlay={() => setState('playing')} onReplay={() => setState('playing')} labels={{ pause: 'Pause', play: 'Play', replay: 'Replay' }} />}>
      <div className="p-4 space-y-2">
        <ChatMessage role="user">Summarize the open security tickets.</ChatMessage>
        <ActivityLine icon={FileSearch} state="done" label="Searched 2 sources" elapsed="3s" />
      </div>
    </SceneFrame>
  )
}

function FilePickerDemo() {
  const [names, setNames] = useState<string[]>([])
  return (
    <div className="space-y-2">
      <FilePickerButton accept=".json" onFiles={f => setNames(f.map(x => x.name))}><Upload size={14} />Import policy</FilePickerButton>
      <Caption>{names.length ? names.join(', ') : 'Nothing chosen.'}</Caption>
    </div>
  )
}

const box = 'rounded-lg border border-line overflow-hidden w-full'

// -- the specimens ---------------------------------------------------------------------------

export const SPECIMENS: Record<string, KitSpecimen[]> = {
  // Foundations and motion
  FadeSwap: [{ title: 'New content fades in on a key', render: () => <FadeSwapDemo /> }],
  Presence: [{ title: 'Something in the flow comes and goes', render: () => <PresenceDemo /> }],
  ErrorBoundary: [{ title: 'A part that fails, alone', render: () => <ErrorBoundaryDemo /> }],

  // Text
  Title: [{ title: 'Title 18/600', render: () => <Title>Agents</Title> }],
  PageTitle: [{ title: 'With meta and actions', wide: true, render: () => <PageTitle className="w-full" meta="42 sources, 12,840 documents" actions={<Button><Plus size={14} />Connect</Button>}>Sources</PageTitle> }],
  PageHero: [{ title: 'The empty page greeting', wide: true, render: () => <PageHero centered className="w-full" title="What do you need done?" subtitle="Ask about anything in your sources, or hand the agent a task." /> }],
  SectionHeading: [
    { title: 'With caption and action', render: () => <SectionHeading className="w-80" caption="Work handed to agents that run beside the main one." action={<ToggleSwitch checked onChange={noop} ariaLabel="Sub-agents" />}>Sub-agents</SectionHeading> },
    { title: 'Danger', render: () => <SectionHeading tone="danger" flush>Danger zone</SectionHeading> },
  ],
  FieldLabel: [{ title: 'Label 14/500', render: () => <FieldLabel htmlFor="x">Step limit</FieldLabel> }],
  Caption: [{ title: 'Tones', render: () => <div className="space-y-1"><Caption>Caption 12, caption ink.</Caption><Caption tone="warning" icon={ShieldAlert}>A warning caption with its glyph.</Caption><Caption tone="danger">A danger caption.</Caption><Caption tone="success">A success caption.</Caption><Caption tone="brand">A brand caption.</Caption></div> }],
  Eyebrow: [{ title: 'What a card shows', render: () => <Eyebrow action={<Button variant="hyperlink">View all</Button>}>Requests per day</Eyebrow> }],
  InlineCode: [{ title: 'Code in prose', render: () => <Paragraph>Run <InlineCode>proxyma --reset</InlineCode> to start again.</Paragraph> }],
  Readout: [{ title: 'Figures in a sentence', render: () => <Paragraph>Spent <Readout tone="default">$12.40</Readout> on <Readout tone="caption">1,284</Readout> calls; <Readout tone="danger">3</Readout> failed.</Paragraph> }],
  Paragraph: [{ title: 'lead, body, small', render: () => <div className="w-80 space-y-2"><Paragraph size="lead">Your knowledge, and an agent that can use it.</Paragraph><Paragraph>Body text in a dialog or a card reads at 14px in secondary ink.</Paragraph><Paragraph size="small" tone="caption">A small note under a block.</Paragraph></div> }],
  MetaList: [{ title: 'Facts joined by a dot', render: () => <MetaList className="text-xs text-ink-caption" items={['12 items', 'synced 3 min ago', false, 'shared']} /> }],
  ExternalLink: [{ title: 'The one link out', render: () => <Paragraph>Read the <ExternalLink href="https://proxyma.ai/docs/">manual</ExternalLink> first.</Paragraph> }],

  // Controls
  Button: [
    { title: 'Variants', wide: true, render: () => <div className="flex flex-wrap gap-3"><Button>Primary</Button><Button variant="featured">Featured</Button><Button variant="secondary">Secondary</Button><Button variant="danger">Danger</Button><Button variant="hyperlink">Hyperlink</Button><Button variant="quiet">Quiet</Button><Button variant="danger-quiet">Delete</Button></div> },
    { title: 'Sizes', render: () => <div className="flex flex-wrap items-center gap-3"><Button size="sm">Small</Button><Button size="md">Medium</Button><Button size="lg">Large</Button></div> },
    { title: 'Loading, disabled, disabled with a reason', render: () => <div className="flex flex-wrap items-start gap-3"><Button loading>Saving</Button><Button disabled>Disabled</Button><Button disabled disabledReason="Only an Owner can share this account">Share</Button></div> },
  ],
  ButtonLink: [{ title: 'An anchor as a button', render: () => <div className="flex flex-wrap gap-3"><ButtonLink href="#download">Download</ButtonLink><ButtonLink variant="secondary" href="https://proxyma.ai/pricing/" external>Pricing</ButtonLink></div> }],
  ExternalMark: [{ title: 'The outbound glyph', render: () => <span className="flex items-center gap-1 text-sm text-ink-secondary">A card that leaves <ExternalMark /></span> }],
  Input: [{ title: 'Label, hint, error, disabled', render: () => <InputDemo /> }],
  Textarea: [{ title: 'Multi-line, mono', render: () => <div className="w-72 space-y-4"><Textarea label="Instructions" defaultValue="Answer in short paragraphs." hint="The agent reads this first." /><Textarea label="Certificate" mono defaultValue="-----BEGIN CERTIFICATE-----" /></div> }],
  FieldNote: [{ title: 'The line under a field', render: () => <div className="w-72"><FieldNote hint="A hint under the field." /><FieldNote error="An error replaces the hint." /></div> }],
  PasswordInput: [{ title: 'A fresh secret with a reveal', render: () => <div className="w-72"><PasswordInput label="Passphrase" hint="At least 12 characters." labels={{ show: 'Show password', hide: 'Hide password' }} defaultValue="correct horse" /></div> }],
  SearchInput: [{ title: 'With a clear button', render: () => <SearchDemo /> }],
  SecretInput: [{ title: 'Stored and empty', render: () => <SecretDemo /> }],
  Select: [{ title: 'Plain and searchable', render: () => <SelectDemo /> }],
  Combobox: [{ title: 'Typed or picked', render: () => <ComboboxDemo /> }],
  DateTimePicker: [{ title: 'Date and time, date only', render: () => <DateDemo /> }],
  Checkbox: [{ title: 'Checkbox, RadioGroup, SelectBox', render: () => <CheckDemo /> }],
  SelectBox: [{ title: 'A row box and an indeterminate header', render: () => <div className="flex gap-3"><SelectBox checked label="Select row" onToggle={noop} /><SelectBox checked={false} indeterminate label="Select all" onToggle={noop} /><SelectBox checked={false} disabled label="Disabled" onToggle={noop} /></div> }],
  RadioGroup: [{ title: 'Vertical and horizontal', render: () => <div className="space-y-3"><RadioGroup ariaLabel="Context" value="32k" onChange={noop} figures horizontal options={[{ value: '8k', label: '8k' }, { value: '32k', label: '32k' }, { value: '128k', label: '128k' }]} /><RadioGroup ariaLabel="Mode" value="a" onChange={noop} options={[{ value: 'a', label: 'Ask first' }, { value: 'b', label: 'Allow', hint: 'Every change still shows.' }]} /></div> }],
  ChoiceMark: [{ title: 'Ring, square, indeterminate', render: () => <div className="flex gap-3"><ChoiceMark checked /><ChoiceMark checked={false} /><ChoiceMark multi checked /><ChoiceMark multi checked={false} /><ChoiceMark multi checked={false} indeterminate /></div> }],
  SegmentedControl: [{ title: 'Boxed, text, warning', render: () => <SegDemo /> }],
  ToggleSwitch: [{ title: 'Sizes and states', render: () => <SwitchDemo /> }],
  Slider: [{ title: 'With a readout', render: () => <SliderDemo /> }],
  Divider: [{ title: 'Horizontal and vertical', render: () => <div className="w-72 space-y-3"><Divider /><div className="flex items-center gap-3 h-6"><Caption>Left</Caption><Divider vertical /><Caption>Right</Caption></div></div> }],
  CopyButton: [{ title: 'Icon, labelled, minted on demand', render: () => <div className="flex flex-wrap items-center gap-3"><CopyButton getText={() => 'copied'} label="Copy" copiedLabel="Copied" /><CopyButton variant="button" getText={() => 'copied'} label="Copy command" copiedLabel="Copied" /><CopyButton icon={Globe} status getText={async () => 'https://example/shared/x'} label="Copy share link" tooltip="A read-only link to this conversation" copiedLabel="Share link copied" /></div> }],
  CopyField: [{ title: 'A value to copy', render: () => <div className="w-80"><CopyField label="Deployment code" value="PXM-7F3A-2C91-D04E" copyLabel="Copy code" copiedLabel="Copied" hint="Send it to us to get an air-gapped trial key." /></div> }],
  OneTimeSecret: [{ title: 'A token shown once', wide: true, render: () => <OneTimeSecret className="w-full max-w-xl" title="Copy this token now" value="pxm_9f2c4e1a7b3d5e8f0a1c2b3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f" label="Ingestion token" copyLabel="Copy token" copiedLabel="Copied">It is shown once. Store it where your pipeline reads its secrets - it cannot be shown again.</OneTimeSecret> }],
  IconButton: [{ title: 'Sizes, danger, busy', render: () => <div className="flex items-center gap-2"><IconButton icon={Search} label="Search" size="sm" /><IconButton icon={Search} label="Search" /><IconButton icon={Settings} label="Settings" size="lg" /><IconButton icon={Trash2} label="Delete" tone="danger" /><IconButton icon={RefreshCw} label="Sync now" busy /></div> }],
  IconToggleButton: [{ title: 'On, off, filled', render: () => <ToggleDemo /> }],
  IconCountButton: [{ title: 'The agents button', render: () => <div className="flex items-center gap-2"><IconCountButton icon={Workflow} label="Agents" onClick={noop} /><IconCountButton icon={Workflow} label="Agents - 2 sub-agents working" count="2 agents" working onClick={noop} /></div> }],
  MenuTrigger: [{ title: 'A toolbar choice', render: () => <div className="flex gap-3"><MenuTrigger icon={ShieldCheck} value="Ask" ariaLabel="Permission: Ask" open={false} onClick={noop} /><MenuTrigger icon={ShieldAlert} value="Auto" tone="warning" ariaLabel="Permission: Auto" open={false} onClick={noop} /></div> }],
  FilePickerButton: [{ title: 'Opens the file picker', render: () => <FilePickerDemo /> }],

  // Marks and meters
  Badge: [
    { title: 'Variants', wide: true, render: () => <div className="flex flex-wrap gap-2"><Badge label="Admin" variant="brand" /><Badge label="Recommended" variant="featured" /><Badge label="Beta" variant="electric" /><Badge label="Active" variant="success" /><Badge label="Expiring" variant="warning" /><Badge label="Revoked" variant="danger" /><Badge label="Draft" /><Badge label="Viewer" variant="outline" /><Badge label="Bad" variant="outline" tone="danger" /><Badge label="12" numeric /></div> },
    { title: 'Small', render: () => <div className="flex gap-2"><Badge size="sm" label="Default" variant="outline" /><Badge size="sm" label="New" variant="brand" /></div> },
  ],
  TierBadge: [{ title: 'Tiers', render: () => <div className="flex gap-2"><TierBadge tier="COMMUNITY" /><TierBadge tier="ENTERPRISE" /><TierBadge tier="PROFESSIONAL" /><TierBadge tier={null} /></div> }],
  PlanBadge: [{ title: 'With a trial', render: () => <div className="flex flex-col gap-2"><PlanBadge tier="ENTERPRISE" trial daysLeft={21} trialLabel="Trial - 21 days left" /><PlanBadge tier="ENTERPRISE" trial daysLeft={3} trialLabel="Trial - 3 days left" /></div> }],
  Chip: [{ title: 'Outline, file, solid, filter, link', wide: true, render: () => <div className="flex flex-wrap gap-2"><Chip label="report.pdf" variant="file" icon={<FileText />} onRemove={noop} removeLabel="Remove report.pdf" /><Chip label="Research" variant="solid" /><Chip label="release" /><Chip label="Applies" variant="filter" pressed count={67} onClick={noop} /><Chip label="Tailored out" variant="filter" count={20} onClick={noop} /><Chip label="attachment.png" variant="file" icon={<FileText />} href="/favicon.svg" target="_blank" /></div> }],
  Spinner: [{ title: 'Sizes and the graph', render: () => <div className="flex items-center gap-3"><Spinner size="sm" /><Spinner /><Spinner size="lg" /><Spinner size="sm" variant="spokes" /><Spinner size={12} className="text-ink-caption" /></div> }],
  RefreshIcon: [{ title: 'At rest and turning', render: () => <div className="flex gap-3 text-ink-caption"><RefreshIcon /><RefreshIcon spinning /></div> }],
  Skeleton: [{ title: 'Bars', render: () => <div className="w-72 space-y-2"><Skeleton className="h-3 w-40" /><Skeleton className="h-3 w-56" /><Skeleton className="h-24 w-full rounded-lg" /></div> }],
  ListRowSkeleton: [{ title: 'A list row loading', render: () => <div className="w-80"><ListRowSkeleton /><ListRowSkeleton action={false} /></div> }],
  SwitchRowSkeleton: [{ title: 'A switch row loading', render: () => <div className="w-80"><SwitchRowSkeleton /></div> }],
  FieldSkeleton: [{ title: 'A field loading', render: () => <div className="w-80 space-y-4"><FieldSkeleton /><Input label="The field it stands for" defaultValue="Loaded" /></div> }],
  RadioGroupSkeleton: [{ title: 'A radio group loading', render: () => <div className="w-80 space-y-3"><RadioGroupSkeleton count={2} /><RadioGroupSkeleton count={3} horizontal /></div> }],
  StateMark: [{ title: 'A piece of work, by color', render: () => <div className="flex gap-3">{(['running', 'done', 'failed', 'partial', 'stopped', 'pending'] as const).map(s => <StateMark key={s} icon={FileSearch} state={s} />)}</div> }],
  StatusMark: [{ title: 'Health, and what a sync noticed', wide: true, render: () => <div className="flex flex-wrap gap-3">{(['ok', 'ok-graph', 'working', 'graph', 'failed', 'warning', 'paused', 'queued', 'none', 'detected', 'modified', 'deleted'] as const).map(s => <StatusMark key={s} state={s} label={s} />)}</div> }],
  StatusDot: [{ title: 'Tones', render: () => <div className="flex items-center gap-3"><StatusDot /><StatusDot tone="warning" /><StatusDot tone="danger" /><StatusDot tone="muted" pulse={false} /></div> }],
  WorkingBadge: [{ title: 'Work going on elsewhere', render: () => <WorkingBadge count={2} label="2 conversations still working - go to the latest" onClick={noop} /> }],
  Avatar: [{ title: 'A person, the agent, two sizes', render: () => <div className="flex items-center gap-2"><Avatar name="Anna Weber" /><Avatar name="Minh Tran" size="sm" /><Avatar kind="agent" /><Avatar kind="agent" size="sm" /><Avatar name={null} /></div> }],
  AvatarStack: [{ title: 'Who else is here', render: () => <AvatarStack label="Also here: Lan, Minh, Quan, Thao, Bao" people={['Lan Nguyen', 'Minh Tran', 'Quan Le', 'Thao Pham', 'Bao Vo'].map(n => ({ key: n, name: n, tooltip: `${n} is on Moves` }))} /> }],
  Stepper: [
    { title: 'Vertical, with details', render: () => <Stepper label="Restore progress" className="w-80" steps={[{ key: 'a', label: 'Stop the services', state: 'done' }, { key: 'b', label: 'Restore the database', state: 'running', detail: '1.2 of 3.4 GB' }, { key: 'c', label: 'Rebuild the index', state: 'pending' }, { key: 'd', label: 'Start again', state: 'pending' }]} /> },
    { title: 'Horizontal, with a failure', wide: true, render: () => <Stepper orientation="horizontal" label="Activation" steps={[{ key: 'a', label: 'Key', state: 'done' }, { key: 'b', label: 'Deployment', state: 'failed' }, { key: 'c', label: 'Done', state: 'pending' }]} /> },
  ],
  ProgressBar: [{ title: 'Determinate, indeterminate, tones', render: () => <div className="w-72 space-y-3"><ProgressBar value={62} label="Upload" /><ProgressBar label="Indexing" /><ProgressBar value={85} tone="warning" size="xs" /><ProgressBar value={96} tone="danger" size="md" /></div> }],
  ProgressRow: [{ title: 'Progress with its words', render: () => <div className="w-80 space-y-4"><ProgressRow label="Uploading 12 files" figures="4 of 12" value={33} action={<Button variant="quiet">Cancel</Button>} /><ProgressRow label="Re-indexing" /></div> }],
  Meter: [{ title: 'Used against a limit', render: () => <div className="w-72 space-y-4"><Meter used={4.2e9} limit={5e9} format={n => `${(n / 1e9).toFixed(1)} GB`} label="Storage" caption="Resets on the 1st" /><Meter used={12} limit={null} label="Seats" unlimitedLabel="No limit" /></div> }],
  BarList: [
    { title: 'A ranked list with bars', render: () => <BarList className="w-80" label="Top features" rows={[{ key: 'chat', label: 'Chat', value: 1284 }, { key: 'search', label: 'Search', value: 640 }, { key: 'graph', label: 'Knowledge graph', value: 210 }, { key: 'other', label: 'Other', value: 40, muted: true }]} /> },
    { title: 'Rows that open their details', render: () => <BarList className="w-80" label="Failure causes" share={false} rows={[{ key: 'timeout', label: 'timeout', value: 42, detail: 'Sync 30, model 12', onSelect: () => undefined }, { key: 'auth', label: 'auth', value: 9, detail: 'Sync 9', onSelect: () => undefined }, { key: 'io', label: 'io', value: 4, detail: 'Items 4', onSelect: () => undefined }]} /> },
  ],
  StackedBar: [{ title: 'One bar in shares', render: () => <div className="w-72 space-y-3"><StackedBar label="Spend by service" segments={[{ value: 6, className: 'bg-chart-1', label: 'Chat' }, { value: 3, className: 'bg-chart-2', label: 'Embedding' }, { value: 1, className: 'bg-chart-3', label: 'Rerank' }]} /></div> }],
  LegendSwatch: [{ title: 'Legend entries', render: () => <div className="flex gap-4"><LegendSwatch swatchClass="bg-chart-1" label="Chat" value="$6.00" /><LegendSwatch swatchClass="bg-chart-2" label="Embedding" value="$3.00" /><LegendSwatch swatchClass="bg-chart-other" label="Other" /></div> }],
  UsageRing: [{ title: 'A percent ring', render: () => <div className="flex gap-3 text-ink-caption"><UsageRing value={24} /><span className="text-warning"><UsageRing value={84} /></span><span className="text-danger"><UsageRing value={97} size={24} /></span></div> }],
  ColumnChart: [{ title: 'Cost per day (arrows move it)', wide: true, render: () => <ColumnDemo /> }],
  Tooltip: [{ title: 'Hover or keyboard focus', render: () => <div className="flex gap-3"><Tooltip label="A hint in words"><button type="button" className={iconButton('md')} aria-label="Settings"><Settings size={16} aria-hidden="true" /></button></Tooltip><Tooltip label="Below the trigger" side="bottom"><Button variant="secondary">Bottom</Button></Tooltip></div> }],
  Collapse: [{ title: 'The animated body', render: () => <DisclosureDemo /> }],
  Disclosure: [{ title: 'Plain and boxed', render: () => <DisclosureDemo /> }],
  DisclosureChevron: [{ title: 'Closed and open', render: () => <div className="flex gap-3 text-ink-caption"><DisclosureChevron open={false} /><DisclosureChevron open /></div> }],
  DisclosureButton: [{ title: 'A chevron that opens', render: () => <DisclosureDemo /> }],

  // Brand
  Logo: [
    { title: 'The lockup at its sizes', wide: true, render: () => <div className="flex flex-wrap items-center gap-8"><Logo size={16} /><Logo size={24} /><Logo /><Logo size={40} /></div> },
    { title: 'With a subtitle (the consoles)', render: () => <div className="flex flex-col gap-4"><Logo subtitle="License Manager" /><Logo subtitle="Telemetry" /></div> },
    { title: 'Stacked, at hero size (the Community new chat)', render: () => <div className="flex items-end gap-10"><Logo size={64} stacked /><Logo size={72} stacked /></div> },
    { title: 'Mark alone', render: () => <div className="flex items-end gap-4"><Logo markOnly size={16} /><Logo markOnly size={24} ariaLabel="Proxyma" /><Logo markOnly size={40} /><Logo markOnly size={72} /><Logo markOnly size={80} className="animate-mark-idle" /></div> },
  ],
  ProxymaIcon: [{ title: 'The mark itself', render: () => <div className="flex items-end gap-4"><ProxymaIcon size={16} /><ProxymaIcon size={32} /><ProxymaIcon size={64} /><ProxymaIcon size={128} /></div> }],
  BrandMark: [{ title: 'Every mark', wide: true, render: () => (
    <div className="grid grid-cols-3 sm:grid-cols-6 gap-3 w-full">
      {Object.keys(MARK_PATHS).filter(isMarkId).map(id => (
        <span key={id} className="flex items-center gap-2 text-xs text-ink-secondary min-w-0"><BrandMark id={id} className="text-ink-primary shrink-0" /><span className="truncate">{id}</span></span>
      ))}
    </div>
  ) }],
  ProxymaBackground: [{ title: 'The canvas behind a page', render: () => <div className="relative h-24 w-72 rounded-lg border border-line overflow-hidden"><ProxymaBackground className="absolute inset-0 z-0" /><span className="relative p-3 block text-xs text-ink-caption">Content over the canvas.</span></div> }],

  // Surfaces and states
  Card: [
    { title: 'Subtle, outline, solid, danger, highlighted', wide: true, render: () => <div className="grid sm:grid-cols-2 gap-3 w-full"><Card><CardHeader title="Subtle" readout="7 days" /><Caption>The app's card.</Caption></Card><Card variant="outline"><CardHeader title="Outline" /><Caption>Border only - the site's cards.</Caption></Card><Card variant="solid"><CardHeader title="Solid" /><Caption>Over a picture.</Caption></Card><Card tone="danger"><SectionHeading tone="danger" flush>Danger zone</SectionHeading><Caption>Deletes everything.</Caption></Card><Card highlighted><CardHeader title="Highlighted" /><Caption>A brand border, never a glow.</Caption></Card><Card interactive tabIndex={0}><CardHeader title="Interactive" /><Caption>Opens something.</Caption></Card></div> },
    { title: 'States of a record', wide: true, render: () => <div className="grid sm:grid-cols-3 gap-3 w-full"><Card state="proposed"><Eyebrow>Proposed</Eyebrow><Caption>Not confirmed yet.</Caption></Card><Card state="agent"><Eyebrow>From the agent</Eyebrow><Caption>Proposed by the agent.</Caption></Card><Card state="confirmed"><Eyebrow>Confirmed</Eyebrow><Caption>A person decided it.</Caption></Card></div> },
  ],
  CardHeader: [{ title: 'Eyebrow, readout, actions', render: () => <Card className="w-80"><CardHeader title="Requests per day" readout="1,284" actions={<Button variant="hyperlink">Export</Button>} divided /><CardBody><Caption>The body.</Caption></CardBody></Card> }],
  CardBody: [{ title: 'A padded body', render: () => <Card padding="none" className="w-80"><CardBody><Caption>CardBody pads a padding-none card.</Caption></CardBody></Card> }],
  ChartCard: [{ title: 'One chart in a card', wide: true, render: () => <div className="grid sm:grid-cols-2 gap-3 w-full"><ChartCard title="Calls per day" readout="1,284"><div className="h-full rounded-md bg-brand-accent/20" /></ChartCard><ChartCard title="Latency" readout="p95 1.2s"><div className="h-full rounded-md bg-chart-2/20" /></ChartCard></div> },
    { title: 'A ranked list beside a chart', wide: true, render: () => <div className="grid sm:grid-cols-2 gap-3 w-full"><ChartCard title="Active time"><div className="h-full rounded-md bg-brand-accent/20" /></ChartCard><ChartCard title="Features used" readout="2,524 calls" flow><BarList label="Features used" rows={[...['Indexing', 'Chat', 'Search', 'Settings', 'Files', 'Key accounts', 'Administration', 'Connections'].map((label, i): BarListRow => ({ key: label, label, value: 1284 - i * 150 })), { key: 'other', label: 'Other', value: 147, muted: true }]} /></ChartCard></div> }],
  StatTile: [{ title: 'Label over figure', wide: true, render: () => <StatGrid className="w-full"><StatTile label="Sources" value={42} /><StatTile label="Documents" value={12840} sub="+320 this week" /><StatTile label="Spend this month" value="$12.40" /><StatTile label="Errors" value={3} selected onSelect={noop} /></StatGrid> }],
  StatGrid: [{ title: 'Three across', wide: true, render: () => <StatGrid columns={3} className="w-full"><StatTile label="Seats" value={25} /><StatTile label="In use" value={18} /><StatTile label="Loading" value={0} loading /></StatGrid> }],
  FilterTiles: [{ title: 'Tiles that are the filter', wide: true, render: () => <FilterTilesDemo /> }],
  Callout: [{ title: 'Tones', wide: true, render: () => <div className="w-full space-y-3"><Callout title="Read-only">Only a super admin can change this page.</Callout><Callout tone="info" title="Note">Indexing runs in the background.</Callout><Callout tone="warning" title="Restart needed" actions={<><Button size="sm">Restart now</Button><Button variant="quiet">Later</Button></>}>The proxy change takes effect after a restart.</Callout><Callout tone="danger" title="The key was refused">Check that it has not expired.</Callout><Callout tone="success" title="Connected">The test reached the server.</Callout></div> }],
  Banner: [{ title: 'The strip above a page', wide: true, render: () => <div className="w-full space-y-2"><Banner tone="info" title="Public sandbox -" action={<button type="button" className={textLink()}>Sign in</button>} onDismiss={noop} dismissLabel="Dismiss">everything is deleted every night.</Banner><Banner tone="warning">Maintenance mode is on.</Banner><Banner tone="neutral" icon={null}>A neutral banner with no glyph.</Banner></div> }],
  EmptyState: [{ title: 'A sentence and one action', render: () => <EmptyState className="w-80" title="No skills yet" action={<Button size="sm"><Plus size={14} />Add skill</Button>}>A skill teaches the agent a procedure. Add one and it can be loaded with /.</EmptyState> }],
  LoadingState: [{ title: 'A region loading', render: () => <LoadingState label="Loading preview" /> }],
  ErrorState: [{ title: 'Failed to load', render: () => <ErrorState className="w-80" title="Could not load the preview" action={<Button variant="hyperlink">Try again</Button>}>The file may have been moved.</ErrorState> }],
  InlineNotice: [{ title: 'Every tone', render: () => <div className="w-80 space-y-2"><InlineNotice>A neutral note.</InlineNotice><InlineNotice tone="info">An informational note.</InlineNotice><InlineNotice tone="progress">Uploading 3 files</InlineNotice><InlineNotice tone="done">Condensed.</InlineNotice><InlineNotice tone="success" icon={Database}>Connection works.</InlineNotice><InlineNotice tone="warning">The token expires in 3 days.</InlineNotice><InlineNotice tone="danger" icon={ShieldAlert} size="md">401 - the token was refused.</InlineNotice></div> }],
  LoadMoreRow: [{ title: 'Load more, and loading', render: () => <div className="w-72"><LoadMoreRow label="Load more" loadingLabel="Loading more..." onLoadMore={noop} /><LoadMoreRow loading label="Load more" loadingLabel="Loading more..." /></div> }],
  Pager: [{ title: 'Numbered pages', wide: true, render: () => <PagerDemo /> }],
  ListPager: [{ title: 'Shown past one page only', wide: true, render: () => <PagerDemo /> }],
  CodeBlock: [{ title: 'A command, JSON, a transcript', wide: true, render: () => <div className="w-full space-y-3"><CodeBlock text="docker compose pull && docker compose up -d" copyLabel="Copy command" copiedLabel="Copied" /><CodeBlock wrap maxHeight="max-h-32" text={JSON.stringify({ query: 'release notes', limit: 8, sources: ['jira', 'confluence'] }, null, 2)} /><CodeBlock wrap font="ui" text={'User: Which drives run 4.2?\nAssistant: Three customers do.'} /></div> }],
  HScroll: [{ title: 'A command wider than its box', render: () => <HScroll className="w-64 rounded-md border border-line/45 bg-canvas"><p className="px-3 py-2 font-mono text-xs text-ink-primary whitespace-nowrap">docker run -d --name proxyma -p 4246:4246 -v proxyma-data:/data ghcr.io/proxyma-ai/proxyma-server:latest</p></HScroll> }],
  Frame: [{ title: 'A hole in the surface', render: () => <Frame className="w-72"><Caption>Content framed like a field.</Caption></Frame> }],
  KeyValueList: [
    { title: 'Stacked', render: () => <KeyValueList className="w-80"><KeyValue label="Plan">Enterprise</KeyValue><KeyValue label="Seats" figures>25</KeyValue><KeyValue label="Renews" figures>Mar 1, 2027</KeyValue><KeyValue label="Owner"><EmptyValue /></KeyValue></KeyValueList> },
    { title: 'Rows, with a group', render: () => <Frame className="w-80"><KeyValueList layout="rows"><KeyValue label="query">release notes for 4.2</KeyValue><KeyValue label="limit" figures>8</KeyValue><KeyValueGroup label="filter"><KeyValueList layout="rows"><KeyValue label="source">Jira</KeyValue><KeyValue label="body" block>{'line one\nline two'}</KeyValue></KeyValueList></KeyValueGroup></KeyValueList></Frame> },
  ],
  KeyValue: [{ title: 'One fact', render: () => <KeyValueList><KeyValue label="Version" figures>0.35.0</KeyValue></KeyValueList> }],
  KeyValueGroup: [{ title: 'A nested group', render: () => <KeyValueList layout="rows" className="w-72"><KeyValueGroup label="headers"><KeyValueList layout="rows"><KeyValue label="X-Title">Proxyma</KeyValue></KeyValueList></KeyValueGroup></KeyValueList> }],
  EmptyValue: [{ title: 'A value that is not there', render: () => <span className="text-sm">Owner: <EmptyValue /></span> }],
  ProseTable: [{ title: 'A table in an answer', wide: true, render: () => <ProseTable headers={['Product', 'Firmware', 'Units']} rows={[['DX-400', '4.2.1', '1,204'], ['DX-600', '4.2.0', '380']]} nowrap={(_, c) => c < 2} /> }],
  SheetTable: [{ title: 'A spreadsheet preview', render: () => <div className="max-h-28 overflow-auto w-80"><SheetTable rows={[['Name', 'Region', 'Units'], ['Northwind Mills', 'EU', '120'], ['Arcadia Water', 'EU', '44']]} /></div> }],
  AudioPlayer: [{ title: 'A recording', render: () => <AudioPlayer src="" title="Standup recording, Oct 3" labels={{ play: 'Play', pause: 'Pause' }} /> }],
  Lightbox: [{ title: 'One image, full window', render: () => <LightboxDemo /> }],
  RuleChecklist: [{ title: 'Rules met while typing', render: () => <RuleChecklist labels={{ met: 'met', unmet: 'not met yet' }} items={[{ key: 'len', label: 'At least 12 characters', pass: true }, { key: 'digit', label: 'A digit', pass: true }, { key: 'sym', label: 'A symbol', pass: false }]} /> }],
  Toast: [{ title: 'Tones', render: () => <div className="w-96 space-y-2"><Toast tone="success" onDismiss={noop} dismissLabel="Dismiss" actions={<button type="button" className={textLink()}>Open</button>}>Source added.</Toast><Toast tone="info" onDismiss={noop} dismissLabel="Dismiss">Indexing started.</Toast><Toast tone="warning" onDismiss={noop} dismissLabel="Dismiss">The model is slow to answer.</Toast><Toast tone="danger" onDismiss={noop} dismissLabel="Dismiss">The upload failed.</Toast></div> }],

  // Forms, lists, tables
  Field: [{ title: 'Stacked, inline, with a static value', wide: true, render: () => <div className="w-full space-y-4"><Field label="Instructions" description="What the agent reads first." aside="0 / 2000" hint="Plain text.">{(id, aria) => <Textarea id={id} {...aria} />}</Field><Field label="Back up every day at" layout="inline" hint="Local time on the server.">{id => <Input id={id} defaultValue="02:00" className="w-28" />}</Field><Field label="Service"><StaticValue icon={<BrandMark id="jira" size={14} />}>Jira</StaticValue></Field><Field label="Token" error="The token was refused.">{(id, aria) => <Input id={id} {...aria} defaultValue="pxm_..." />}</Field></div> }],
  FormColumns: [{ title: 'Two columns, 3:2', wide: true, render: () => <FormColumns ratio="3:2" className="w-full"><Input label="Host" defaultValue="jira.example.com" /><Input label="Port" defaultValue="443" /></FormColumns> }],
  FormStack: [{ title: 'Fields in a column', render: () => <FormStack className="w-72"><Input label="Name" /><Input label="Email" /></FormStack> }],
  StaticValue: [{ title: 'A value that cannot change here', render: () => <StaticValue icon={<BrandMark id="confluence" size={14} />}>Confluence</StaticValue> }],
  List: [{ title: 'Rows in a list', wide: true, render: () => (
    <List className="w-full">
      <ListRow title="Company Jira" meta={<Badge label="Default" variant="outline" size="sm" />} caption="https://jira.example.com - token set" leading={<BrandMark id="jira" />}
        action={<RowActions destructive={<Button variant="danger-quiet">Delete</Button>}><Button variant="secondary">Test</Button><Button variant="secondary">Edit</Button></RowActions>} />
      <ListRow title="Release plan" caption="Opens a dialog" chevron onClick={noop} />
      <ListRow title="A very long first message of a conversation, cut at one line however long it runs on" titleClamp={1} caption="2 hours ago" onClick={noop} inset />
    </List>
  ) }],
  ListRow: [{ title: 'Opens, and has its own action', wide: true, render: () => <List className="w-full"><ListRow title="Key account review" caption="Opens, and has its own action" onClick={noop} action={<IconButton icon={Trash2} label="Delete" tone="danger" size="sm" />} /><ListRow title="Selected" caption="The chosen row" onClick={noop} selected /><ListRow title="Disabled" disabled onClick={noop} /></List> }],
  SwitchRow: [{ title: 'Row and inline sizes', wide: true, render: () => <List className="w-full"><SwitchRow title="Search the web" caption="Lets the agent search and read public pages." checked onChange={noop} /><div className="py-3"><SwitchRow size="sm" title="Show shared sources from other users" checked={false} onChange={noop} /></div></List> }],
  RowActions: [{ title: 'Actions, destructive last', render: () => <RowActions destructive={<Button variant="danger-quiet">Delete</Button>}><Button variant="secondary">Test</Button><Button variant="secondary">Edit</Button></RowActions> }],
  HoverActions: [{ title: 'Shown under the pointer', render: () => <div className="group flex items-center gap-3 w-72 rounded-md border border-line/40 px-3 py-2"><span className="text-sm flex-1">Point at this row</span><HoverActions><IconButton icon={Trash2} label="Delete" size="sm" tone="danger" /></HoverActions></div> }],
  InlineConfirm: [{ title: 'Confirm in the row', render: () => <InlineConfirm cancelLabel="Cancel" confirmLabel="Delete" onCancel={noop} onConfirm={noop} /> }],
  LinkRow: [{ title: 'A document out', render: () => <LinkRow icon={FileText} title="Server manual" description="Install, upgrade, back up." href="https://proxyma.ai/docs/server/" /> }],
  BulkActionBar: [{ title: 'While rows are selected', wide: true, render: () => <BulkActionBar className="w-full" countText="3 selected"><Button variant="quiet">Clear</Button><Button variant="danger-quiet">Delete</Button></BulkActionBar> }],
  FilterChips: [{ title: 'One filter at a time', wide: true, render: () => <FilterDemo /> }],
  FilterBar: [{ title: 'Filters, search, toggles', wide: true, render: () => <FilterDemo /> }],
  Toolbar: [{ title: 'Controls over a page', wide: true, render: () => <Toolbar className="w-full" label="Report options" start={<><SegmentedControl ariaLabel="Period" value="7d" onChange={noop} options={[{ value: '24h', label: '24h' }, { value: '7d', label: '7d' }, { value: '30d', label: '30d' }]} /></>} end={<><Button variant="secondary">Export</Button><IconButton icon={RefreshCw} label="Refresh" /></>} /> }],
  PickBox: [{ title: 'A bordered pick list', render: () => <PickBox className="w-80"><List>{['searchIndexedSources', 'delegate', 'askUser', 'readSkill'].map(n => <ListRow key={n} leading={<Checkbox checked onChange={noop} ariaLabel={n} />} title={n} />)}</List></PickBox> }],
  SelectableListHeader: [{ title: 'Select all', render: () => <SelectableHeaderDemo /> }],
  ListEditor: [{ title: 'Rows of fields', render: () => <ListEditorDemo /> }],
  StepList: [{ title: 'Numbered instructions', render: () => <StepList intro="Copilot Chat in VS Code, in agent mode."><StepItem text="Add this to .vscode/mcp.json:"><CodeBlock text='{ "servers": {} }' /></StepItem><StepItem text="Reload the window." /></StepList> }],
  StepItem: [{ title: 'One step', render: () => <StepList><StepItem text="Open Settings > Assistants." /></StepList> }],
  StepCard: [{ title: 'An editable step', render: () => <div className="w-80"><StepCard index={0} count={2} head={<Input defaultValue="Collect affected products" aria-label="Step title" />} onMove={noop} onRemove={noop} labels={{ up: 'Move step 1 up', down: 'Move step 1 down', remove: 'Remove step 1' }}><Textarea defaultValue="Search the SBOMs for the component." rows={2} /></StepCard></div> }],
  TableCard: [{ title: 'A table in its card, with a footer', wide: true, render: () => <TableDemo /> }],
  Th: [{ title: 'Header cells', wide: true, render: () => <TableDemo /> }],
  SortTh: [{ title: 'Sortable headers', wide: true, render: () => <TableDemo /> }],
  IdentityCell: [{ title: 'Name over email', render: () => <IdentityCell name="Anna Weber" sub="anna@northwind.example" leading={<Avatar name="Anna Weber" size="sm" />} /> }],
  TableFooter: [{ title: 'Under a table', wide: true, render: () => <TableDemo /> }],
  TableSkeletonRows: [{ title: 'Compact density and loading rows', wide: true, render: () => (
    <TableCard className="w-full">
      <table className={TABLE_CLASS}>
        <thead><tr className={THEAD_TR}><Th compact>Time</Th><Th compact>Service</Th><Th compact className="hidden sm:table-cell">Model</Th><Th compact align="right">Cost</Th></tr></thead>
        <tbody>
          <tr className={TR}><td className={TD_COMPACT}>10:42</td><td className={TD_COMPACT} data-label="Service">Chat</td><td className={`${TD_COMPACT} hidden sm:table-cell`} data-label="Model">sonnet-5.5</td><td className={`${TD_COMPACT} text-right data-readout`} data-label="Cost">$0.012</td></tr>
          <TableSkeletonRows compact rows={2} columns={['w-10', 'w-16', { width: 'w-20', cellClassName: 'hidden sm:table-cell' }, 'w-12 ml-auto']} />
        </tbody>
      </table>
    </TableCard>
  ) }],
  TableEmptyRow: [{ title: 'An empty table', wide: true, render: () => (
    <TableCard className="w-full">
      <table className={TABLE_CLASS}><thead><tr className={THEAD_TR}><Th>Name</Th><Th>Seats</Th></tr></thead><tbody><TableEmptyRow colSpan={2} action={<Button size="sm">Issue a license</Button>}>Licenses appear here once you issue one.</TableEmptyRow></tbody></table>
    </TableCard>
  ) }],
  DropArea: [{ title: 'Click or drop', wide: true, render: () => <DropDemo /> }],
  DropOverlay: [{ title: 'Files over the window', render: () => <div className="relative h-32 w-72 rounded-lg border border-line overflow-hidden"><DropOverlay active portal={false} label="Drop files to upload" /></div> }],

  // Overlays and menus
  DialogShell: [
    { title: 'A form dialog', render: () => <DialogDemo kind="form" /> },
    { title: 'Unsaved work asks first; a capped height', render: () => <DialogDemo kind="shell" /> },
    { title: 'A bottom sheet on a phone', render: () => <DialogDemo kind="sheet" /> },
  ],
  DialogHeader: [{ title: 'Divided header (static)', wide: true, render: () => <div className={box}><DialogHeader icon={<Globe className="text-brand-legible" />} title="Add a web page" subtitle="The page and the pages it links to." actions={<ActionMenu label="More" groups={[[{ label: 'Duplicate', onSelect: noop }]]} />} /></div> }],
  DialogTitle: [{ title: '18/600', render: () => <DialogTitle>Delete this source?</DialogTitle> }],
  DialogDescription: [{ title: 'Body and caption', render: () => <div className="w-80 space-y-2"><DialogDescription>Its documents leave the index.</DialogDescription><DialogDescription tone="caption">Fields below are optional.</DialogDescription></div> }],
  DialogBody: [{ title: 'Padded body (static)', wide: true, render: () => <div className={box}><DialogBody><Caption>The dialog's padded body.</Caption></DialogBody></div> }],
  DialogFooter: [{ title: 'Actions with a status at the start', wide: true, render: () => <div className={box}><DialogFooter start={<InlineNotice tone="success">Reachable.</InlineNotice>}><Button variant="secondary">Cancel</Button><Button>Add</Button></DialogFooter></div> }],
  DialogNotice: [{ title: 'A warning body', render: () => <DialogDemo kind="notice" /> }],
  ConfirmDialog: [{ title: 'The confirmation', render: () => <DialogDemo kind="confirm" /> }],
  Modal: [{ title: 'The whole dialog, as a form', render: () => <DialogDemo kind="modal" /> }],
  Popover: [{ title: 'A menu on a trigger', render: () => (
    <Popover placement="bottom-start" trigger={({ open, toggle, ref }) => <button ref={ref} type="button" className={buttonClass('secondary')} onClick={toggle} aria-haspopup="menu" aria-expanded={open}>Popover menu</button>}>
      {close => (
        <>
          <MenuGroup label="Sources">
            <MenuRow icon={<Folder />} title="Local folder" description="Index a folder on this machine" onSelect={close} />
            <MenuRow icon={<Globe />} title="Web page" onSelect={close} />
            <MenuRow icon={<Users />} title="Microsoft 365" trailing="lock" tone="locked" onSelect={close} />
          </MenuGroup>
          <MenuGroup><MenuRow icon={<Plus />} title="Create new skill" tone="brand" onSelect={close} /></MenuGroup>
        </>
      )}
    </Popover>
  ) }],
  MenuRow: [{ title: 'Rows in a static panel', render: () => <div className="w-64 surface-float border border-line/45 rounded-lg shadow-card overflow-hidden" role="menu" aria-label="Rows"><MenuGroup><MenuRow icon={<KeyRound />} title="Ask" description="Asks before each change" trailing="check" role="menuitemradio" onSelect={noop} /><MenuRow icon={<ShieldAlert />} title="Auto" onSelect={noop} /><MenuRow title="Delete" tone="danger" onSelect={noop} /></MenuGroup></div> }],
  MenuEmpty: [{ title: 'A menu with no rows', render: () => <div className="w-64 surface-float border border-line/45 rounded-lg shadow-card overflow-hidden"><MenuEmpty>No skills yet.</MenuEmpty></div> }],
  ActionMenu: [{ title: 'The "more" menu', render: () => <ActionMenu label="More actions" groups={[[{ label: 'Sync now', icon: <RefreshCw size={15} />, onSelect: noop }, { label: 'Configure', onSelect: noop }], [{ label: 'Delete', danger: true, onSelect: noop }]]} /> }],
  MenuSurface: [{ title: 'An account menu', render: () => <MenuSurfaceDemo /> }],
  MenuGroup: [{ title: 'Groups with a caption', render: () => <div className="w-64 surface-float border border-line/45 rounded-lg shadow-card overflow-hidden"><MenuGroup label="Files"><button type="button" className={menuRow()}>Upload</button></MenuGroup><MenuGroup label="Web"><button type="button" className={menuRow()}>Web page</button></MenuGroup></div> }],
  MenuIdentity: [{ title: 'Who is signed in', render: () => <div className="w-64 surface-float border border-line/45 rounded-lg shadow-card overflow-hidden"><MenuIdentity>anna.weber@northwind.example</MenuIdentity></div> }],
  SubmenuRow: [{ title: 'A row that opens choices', render: () => <SubmenuDemo /> }],
  SubNavTabs: [{ title: 'Sub-page tabs (arrows move)', wide: true, render: () => <TabsDemo /> }],

  // Chat
  ChatMessage: [{ title: 'A turn', wide: true, render: () => <ChatDemo /> }],
  Elapsed: [{ title: 'Time after words', render: () => <span className="text-xs text-ink-caption flex gap-1.5">Searched 3 sources <Elapsed>12s</Elapsed></span> }],
  ActivityLine: [{ title: 'Running, done, waiting', wide: true, render: () => <div className="space-y-1"><ActivityLine icon={Brain} state="running" label="Thinking" elapsed="4s" /><ActivityLine icon={FileSearch} state="done" label="Searched 3 sources" elapsed="12s" open={false} onToggle={noop} /><ActivityLine icon={Brain} state="running" label="Waiting for the model" note="retry 2" noteTone="warning" elapsed="18s" /></div> }],
  ActivityFeed: [{ title: 'A scrolling box of lines', render: () => <ActivityFeed className="w-80">{['Read release-notes.md', 'Searched tickets', 'Read advisory.md', 'Searched SBOMs', 'Read sbom.json', 'Searched install base', 'Wrote the report'].map((l, i) => <ActivityLine key={i} icon={FileSearch} state="done" label={l} />)}</ActivityFeed> }],
  ToolCallRow: [{ title: 'A tool call', wide: true, render: () => <div className="space-y-1"><ToolCallRow icon={Search} state="running" label="Searching install base for DX-400" /><ToolCallRow icon={Search} state="done" label="Searched install base" elapsed="2s" onOpen={noop} /><ToolCallRow icon={FileText} state="failed" label="Read sbom-dx400.json" error="File not found in the index" onOpen={noop} /></div> }],
  ToolCallGroup: [{ title: 'Folded calls', wide: true, render: () => <ToolGroupDemo /> }],
  SubAgentPanel: [{ title: 'A sub-agent at work', wide: true, render: () => <SubAgentDemo /> }],
  PromptCard: [
    { title: 'Approve a write', wide: true, render: () => <PromptCard icon={PenLine} tone="warning" title="Creating SEC-412 in Jira" ariaLabel="Approve this change" status="Denied automatically in 0:42" actions={<><Button variant="secondary">Allow for this conversation</Button><Button>Allow once</Button><Button variant="danger-quiet" className="ml-2">Deny</Button></>}><Caption>Project: SEC - Priority: Highest</Caption></PromptCard> },
    { title: 'A question', wide: true, render: () => <ChoiceDemo /> },
  ],
  PromptSection: [{ title: 'A further question', wide: true, render: () => <ChoiceDemo /> }],
  ChoiceRow: [{ title: 'Answers to pick', wide: true, render: () => <ChoiceDemo /> }],
  NoticeCard: [{ title: 'The product doing its job', wide: true, render: () => <NoticeCard icon={ShieldAlert} title="The guardrail blocked this answer" caption="It named a person's home address." /> }],
  ComposerShell: [{ title: 'The prompt box', wide: true, render: () => (
    <ComposerShell className="w-full max-w-3xl"
      editor={<p className="text-sm text-ink-disabled">Type @ to mention a source or / to use a skill</p>}
      toolbarStart={<IconButton icon={Plus} label="Attach" />}
      toolbarEnd={<IconButton icon={Upload} label="Send" />} />
  ) }],
  ChatStartHero: [
    { title: 'The new chat opening (Community)', wide: true, render: () => (
      <ChatStartHero as="p" className="w-full" fact={<p className="text-center">Proxima Centauri, a small red dwarf about 4.25 light-years away, is the nearest star to the Sun.</p>} />
    ) },
    { title: 'With a greeting (Enterprise Server)', wide: true, render: () => (
      <ChatStartHero as="p" className="w-full" greeting="Hi, Avery" fact={<p className="text-center">Proxima Centauri, a small red dwarf about 4.25 light-years away, is the nearest star to the Sun.</p>} />
    ) },
  ],
  ChatStartLinks: [{ title: 'Under the new-chat composer', wide: true, render: () => (
    <ChatStartLinks primary={{ label: 'Connect a source', icon: Plus, render: (cls, body) => <button type="button" className={cls}>{body}</button> }}
      links={[{ key: 'a', render: cls => <button type="button" className={cls}>Try an example</button> }, { key: 'b', render: cls => <a className={cls} href="#x">Read the manual</a> }]} />
  ) }],
  CitationsFold: [{ title: 'Where an answer came from', wide: true, render: () => <ChatDemo /> }],
  CitationList: [{ title: 'The sources, listed', render: () => <CitationList><CitationRow mark={<BrandMark id="jira" />} typeLabel="Jira issue" name="SEC-412" score="91%" /></CitationList> }],
  CitationRow: [{ title: 'One source', render: () => <CitationList><CitationRow mark={<BrandMark id="web" />} typeLabel="Web page" name="Advisory: libfoo 2.1" /></CitationList> }],
  ChatMessageSkeleton: [{ title: 'A conversation loading', wide: true, render: () => <div className="w-full max-w-3xl"><ChatMessageSkeleton role="user" /><ChatMessageSkeleton role="assistant" /></div> }],

  // Sources
  SourceListHeader: [{ title: 'Column headings', wide: true, render: () => <SourcesDemo /> }],
  ListGroupHeading: [{ title: 'A group heading', render: () => <ListGroupHeading label="Confluence" count={4} /> }],
  SourceRow: [{ title: 'Sources in a list', wide: true, render: () => <SourcesDemo /> }],
  SourceRowSkeleton: [{ title: 'A source loading', wide: true, render: () => <div className="w-full"><SourceRowSkeleton /></div> }],
  ItemRow: [{ title: 'Items of a source', wide: true, render: () => (
    <List className="w-full max-w-xl">
      <ItemRow statusMark={<StatusMark state="ok" size={14} />} title="sbom-dx400-4.2.json" meta={['14 KB', '2 chunks']} />
      <ItemRow statusMark={<StatusMark state="ok" size={14} />} title="HTTP API reference | Proxyma" titleLink={cls => <ExternalLink href="https://proxyma.ai/docs/api/" className={cls} labelClassName="truncate">HTTP API reference | Proxyma</ExternalLink>} meta={['Indexed 12h ago', 'Added Oct 1, 2026']} />
      <ItemRow statusMark={<StatusMark state="failed" size={14} />} title="scan-report.pdf" meta={['2.1 MB']} error="The PDF is password protected." actions={<ActionMenu label="More" size="sm" groups={[[{ label: 'Retry', onSelect: noop }]]} />} />
      <ItemRow statusMark={<StatusMark state="deleted" size={14} />} title="old-notes.md" meta={['Deleted at the source']} struck />
    </List>
  ) }],
  ItemRowSkeleton: [{ title: 'An item loading', wide: true, render: () => <div className="w-full max-w-xl"><ItemRowSkeleton /><ItemRowSkeleton /></div> }],

  // Chrome
  AppHeader: [{ title: 'The bar on every surface', wide: true, render: () => (
    <div className={box}>
      <AppHeader brand={<a href="#x" className="flex items-center"><Logo /></a>} edge={<IconButton icon={Settings} label="Settings" size="lg" />}>
        <nav className="flex items-center gap-4 text-sm"><a href="#x" className={textLink('quiet')}>Chat</a><a href="#x" className={textLink('quiet')}>Sources</a></nav>
      </AppHeader>
    </div>
  ) }],
  AutoHideHeader: [{ title: 'Steps aside on a phone', wide: true, render: () => <div className={box}><AutoHideHeader resetKey="kit"><AppHeader brand={<Logo subtitle="Telemetry" />} /></AutoHideHeader><Caption className="p-3">Below lg, a swipe up the page pulls the bar away.</Caption></div> }],
  LanguageMenu: [{ title: 'An icon menu of languages', render: () => (
    <LanguageMenu label="Language" locales={['en', 'vi'] as const} current="en" nameOf={l => (l === 'en' ? 'English' : 'Tiếng Việt')}
      renderRow={({ locale, className, children, close }) => <button type="button" lang={locale} className={className} onClick={close}>{children}</button>} />
  ) }],
  IconMenu: [{ title: 'An icon and its choices', render: () => <IconMenu icon={<Settings size={16} aria-hidden="true" />} label="Options">{close => <><button type="button" className={menuRow()} onClick={close}>First</button><button type="button" className={menuRow()} onClick={close}>Second</button></>}</IconMenu> }],
  AppearanceMenu: [{ title: 'Four appearances', render: () => <AppearanceMenu label="Appearance" current="system" names={NAMES} onChoose={noop} /> }],
  AppearanceIcon: [{ title: 'A glyph per preference', render: () => <div className="flex gap-3 text-ink-caption">{(['system', 'light', 'black', 'gray'] as const).map(p => <AppearanceIcon key={p} preference={p} />)}</div> }],
  AppearanceMenuRow: [{ title: 'Inside an account menu', render: () => <MenuSurfaceDemo /> }],
  LanguageMenuRow: [{ title: 'Inside an account menu', render: () => <MenuSurfaceDemo /> }],
  MobileMenu: [{ title: 'The hamburger', render: () => <MobileMenu label="Menu">{close => <MenuGroup><button type="button" className={menuRow()} onClick={close}>Chat</button><button type="button" className={menuRow()} onClick={close}>Sources</button></MenuGroup>}</MobileMenu> }],
  UserMenu: [{ title: 'The account menu', render: () => <UserMenu label="Account" name="Anna Weber" withMenuIcon>{close => <><MenuIdentity>anna.weber@northwind.example</MenuIdentity><MenuGroup><button type="button" className={menuRow()} onClick={close}>Settings</button><button type="button" className={menuRow('danger')} onClick={close}>Sign out</button></MenuGroup></>}</UserMenu> }],

  // Screens and settings
  Page: [{ title: 'The page column', wide: true, render: () => <div className={box}><Page><PageHeader title="Sources" meta="42 sources" /><Caption>A page's content in the header's width, with the page rhythm.</Caption></Page></div> }],
  PageHeader: [{ title: 'Title, meta, actions, back', wide: true, render: () => <PageHeader className="w-full" back={<BackLink href="#x">Key accounts</BackLink>} title="Northwind Mills" meta="Renews Mar 1, 2027" actions={<Button variant="secondary">Share</Button>} /> }],
  BackLink: [{ title: 'The way back up', render: () => <BackLink href="#x">Back to sign in</BackLink> }],
  AuthLayout: [{ title: 'The sign-in card', wide: true, render: () => (
    <div className={`${box} h-[560px]`}>
      <AuthLayout description="First run: create the operator account." logo={<Logo />} title="Sign in" back={<BackLink href="#x">Back</BackLink>} footer={<>No account? <button type="button" className={textLink()}>Create one</button></>}>
        <FormAlert>The email or password is wrong.</FormAlert>
        <FormStack><Input label="Email" /><PasswordInput label="Password" labels={{ show: 'Show password', hide: 'Hide password' }} /><Button className="w-full" size="md">Sign in</Button></FormStack>
        <OrDivider label="or" />
        <Button variant="secondary" className="w-full" size="md">Sign in with SSO</Button>
      </AuthLayout>
    </div>
  ) }],
  FormAlert: [{ title: 'A form verdict', render: () => <div className="w-80 space-y-2"><FormAlert>The email or password is wrong.</FormAlert><FormAlert tone="success">We sent a reset link.</FormAlert><FormAlert tone="info">The link expires in an hour.</FormAlert></div> }],
  OrDivider: [{ title: 'Or', render: () => <div className="w-72"><OrDivider label="or" /></div> }],
  BootScreen: [{ title: 'Starting, and unreachable', wide: true, render: () => <div className="grid sm:grid-cols-2 gap-3 w-full"><div className={`${box} h-48`}><BootScreen logo={<Logo />} label="Starting" /></div><div className={`${box} h-48`}><BootScreen logo={<Logo />} message="The server cannot be reached. It may be restarting." action={<Button variant="secondary">Retry</Button>} /></div></div> }],
  StatusScreen: [{ title: 'Restarting', render: () => <StatusScreenDemo /> }],
  SplashScreen: [{ title: 'The desktop splash', render: () => <SplashDemo /> }],
  SettingsLayout: [{ title: 'The settings window, inside', wide: true, render: () => <SettingsLayoutDemo /> }],
  SettingsRail: [{ title: 'Grouped rail', wide: true, render: () => <SettingsLayoutDemo /> }],
  SettingsSections: [{ title: 'Ruled sections', wide: true, render: () => <SettingsLayoutDemo /> }],
  SettingsSection: [{ title: 'A section with a switch', wide: true, render: () => <SettingsLayoutDemo /> }],
  SaveBar: [{ title: 'Pending changes', wide: true, render: () => <div className={box}><SaveBar dirty saving={false} status={null} onCancel={noop} onSave={noop} labels={{ cancel: 'Cancel', save: 'Save', saving: 'Saving...' }} /></div> }],
  SettingsWindow: [{ title: 'The whole window', render: () => <SettingsWindowDemo /> }],

  // Marketing
  PageSection: [{ title: 'A home-page section', wide: true, render: () => <div className={box}><PageSection id="kit-section" size="compact"><DisplayHeading title="One index, many sources." lead="A section's heading and its lead." /></PageSection></div> }],
  DisplayHeading: [{ title: 'Hero, section, sub', wide: true, render: () => <div className="space-y-6 w-full"><DisplayHeading size="hero" title="Your knowledge, and an agent that can use it." /><DisplayHeading title="Sources" lead="Sections open with a display heading, left-aligned, with no eyebrow." /><DisplayHeading size="sub" as="h3" title="A sub-heading" /></div> }],
  SplitSection: [{ title: 'Heading beside content', wide: true, render: () => <SplitSection heading={<DisplayHeading size="sub" as="h3" title="Private by design" />} sticky={false}><Paragraph>The index lives on your machine. Nothing leaves without your provider.</Paragraph></SplitSection> }],
  FeatureRow: [{ title: 'A numbered feature', wide: true, render: () => <FeatureRow index={1} title="Ask across every source" sentence="One question searches Jira, Confluence and your files at once." media={<Card variant="outline"><Caption>A picture of it.</Caption></Card>} /> }],
  RuledColumns: [{ title: 'Ruled terms, boxed', wide: true, render: () => <RuledColumns boxed title="Why not just paste the documents into a chatbot?" items={[{ term: 'Privacy', detail: 'The index lives on your machine.' }, { term: 'Scale', detail: 'Thousands of documents at once.' }, { term: 'Freshness', detail: 'Changes arrive as they happen.' }]} /> }],
  IncludedList: [{ title: 'What is included', render: () => <IncludedList items={['Every connector', 'No limits', 'Updates']} /> }],
  PlanCard: [{ title: 'Plans', wide: true, render: () => (
    <div className="grid md:grid-cols-2 gap-4 w-full">
      <PlanCard name="Community" tagline="For you, on your own computer or server." price="Free" features={['Every connector', 'No limits']} action={<Button variant="secondary" size="md" className="w-full">Download</Button>} />
      <PlanCard featured name="Enterprise" badge={<Badge label="30-day trial" variant="brand" />} tagline="For a team on one server." price="Contact us" features={['Accounts and SSO', 'Microsoft 365', 'Key accounts']} action={<Button size="md" className="w-full">Start a trial</Button>} />
    </div>
  ) }],
  PlanBand: [{ title: 'The Enterprise band', wide: true, render: () => <PlanBand className="w-full" name="Enterprise" tagline="Run it for your whole organization" actions={<><Button size="md">Look around the demo</Button><Button size="md" variant="secondary">Contact us</Button></>}><Caption>SSO, roles, quotas and an audit trail.</Caption></PlanBand> }],
  LinkCard: [{ title: 'A card that is a link', render: () => <LinkCard href="#x" icon={FileText} title="Desktop manual" summary="Install, connect a source, ask your first question." /> }],
  LinkColumn: [{ title: 'A footer column', render: () => <LinkColumn heading="Product"><a href="#x" className={textLink('quiet')}>Features</a><a href="#x" className={textLink('quiet')}>Pricing</a></LinkColumn> }],
  Timeline: [{ title: 'Steps in time', wide: true, render: () => <Timeline className="w-full" items={[{ marker: 'Day 1', title: 'Install', body: 'One command.' }, { marker: 'Day 2', title: 'Connect', body: 'Your sources sync.' }, { marker: 'Day 30', title: 'Decide', body: 'Keep it or not.' }]} /> }],
  DownloadRow: [{ title: 'One download', wide: true, render: () => <DownloadRow className="w-full" name="Windows" audience="For one person on a PC." needs="Windows 10 or later, 8 GB of memory." action={<ButtonLink href="#x">Download</ButtonLink>} note="Starts with every connector." /> }],
  CompareTable: [{ title: 'Plans compared', wide: true, render: () => <CompareTable className="w-full" caption="Community and Enterprise compared" columns={['Community', 'Enterprise']} columnLabels={['Community', 'Enterprise']} yes="Included" no="Not included" groups={[{ heading: 'Sources', rows: [{ label: 'Every connector', values: [true, true] }, { label: 'Microsoft 365', values: [false, true] }] }, { heading: 'Team', rows: [{ label: 'Accounts and SSO', values: [false, true] }] }]} /> }],
  FaqItem: [{ title: 'A question', wide: true, render: () => <div className="w-full border-t border-line"><FaqItem id="free" question="Is the Community edition really free?" answer="Yes, with no time limit." /></div> }],
  SceneFrame: [{ title: 'A product picture', wide: true, render: () => <MarketingSceneDemo /> }],
  SceneControl: [{ title: 'Pause, play, replay', wide: true, render: () => <MarketingSceneDemo /> }],
}
