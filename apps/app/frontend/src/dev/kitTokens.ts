/**
 * THE KIT'S TOKENS AS DATA - what the gallery's token tables show and what the design guideline is
 * written from. Plain .ts with no imports (a static generator reads it with `node`, like
 * kitRegistry.ts). Names, roles and the class a page writes; the VALUES are read from
 * libs/ui-kit/tokens/proxyma-tokens.css (the gallery parses it per appearance) so they cannot
 * drift from the file that defines them.
 */

export type Appearance = 'light' | 'dark'

/** The three appearances a person picks; System resolves to Light or Dark (since 2026-10-28). */
export const APPEARANCES: { id: Appearance | 'system'; name: string; note: string }[] = [
  { id: 'system', name: 'System', note: 'Follows the operating system: Light on a light OS, Dark on a dark one.' },
  { id: 'light', name: 'Light', note: 'White canvas, neutral greys; the brand only where it acts. The bare :root block - what paints before any script.' },
  { id: 'dark', name: 'Dark', note: 'Neutral dark greys, no teal or blue tint; :root[data-mode="dark"]. A stored gray or black (older builds) reads as Dark.' },
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

/** The brand ramp (constant hue 196.4) and the neutral mono ramp - MODE-INVARIANT, not page chrome. */
export const RAMPS: { title: string; note: string; steps: ColorToken[] }[] = [
  {
    title: 'Brand ramp (proxima)',
    note: 'Generated from the primary #164E63 (p900). Mode-invariant: for the logo mark, never for page chrome.',
    steps: [
      { name: 'p50', swatch: 'bg-proxima-50', classes: 'proxima-50', role: '' },
      { name: 'p100', swatch: 'bg-proxima-100', classes: 'proxima-100', role: '' },
      { name: 'p200', swatch: 'bg-proxima-200', classes: 'proxima-200', role: '' },
      { name: 'p300', swatch: 'bg-proxima-300', classes: 'proxima-300', role: 'Dark brand-legible.' },
      { name: 'p400', swatch: 'bg-proxima-400', classes: 'proxima-400', role: 'Dark brand-accent and focus line; the mark\'s disc.' },
      { name: 'p500', swatch: 'bg-proxima-500', classes: 'proxima-500', role: 'The mark\'s spokes.' },
      { name: 'p600', swatch: 'bg-proxima-600', classes: 'proxima-600', role: 'The mark\'s gap arcs.' },
      { name: 'p700', swatch: 'bg-proxima-700', classes: 'proxima-700', role: 'Dark brand-solid (its ONLY fill step); Light accent and focus line; the mark\'s outer arcs.' },
      { name: 'p800', swatch: 'bg-proxima-800', classes: 'proxima-800', role: 'Light brand-legible.' },
      { name: 'p900', swatch: 'bg-proxima-900', classes: 'proxima-900', role: 'The primary. Light brand-solid (its ONLY fill step).' },
      { name: 'p950', swatch: 'bg-proxima-950', classes: 'proxima-950', role: '' },
    ],
  },
  {
    title: 'Mono ramp (mono)',
    note: 'NEUTRAL since 2026-10-28 (a zinc-like grey; it was the brand hue at low saturation). The names stayed; pages use the semantic tokens.',
    steps: [
      { name: 'n50', swatch: 'bg-mono-50', classes: 'mono-50', role: '' },
      { name: 'n100', swatch: 'bg-mono-100', classes: 'mono-100', role: '' },
      { name: 'n200', swatch: 'bg-mono-200', classes: 'mono-200', role: '' },
      { name: 'n300', swatch: 'bg-mono-300', classes: 'mono-300', role: '' },
      { name: 'n400', swatch: 'bg-mono-400', classes: 'mono-400', role: '' },
      { name: 'n500', swatch: 'bg-mono-500', classes: 'mono-500', role: '' },
      { name: 'n600', swatch: 'bg-mono-600', classes: 'mono-600', role: '' },
      { name: 'n700', swatch: 'bg-mono-700', classes: 'mono-700', role: '' },
      { name: 'n800', swatch: 'bg-mono-800', classes: 'mono-800', role: '' },
      { name: 'n900', swatch: 'bg-mono-900', classes: 'mono-900', role: '' },
      { name: 'n950', swatch: 'bg-mono-950', classes: 'mono-950', role: '' },
    ],
  },
]

/** The semantic tokens - what pages use. They re-map per appearance. */
export const SEMANTIC: { title: string; tokens: ColorToken[] }[] = [
  {
    title: 'Planes and lines',
    tokens: [
      { name: 'canvas', swatch: 'bg-canvas', classes: 'bg-canvas', role: 'The page, behind everything: a cool gray in Light, near black in Dark. In an app shell (the glass) the solid color the glass sheet composites to - for what must hide what is under it, like the pinned question\'s fade.' },
      { name: 'surface', swatch: 'bg-surface', classes: 'bg-surface shadow-card', role: 'The card plane: white in Light, a step up in Dark. In an app shell a card is GLASS (.surface-card) and this is its solid equivalent - a sticky row or bar inside a card.' },
      { name: 'surface-alt', swatch: 'bg-surface-alt', classes: 'bg-surface-alt shadow-well', role: 'A SUNKEN well: a rail, a segmented control\'s track, a code block; a hover or SELECTED row fill (with a teal indicator or ink), a neutral badge.' },
      { name: 'surface-float', swatch: 'bg-surface-float', classes: '.surface-float shadow-float', role: 'What floats: menus, popovers, dialogs, toasts, tooltips; a pill lifted out of a well. White in Light, the lightest plane in Dark.' },
      { name: 'field', swatch: 'bg-field', classes: 'bg-field (FIELD_BASE)', role: 'A text control\'s fill: white in Light, so a field on a white card is drawn by its line; a well in Dark.' },
      { name: 'line', swatch: 'bg-line', classes: 'border-line, border-line/40', role: 'Hairlines and borders - a card\'s at full strength, rules usually at an opacity. Decorative - not held to 3:1.' },
    ],
  },
  {
    title: 'Glass (an app shell)',
    tokens: [
      { name: 'field-base', swatch: 'app-field', classes: '.app-field (the shell\'s body; ProxymaBackground)', role: 'THE FIELD behind the glass: one flat cool color - airy in Light, #101011 in Dark. The only light is the sheet light, in its bottom-right corner. No text sits on it.' },
      { name: 'glass', swatch: 'app-glass', classes: '.app-glass', role: 'The FRAME from `lg` holding the sidebar: --glass at --glass-alpha (0.64 Light, 0.6 Dark), a 24px blur, the --glass-edge and --shadow-frame.' },
      { name: 'sheet', swatch: 'app-sheet', classes: '.app-sheet', role: 'The PAGE: --sheet at --sheet-alpha (white 0.85 Light, near black 0.85 Dark), a 16px blur, and --sheet-accent light in its bottom-right corner (6.5% / 5%).' },
      { name: 'card', swatch: 'surface-card', classes: '.surface-card shadow-card (Card, cardClass)', role: 'A card on the sheet: --card at --card-alpha (white 0.62 Light, white 0.025 Dark), the --card-edge and the card shadow\'s top highlight. NO blur. In a card it is a well (--well).' },
      { name: 'float', swatch: 'surface-float', classes: '.surface-float border rounded-lg shadow-float', role: 'What floats - menus, popovers, lists, tooltips, toasts, dialogs, the composer: --float at 0.86, a 20px blur, the --float-edge. Never give it a border color; the class decides.' },
      { name: 'bar', swatch: 'header-bar', classes: '.header-bar (AppHeader)', role: 'The header below `lg`, both consoles\' header: --bar at 0.72, a 16px blur.' },
      { name: 'scrim', swatch: 'modal-scrim', classes: '.modal-scrim (MODAL_SCRIM)', role: 'Behind a dialog: a cool shade at 0.32 in Light, black at 0.5 in Dark, a 6px blur.' },
    ],
  },
  {
    title: 'Ink',
    tokens: [
      { name: 'ink-primary', swatch: 'bg-ink-primary', classes: 'text-ink-primary', role: 'Headings, names, values.' },
      { name: 'ink-secondary', swatch: 'bg-ink-secondary', classes: 'text-ink-secondary', role: 'Body text (the default).' },
      { name: 'ink-caption', swatch: 'bg-ink-caption', classes: 'text-ink-caption', role: 'Captions, labels of facts, icon buttons at rest. 4.5:1 on all four planes.' },
      { name: 'ink-disabled', swatch: 'bg-ink-disabled', classes: 'text-ink-disabled', role: 'UI only, 3:1: placeholders, disabled controls, a switch\'s off track, the dot between facts. Never text that must be read.' },
    ],
  },
  {
    title: 'Brand',
    tokens: [
      { name: 'brand-solid', swatch: 'bg-brand-solid', classes: 'bg-brand-solid', role: 'A FILL, ONE ramp step per mode (p900 Light, p700 Dark): the primary button, a switch that is on, the chosen day. Never a background.' },
      { name: 'brand-on-solid', swatch: 'bg-brand-on-solid', classes: 'text-brand-on-solid', role: 'Text on a brand-solid fill.' },
      { name: 'brand-legible', swatch: 'bg-brand-legible', classes: 'text-brand-legible', role: 'Brand TEXT: links, the wordmark, work done.' },
      { name: 'brand-accent', swatch: 'bg-brand-accent', classes: 'bg-brand-accent', role: 'DECORATIVE only: meter fills, dots, a reveal. Never behind a label.' },
      { name: 'brand-accent-legible', swatch: 'bg-brand-accent-legible', classes: 'text-brand-accent-legible', role: 'A brand glyph beside text.' },
      { name: 'brand-pale', swatch: 'bg-brand-pale', classes: 'bg-brand-pale, bg-brand-chip-bg', role: 'NEUTRAL since 2026-10-28 (= surface-alt): the selected chip or pill fill, with brand-legible ink. Never a teal fill.' },
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
  { role: 'pageTitle', spec: '24/32, 600, ink-primary', component: 'PageHeader, PageTitle', use: 'A page\'s own name.' },
  { role: 'title', spec: '18/26, 600, ink-primary', component: 'Title, DialogTitle, PageTitle level="tab"', use: 'A dialog\'s or a Settings tab\'s name; a card\'s heading.' },
  { role: 'lead', spec: '18/28, 400, ink-secondary', component: 'Paragraph size="lead"', use: 'The paragraph that opens a page or a site section.' },
  { role: 'section', spec: '16/24, 600, ink-primary', component: 'SectionHeading', use: 'A sub-section of a page, tab or dialog.' },
  { role: 'body', spec: '16/26, 400, ink-secondary', component: 'Paragraph, DialogDescription, PROSE_CHAT, .prose-doc', use: 'Reading text: a chat answer, a manual, a paragraph read rather than scanned. At most about 70ch a line.' },
  { role: 'label', spec: '14/20, 500, ink-primary', component: 'FieldLabel, ListRow', use: 'The name of a field, a switch row, a list item.' },
  { role: 'option', spec: '14/20, 400, ink-secondary', component: 'RadioGroup, Checkbox, field text', use: 'UI text: a choice, a field\'s value, the quieter line of a row. Buttons are 14/20 600.' },
  { role: 'caption', spec: '13/18, 400, ink-caption', component: 'Caption', use: 'Everything that explains.' },
  { role: 'eyebrow', spec: '13/18, 500, ink-caption, sentence case', component: 'Eyebrow', use: 'The label inside a card naming what it shows.' },
  { role: 'code', spec: '13/20, 400, the mono face', component: 'CodeBlock, InlineCode (0.9em)', use: 'Code only - a block, a command, a key.' },
]

/** The faces. */
export const FACES: { name: string; token: string; family: string; use: string; sample: string; className: string }[] = [
  { name: 'UI', token: '--font-ui', family: 'Space Grotesk 400-600', use: 'Every word and every number. No 700 below the 24px page title.', sample: 'The quick brown fox jumps over the lazy dog', className: 'font-ui' },
  { name: 'Brand', token: '--font-brand', family: 'Orbitron 700 (900 on the splash)', use: 'The wordmark ONLY.', sample: 'Proxyma', className: 'font-brand font-bold tracking-wider' },
  { name: 'Readout', token: '--font-readout', family: 'The UI face, tabular figures (tnum)', use: 'Anything carrying figures - counts, costs, dates, ids (.data-readout). Digits keep their width; a figure and its word are one face.', sample: 'input 12.3k - 10/5/2026, 7:25 AM - $0.012', className: 'data-readout' },
  { name: 'Code', token: '--font-mono', family: 'Consolas, Cascadia Code, Space Mono', use: 'Code only - a block, a command, a key. Never a number.', sample: 'docker compose up -d', className: 'font-mono' },
]

/** The size steps of the preset (fontSize), each with its one leading. */
export const FONT_SIZES: { cls: string; spec: string }[] = [
  { cls: 'text-2xs', spec: '12/16' }, { cls: 'text-xs', spec: '13/18' }, { cls: 'text-sm', spec: '14/20' },
  { cls: 'text-base', spec: '16/26' }, { cls: 'text-lg', spec: '18/26' }, { cls: 'text-xl', spec: '20/28' },
  { cls: 'text-2xl', spec: '24/32' }, { cls: 'text-3xl', spec: '30/38' }, { cls: 'text-4xl', spec: '36/44' }, { cls: 'text-5xl', spec: '48/56' },
]

/** Spacing: the 4-point rhythm every kit component keeps (since 2026-10-28), plus the control sizes. */
export const SPACING: { cls: string; px: number; use: string }[] = [
  { cls: '1', px: 4, use: 'Glyph to word in a chip; a label to its caption.' },
  { cls: '2', px: 8, use: 'A label to its field (mb-2); a field to its note (mt-2); an inline action cluster.' },
  { cls: '3', px: 12, use: 'A form\'s action row; a stat grid; the phone gutter.' },
  { cls: '4', px: 16, use: 'Fields in a form (space-y-4); a card\'s padding on a phone.' },
  { cls: '6', px: 24, use: 'Between page blocks (space-y-6); the gutter and a card\'s padding from sm; a dialog body.' },
  { cls: '8', px: 32, use: 'A large card\'s padding; between a site page\'s sections.' },
  { cls: '12', px: 48, use: 'A site page\'s larger gaps. (Settings sections are cards 24px apart since 2026-10-29.)' },
  { cls: '16', px: 64, use: 'A site section\'s vertical rhythm.' },
  { cls: 'h-8', px: 32, use: 'A small button (sm).' },
  { cls: 'tap-sm', px: 36, use: 'A field and a default button (h-9) under a mouse.' },
  { cls: 'tap', px: 44, use: 'Every control on glass (touch:).' },
]

export const RADII: { cls: string; px: string; use: string }[] = [
  { cls: 'rounded-sm', px: '6px', use: 'A tiny chip only: a kbd, a checkbox square, a skeleton bar; a pill inside an 8px track (concentric).' },
  { cls: 'rounded-md', px: '8px', use: 'THE control radius (bare rounded is the same): buttons, fields, badges, chips, menu rows, code.' },
  { cls: 'rounded-lg', px: '12px', use: 'THE surface radius: cards, panels, dialogs, popovers, menus, the composer.' },
  { cls: 'rounded-full', px: '9999px', use: 'Only what is round by nature: dots, switches, avatars, pills.' },
]

export const SHADOWS: { cls: string; use: string }[] = [
  { cls: 'shadow-card', use: 'A card, a panel, a bar on the canvas; a pill lifted out of its well. Dark adds a 5% highlight along the top - and in an app shell both appearances do, the glass\'s light edge. A card inside a card drops it.' },
  { cls: 'shadow-float', use: 'What floats: menus, popovers, lists, tooltips, dialogs, toasts. In an app shell with a highlight along the top.' },
  { cls: 'shadow-well', use: 'INSET: a sunken well - a rail, a segmented control\'s track, a code block.' },
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
  { cls: 'text-wave', use: 'WORDS at work - a band of opacity sweeping across them (working.ts).' },
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
  { name: 'PROSE_MEASURE', value: 'max-w-[70ch] lg:max-w-[min(70ch,calc(66.6667%-1rem))]', use: 'Marketing prose - at most about 70 characters a line.' },
  { name: 'THREAD_COLUMN', value: 'max-w-3xl 2xl:max-w-4xl mx-auto w-full', use: 'The chat thread.' },
  { name: 'LIST_PAGE_SIZE', value: '20', use: 'Every list of records pages at twenty (ListPager).' },
]
