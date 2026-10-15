/**
 * THE KIT'S TOKENS AS DATA - what the gallery's token tables show and what the design guideline is
 * written from. Plain .ts with no imports (a static generator reads it with `node`, like
 * kitRegistry.ts). Names, roles and the class a page writes; the VALUES are read from
 * libs/ui-kit/tokens/proxyma-tokens.css (the gallery parses it per appearance) so they cannot
 * drift from the file that defines them.
 */

export type Appearance = 'light' | 'gray' | 'black'

/** The four appearances a person picks; System resolves to Light or Dark. */
export const APPEARANCES: { id: Appearance | 'system'; name: string; note: string }[] = [
  { id: 'system', name: 'System', note: 'Follows the operating system: Light on a light OS, Dark on a dark one.' },
  { id: 'light', name: 'Light', note: 'White canvas, neutral grays; the brand only on controls and the logo.' },
  { id: 'gray', name: 'Proxyma', note: 'The product\'s own dark: teal-tinted planes, near-white headings. The bare :root block.' },
  { id: 'black', name: 'Dark', note: 'A neutral VS Code-style dark gray; brand, status and chart tokens stay Proxyma\'s.' },
]

export interface ColorToken {
  /** The CSS variable, without `--`. */
  name: string
  /** A class that paints it (a swatch's background). */
  swatch: string
  /** The classes a page writes for it. */
  classes: string
  role: string
}

/** The brand ramp (constant hue 196.4) and the mono ramp - MODE-INVARIANT, brand fills only. */
export const RAMPS: { title: string; note: string; steps: ColorToken[] }[] = [
  {
    title: 'Brand ramp (proxima)',
    note: 'Generated from the primary #164E63 (p900). Mode-invariant: for the logo mark, never for page chrome.',
    steps: [
      { name: 'p50', swatch: 'bg-proxima-50', classes: 'proxima-50', role: 'Light mode\'s brand-pale.' },
      { name: 'p100', swatch: 'bg-proxima-100', classes: 'proxima-100', role: '' },
      { name: 'p200', swatch: 'bg-proxima-200', classes: 'proxima-200', role: '' },
      { name: 'p300', swatch: 'bg-proxima-300', classes: 'proxima-300', role: 'Dark brand-legible.' },
      { name: 'p400', swatch: 'bg-proxima-400', classes: 'proxima-400', role: 'Dark brand-accent; the mark\'s disc.' },
      { name: 'p500', swatch: 'bg-proxima-500', classes: 'proxima-500', role: 'The mark\'s spokes.' },
      { name: 'p600', swatch: 'bg-proxima-600', classes: 'proxima-600', role: 'The mark\'s gap arcs.' },
      { name: 'p700', swatch: 'bg-proxima-700', classes: 'proxima-700', role: 'Dark brand-solid; the mark\'s outer arcs.' },
      { name: 'p800', swatch: 'bg-proxima-800', classes: 'proxima-800', role: 'Light brand-legible.' },
      { name: 'p900', swatch: 'bg-proxima-900', classes: 'proxima-900', role: 'The primary. Light brand-solid.' },
      { name: 'p950', swatch: 'bg-proxima-950', classes: 'proxima-950', role: '' },
    ],
  },
  {
    title: 'Mono ramp (mono)',
    note: 'The neutral counterpart at the brand hue: every plane, line and ink of the Proxyma appearance.',
    steps: [
      { name: 'n50', swatch: 'bg-mono-50', classes: 'mono-50', role: '' },
      { name: 'n100', swatch: 'bg-mono-100', classes: 'mono-100', role: 'Proxyma ink-primary; light surface.' },
      { name: 'n200', swatch: 'bg-mono-200', classes: 'mono-200', role: 'Light surface-alt.' },
      { name: 'n300', swatch: 'bg-mono-300', classes: 'mono-300', role: 'Proxyma ink-secondary; light line.' },
      { name: 'n400', swatch: 'bg-mono-400', classes: 'mono-400', role: 'Proxyma ink-caption.' },
      { name: 'n500', swatch: 'bg-mono-500', classes: 'mono-500', role: 'Proxyma ink-disabled.' },
      { name: 'n600', swatch: 'bg-mono-600', classes: 'mono-600', role: '' },
      { name: 'n700', swatch: 'bg-mono-700', classes: 'mono-700', role: '' },
      { name: 'n800', swatch: 'bg-mono-800', classes: 'mono-800', role: 'Proxyma surface-alt and line.' },
      { name: 'n900', swatch: 'bg-mono-900', classes: 'mono-900', role: 'Proxyma surface.' },
      { name: 'n950', swatch: 'bg-mono-950', classes: 'mono-950', role: 'Proxyma canvas.' },
    ],
  },
]

/** The semantic tokens - what pages use. They re-map per appearance. */
export const SEMANTIC: { title: string; tokens: ColorToken[] }[] = [
  {
    title: 'Planes and lines',
    tokens: [
      { name: 'canvas', swatch: 'bg-canvas', classes: 'bg-canvas', role: 'The page; every field (a field is a hole in the surface); every floating panel.' },
      { name: 'surface', swatch: 'bg-surface', classes: 'bg-surface, bg-surface/40', role: 'A card or row plane on the canvas (cards use it at 40%).' },
      { name: 'surface-alt', swatch: 'bg-surface-alt', classes: 'bg-surface-alt', role: 'A raised plane - a neutral badge.' },
      { name: 'line', swatch: 'bg-line', classes: 'border-line, border-line/40', role: 'Hairlines and borders, usually at an opacity.' },
    ],
  },
  {
    title: 'Ink',
    tokens: [
      { name: 'ink-primary', swatch: 'bg-ink-primary', classes: 'text-ink-primary', role: 'Headings, names, values.' },
      { name: 'ink-secondary', swatch: 'bg-ink-secondary', classes: 'text-ink-secondary', role: 'Body text (the default).' },
      { name: 'ink-caption', swatch: 'bg-ink-caption', classes: 'text-ink-caption', role: 'Captions, labels of facts, icon buttons at rest.' },
      { name: 'ink-disabled', swatch: 'bg-ink-disabled', classes: 'text-ink-disabled', role: 'Placeholders, disabled controls, the dot between facts.' },
    ],
  },
  {
    title: 'Brand',
    tokens: [
      { name: 'brand-solid', swatch: 'bg-brand-solid', classes: 'bg-brand-solid', role: 'A FILL: the primary button, a switch that is on, the chosen day.' },
      { name: 'brand-on-solid', swatch: 'bg-brand-on-solid', classes: 'text-brand-on-solid', role: 'Text on a brand-solid fill.' },
      { name: 'brand-legible', swatch: 'bg-brand-legible', classes: 'text-brand-legible', role: 'Brand TEXT: links, the wordmark, work done.' },
      { name: 'brand-accent', swatch: 'bg-brand-accent', classes: 'bg-brand-accent', role: 'DECORATIVE only: meter fills, dots, a reveal. Never behind a label.' },
      { name: 'brand-accent-legible', swatch: 'bg-brand-accent-legible', classes: 'text-brand-accent-legible', role: 'A brand glyph beside text.' },
      { name: 'brand-pale', swatch: 'bg-brand-pale', classes: 'bg-brand-pale, bg-brand-chip-bg', role: 'The pale chip fill, paired with brand-legible text.' },
    ],
  },
  {
    title: 'Status',
    tokens: [
      { name: 'success', swatch: 'bg-success', classes: 'text-success', role: 'A verdict that passed (a test, a rule met). TEXT.' },
      { name: 'warning', swatch: 'bg-warning', classes: 'text-warning', role: 'Attention, not failure. TEXT.' },
      { name: 'danger', swatch: 'bg-danger', classes: 'text-danger', role: 'Failure, and actions that remove. TEXT.' },
      { name: 'success-solid', swatch: 'bg-success-solid', classes: 'bg-success-solid text-on-status', role: 'A status badge\'s fill. Mode-invariant.' },
      { name: 'warning-solid', swatch: 'bg-warning-solid', classes: 'bg-warning-solid text-on-status', role: 'A status badge\'s fill. Mode-invariant.' },
      { name: 'danger-solid', swatch: 'bg-danger-solid', classes: 'bg-danger-solid text-on-status', role: 'The destructive button and a status badge. Mode-invariant.' },
      { name: 'on-status', swatch: 'bg-on-status', classes: 'text-on-status', role: 'Text on a solid status fill.' },
      { name: 'thumb', swatch: 'bg-thumb', classes: 'bg-thumb', role: 'A switch\'s knob.' },
    ],
  },
  {
    title: 'Charts',
    tokens: [
      { name: 'chart-series-1', swatch: 'bg-chart-1', classes: 'bg-chart-1, CHART_SERIES[0]', role: 'Blue - first category.' },
      { name: 'chart-series-2', swatch: 'bg-chart-2', classes: 'bg-chart-2, CHART_SERIES[1]', role: 'Green.' },
      { name: 'chart-series-3', swatch: 'bg-chart-3', classes: 'bg-chart-3, CHART_SERIES[2]', role: 'Magenta.' },
      { name: 'chart-series-4', swatch: 'bg-chart-4', classes: 'bg-chart-4, CHART_SERIES[3]', role: 'Yellow. The order is CVD-validated - do not reorder.' },
      { name: 'chart-series-other', swatch: 'bg-chart-other', classes: 'bg-chart-other', role: 'The grouped remainder.' },
    ],
  },
]

/** The type roles (typeScale.ts `TEXT`), largest first, with the component that draws each. */
export const TYPE_ROLES: { role: string; spec: string; component: string; use: string }[] = [
  { role: 'title', spec: '18/28, 600, ink-primary', component: 'Title, PageTitle, DialogTitle', use: 'A page\'s, a Settings tab\'s or a dialog\'s own name.' },
  { role: 'section', spec: '16/24, 600, ink-primary', component: 'SectionHeading', use: 'A sub-section of a page, tab or dialog.' },
  { role: 'lead', spec: '17, 400, relaxed, ink-secondary', component: 'Paragraph size="lead"', use: 'The paragraph that opens a page or a site section.' },
  { role: 'body', spec: '15, 400, relaxed, ink-secondary', component: 'Paragraph, DialogDescription, PROSE_CHAT', use: 'Running text in a dialog or a card; a chat message; a manual.' },
  { role: 'label', spec: '14/20, 500, ink-primary', component: 'FieldLabel', use: 'The name of a field, a switch row, a list item.' },
  { role: 'option', spec: '14/20, 400, ink-secondary', component: 'RadioGroup, Checkbox', use: 'A choice; the quieter line of a row.' },
  { role: 'caption', spec: '13, 400, relaxed, ink-caption', component: 'Caption', use: 'Everything that explains.' },
  { role: 'eyebrow', spec: '13/18, 500, ink-caption, sentence case', component: 'Eyebrow', use: 'The label inside a card naming what it shows.' },
]

/** The faces. */
export const FACES: { name: string; token: string; family: string; use: string; sample: string; className: string }[] = [
  { name: 'UI', token: '--font-ui', family: 'Space Grotesk 400-700', use: 'Everything.', sample: 'The quick brown fox jumps over the lazy dog', className: 'font-ui' },
  { name: 'Brand', token: '--font-brand', family: 'Orbitron 700 (900 on the splash)', use: 'The wordmark and display numerals only.', sample: 'Proxyma', className: 'font-brand font-bold tracking-wider' },
  { name: 'Readout', token: '--font-readout', family: 'Proxyma Figures (Consolas digits) + the UI face', use: 'Anything carrying figures - counts, costs, dates, ids (.data-readout).', sample: 'input 12.3k - 10/5/2026, 7:25 AM - $0.012', className: 'data-readout' },
  { name: 'Code', token: '--font-mono', family: 'Consolas, Cascadia Code, Space Mono', use: 'Code only - a block, a command, a key.', sample: 'docker compose up -d', className: 'font-mono' },
]

/** The size steps of the preset (fontSize), for anything not a TEXT role (the site's display type). */
export const FONT_SIZES: { cls: string; spec: string }[] = [
  { cls: 'text-2xs', spec: '12/16' }, { cls: 'text-xs', spec: '13/18' }, { cls: 'text-sm', spec: '14/22' },
  { cls: 'text-base', spec: '16/26' }, { cls: 'text-lg', spec: '18/28' }, { cls: 'text-xl', spec: '21/30' },
  { cls: 'text-2xl', spec: '25/34' }, { cls: 'text-3xl', spec: '29/38' }, { cls: 'text-4xl', spec: '37/44' }, { cls: 'text-5xl', spec: '49/56' },
]

/** Spacing: the steps the spacing table names (Tailwind's 4px scale), plus the touch targets. */
export const SPACING: { cls: string; px: number; use: string }[] = [
  { cls: '1', px: 4, use: 'Glyph to word in a chip.' },
  { cls: '1.5', px: 6, use: 'A field\'s line to its note (mt-1.5).' },
  { cls: '2', px: 8, use: 'A label to its field (mb-2); an inline action cluster.' },
  { cls: '3', px: 12, use: 'A form\'s action row; a stat grid; the phone gutter.' },
  { cls: '4', px: 16, use: 'Fields in a form (space-y-4); a card\'s padding on a phone.' },
  { cls: '5', px: 20, use: 'A card\'s padding from sm; a dialog body\'s vertical padding.' },
  { cls: '6', px: 24, use: 'Between page blocks (space-y-6); the gutter from sm; a dialog\'s side padding.' },
  { cls: '8', px: 32, use: 'Between a page\'s sections on a site.' },
  { cls: 'tap-sm', px: 36, use: 'A control under a mouse.' },
  { cls: 'tap', px: 44, use: 'A control on glass (touch:).' },
]

export const RADII: { cls: string; px: string; use: string }[] = [
  { cls: 'rounded-sm', px: '4px', use: 'A skeleton bar; a checkbox square (rounded).' },
  { cls: 'rounded-md', px: '6px', use: 'THE control radius: buttons, fields, badges, chips, code.' },
  { cls: 'rounded-lg', px: '8px', use: 'THE surface radius: cards, panels, dialogs, menus.' },
  { cls: 'rounded-xl', px: '10px', use: 'The chat composer.' },
  { cls: 'rounded-3xl', px: '14px', use: 'The desktop splash card.' },
  { cls: 'rounded-full', px: '9999px', use: 'Only what is round by nature: dots, switches, avatars, the play button.' },
]

export const SHADOWS: { cls: string; use: string }[] = [
  { cls: 'shadow-sm', use: 'A tooltip.' },
  { cls: 'shadow-card', use: 'Every floating panel: menus, popovers, dialogs, toasts.' },
  { cls: 'shadow-card-lg', use: 'The lightbox.' },
]

export const MOTION: { token: string; cls: string; value: string; use: string }[] = [
  { token: '--motion-fast', cls: 'duration-fast', value: '120ms', use: 'Colors answering the pointer, a menu opening, anything leaving.' },
  { token: '--motion-base', cls: 'duration-base', value: '200ms', use: 'A dialog or a page\'s content arriving; a disclosure\'s height.' },
  { token: '--motion-slow', cls: 'duration-slow', value: '300ms', use: 'A quantity moving (a meter); the appearance crossfade.' },
  { token: '--ease-out', cls: 'ease-out', value: 'cubic-bezier(0.2, 0, 0, 1)', use: 'Arriving.' },
  { token: '--ease-in', cls: 'ease-in', value: 'cubic-bezier(0.4, 0, 1, 1)', use: 'Leaving.' },
  { token: '--ease-in-out', cls: 'ease-in-out', value: 'cubic-bezier(0.4, 0, 0.2, 1)', use: 'Changing in place.' },
]

/** The named animations a page can use. */
export const ANIMATIONS: { cls: string; use: string }[] = [
  { cls: 'animate-fade-in', use: 'Content replacing a skeleton; a notice; a table row.' },
  { cls: 'animate-fade-up', use: 'A page arriving.' },
  { cls: 'animate-slide-up', use: 'A dialog panel arriving.' },
  { cls: 'animate-dropdown', use: 'A menu or popover opening (pop-out when it closes).' },
  { cls: 'animate-skeleton', use: 'A loading placeholder (Skeleton).' },
  { cls: 'animate-pulse', use: 'An ICON at work - 1Hz blink (StateMark, StatusDot).' },
  { cls: 'text-wave', use: 'WORDS at work - a band sweeping across them (working.ts).' },
  { cls: 'animate-shake', use: 'A field that failed a submit (useShake).' },
  { cls: 'animate-reveal', use: 'A place the reader was just sent to.' },
  { cls: 'animate-mark-idle', use: 'The mark\'s idle turn where nothing measures it.' },
]

export const LAYERS: { cls: string; value: number; use: string }[] = [
  { cls: 'z-raised', value: 10, use: 'Lifted over its siblings in the flow.' },
  { cls: 'z-sticky', value: 20, use: 'A sticky row inside a scroller.' },
  { cls: 'z-header', value: 50, use: 'The page header.' },
  { cls: 'z-dropzone', value: 9000, use: 'The full-window drop layer.' },
  { cls: 'z-dialog', value: 9999, use: 'Every dialog, the Settings window, the lightbox.' },
  { cls: 'z-toast', value: 10010, use: 'The toast stack.' },
  { cls: 'z-float', value: 10030, use: 'Menus, popovers, Select and Combobox lists, the date picker, submenus.' },
  { cls: 'z-tooltip', value: 10035, use: 'A tooltip.' },
  { cls: 'z-alert', value: 10040, use: '"Discard unsaved changes?" over its dialog.' },
  { cls: 'z-tour', value: 10050, use: 'The onboarding tour.' },
  { cls: 'z-status', value: 99999, use: 'Restart, maintenance and quit screens.' },
]

export const BREAKPOINTS: { cls: string; query: string; use: string }[] = [
  { cls: 'sm', query: 'min-width: 640px', use: 'Above a phone.' },
  { cls: 'md', query: 'min-width: 768px', use: 'A tablet upright - and a phone on its side.' },
  { cls: 'lg', query: 'min-width: 1024px', use: 'The hamburger leaves the header.' },
  { cls: 'xl', query: 'min-width: 1280px', use: '' },
  { cls: '2xl', query: 'min-width: 1536px', use: 'The page column widens.' },
  { cls: 'short', query: 'max-height: 500px', use: 'A phone on its side, or a window dragged short: collapse vertical padding; never show two sticky bars.' },
  { cls: 'tall', query: 'min-height: 700px', use: 'Height worth spending on breathing room.' },
  { cls: 'touch', query: 'pointer: coarse', use: '44px targets, and no affordance that only appears on hover.' },
  { cls: 'mouse', query: 'pointer: fine and hover: hover', use: 'Hover states, 36px targets.' },
  { cls: 'stylus', query: 'pointer: fine and hover: none', use: 'A tablet with a pen: sizes like touch, hovers like nothing.' },
]

/** The layout constants (layout.ts and friends). */
export const LAYOUT: { name: string; value: string; use: string }[] = [
  { name: 'PAGE_COLUMN', value: 'max-w-5xl 2xl:max-w-7xl mx-auto', use: 'The header\'s width - every page.' },
  { name: 'PAGE_GUTTER', value: 'px-3 sm:px-6', use: '12px on a phone, 24px from sm.' },
  { name: 'PROSE_MEASURE', value: 'lg:max-w-[calc(66.6667%-1rem)]', use: 'Marketing prose.' },
  { name: 'THREAD_COLUMN', value: 'max-w-3xl 2xl:max-w-4xl mx-auto w-full', use: 'The chat thread.' },
  { name: 'LIST_PAGE_SIZE', value: '20', use: 'Every list of records pages at twenty (ListPager).' },
]
