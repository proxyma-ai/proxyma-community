// The UI design guideline's words. Every rule here is a rule of .claude/rules/ui-kit.md,
// .claude/rules/frontend.md or .claude/rules/landing.md stated in the present, without its history -
// and the brand and messaging rules (Brand assets, Copy and messaging) live HERE and nowhere else,
// since the design directory's README and hero brief folded into them; every list of names,
// tokens, marks and components is read from the kit (sources.mjs, the bundled registry) rather
// than typed. ASCII only, American English.
//
// md() turns `code` and **strong** into HTML after escaping, so the prose stays readable here.

import { brandMarkTerms, exportDoc, hex, kitExports, resolveToken, tokenBlocks } from './sources.mjs'

export const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
export const md = s => esc(s).replace(/`([^`]+)`/g, '<code>$1</code>').replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  // A word ASCII cannot spell, written as entities so the source stays ASCII.
  .replace(/__VI__/g, 'Ti&#7871;ng Vi&#7879;t')
const ul = items => `<ul>${items.map(i => `<li>${md(i)}</li>`).join('')}</ul>`
const p = s => `<p>${md(s)}</p>`
function table(head, rows, { raw = false, cls = '' } = {}) {
  const cell = c => (raw ? c : md(c))
  return `<div class="table-wrap${cls ? ' ' + cls : ''}"><table><thead><tr>${head.map(h => `<th>${esc(h)}</th>`).join('')}</tr></thead><tbody>${
    rows.map(r => `<tr>${r.map((c, i) => `<td data-label="${esc(head[i])}">${cell(c)}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`
}
const note = (label, body, kind = 'note') => `<div class="${kind}"><span class="label">${esc(label)}</span>${body}</div>`

/** Descriptions for exports whose source carries no comment above them. */
const API_NOTES = {
  TEXT_LINK_BASE: 'The shared part of every text link class; textLink() adds the tone.',
  PAGE_GUTTER: 'The inset inside the page column: 12px on a phone, 24px from sm.',
  PAGE_INSET_RIGHT: 'PAGE_INSET_LEFT\'s other side: the column\'s right edge for something placed absolutely in a box as wide as the window.',
  TOAST_STACK: 'The header\'s column inside TOAST_LAYER: toasts against its right edge from sm, across it on a phone.',
  TOAST_DOCK: 'A toast stack in a page\'s own flow, for a page the fixed layer would cover - the chat page docks it above its composer.',
  pageTitle: 'The browser tab title: "Page - Product", or the product alone.',
  ICON_BUTTON_BASE: 'The shared part of every icon button class: shape, focus ring, disabled state, 44px on touch.',
  createTypeahead: 'A typeahead buffer that collects keystrokes for TYPEAHEAD_MS and finds the row they spell.',
  isNavKey: 'Whether a key moves the active row (arrows, Home, End, Page Up and Down).',
  useListbox: 'The keyboard of a Select or Combobox: focus stays on the field and the active option is named by aria-activedescendant.',
  useMenuFocus: 'The keyboard of a menu: opened from the keyboard it takes focus onto the checked or first row; arrows, Home/End and typeahead move; Escape and Tab close and give focus back.',
  CHART_SERIES_OTHER: 'The chart color for the grouped remainder ("Other").',
  prefersReducedMotion: 'Whether the reader asked for reduced motion.',
  createAppearanceStore: 'A small store for the appearance preference, kept in localStorage under the key given, for an app with no state library.',
  METER_TEXT: 'The text color of each meter tier (ok, warn, over).',
  toolFold: 'Which tool calls of a group are drawn: every call still running, the finished ones folded away, the last one alone once all are done.',
  cardClass: 'The class string of a Card, for an element that must be a card but cannot be the component (a link, a list item).',
  isChunkLoadError: 'Whether an error is a lazy chunk that failed to load after a deploy - the case a reload fixes.',
  TR_INTERACTIVE: 'Added to TR for a row that opens something: a pointer and the one row hover.',
  MODAL_SCRIM_DARK: 'The darker scrim of the status screens and the lightbox.',
  // Where the source's own comment opens with a heading or history rather than a sentence.
  TEXT: 'The type scale as class strings, one per role: title, section, lead, body, label, option, caption, eyebrow.',
  textLink: 'The classes of a text link in a tone (brand, danger, quiet, warning); `inline` underlines it inside running text.',
  PAGE_COLUMN: 'The page column: the header\'s width, shared by every page.',
  PROSE_MEASURE: 'The measure for running prose that spans a page or a section of the site.',
  TITLE_SEPARATOR: 'What joins the page and the product in a browser tab title.',
  fieldBorder: 'A field\'s border: the only thing an error state changes.',
  CHART_SERIES: 'The categorical series colors, in their validated order (never reordered).',
  useIdleRotation: 'Turns the header mark slowly while nothing else moves, from requestAnimationFrame.',
  IDLE_DEG_PER_S: 'How fast the idle mark turns, in degrees a second.',
  useHideOnScroll: 'Whether a header fixed over a scrolling document should step out of the way (the landing site).',
  useExitGhost: 'An exit for something its caller unmounts at once (a dialog, a status screen): an inert copy fades out in its place.',
  themeColor: 'The canvas of a resolved appearance, for the browser chrome\'s color.',
  THEME_COLOR: 'The canvas of each appearance, as the browser chrome\'s color (`<meta name="theme-color">`).',
  applyAppearance: 'Stamps a resolved appearance on the root element, and the `theme-color` meta the phone\'s browser chrome takes its color from.',
  useShake: 'A field\'s shake on a failed submit - the `shake` prop of Input, Textarea, PasswordInput, Combobox and Checkbox.',
  pageWindow: 'Which page numbers a pager shows: the first, the last and the neighbors of the current page, in a row that keeps its width.',
  LIST_PAGE_SIZE: 'How many records a list shows at once, in all four apps: 20.',
  usePageFor: 'The page a server-paged list is on, back to the first whenever what it lists changes (a filter, a search, a tab).',
  usePagedList: 'A list paged in the browser, for a list the server sends whole because something else on the page needs all of it.',
  MARK_PATHS: 'Every brand mark as one even-odd SVG path on the 24-unit grid, by id.',
  FRAME_CLASS: 'The frame of something that is not page text - a hole in the surface, as a field is.',
  PROSE_CHAT: 'Markdown the agent wrote, at the thread\'s density.',
  TABLE_CLASS: 'The one data table: header, rows and cells, and the stacked phone layout.',
  TABLE_FLUSH: 'A table without a card around it, on the text column.',
  TR: 'A body row; it fades in as it mounts.',
  useFileDrop: 'A file drop target\'s state, with an enter and leave counter so a drag over its children does not flicker.',
  useFilePicker: 'The file picker without its control, for anything that opens it.',
  dialogPanel: 'The classes of a dialog panel of a size, with `fill` (a fixed height) or `cap` (a capped one).',
  DIALOG_OVERLAY: 'The full-screen container a dialog panel is centered in.',
  DIALOG_SHEET_OVERLAY: 'The overlay of a dialog that is a bottom sheet on a phone.',
  MODAL_SCRIM: 'The scrim under every dialog: it darkens and blurs what is behind - the one backdrop blur anywhere.',
  MENU_PANEL: 'A menu\'s panel as a class string, for a menu that owns its own surface; prefer MenuSurface.',
  SOURCE_LIST_BOX: 'The source list\'s box: no bleed since 2026-10-28 - its rules and columns stop on the page column; only a hovered row\'s fill reaches past.',
  headerNavLink: 'The classes of a navigation word on the header bar.',
  SETTINGS_PANEL: 'The settings window\'s panel.',
  SETTINGS_OVERLAY: 'The settings window\'s overlay; it does not scroll - each tab scrolls in its own column.',
  useInViewOnce: 'Whether a site scene has been scrolled into view, so it may start.',
  useScenePlayer: 'A site scene\'s clock: play, pause and replay, and reduced motion.',
  APPEARANCE_PREFERENCES: 'The three appearance preferences, in menu order.',
  CHART_INK: 'Chart ink and lines as SVG paints that follow the appearance.',
  exitingProps: 'The attributes of a panel that is leaving: inert, so nothing in it takes a click or focus.',
}

/**
 * @param {object} c
 * @param {string} c.repo
 * @param {object} c.kit     the bundled module: REGISTRY, SPECIMENS, TOKENS, GUIDE
 * @param {(key: string, picture: object) => string} c.picture  places one rendered picture
 * @param {(entry: object) => string} c.component               places one component's section
 */
export function guidelineBody({ repo, kit, picture }) {
  const T = kit.TOKENS
  const R = kit.REGISTRY
  const P = kit.GUIDE.PICTURES
  const pic = key => picture(P[key])
  const tokens = tokenBlocks(repo)
  const out = []

  // -- 1. Principles ---------------------------------------------------------------------------
  out.push(`<h2 id="principles">Principles</h2>`)
  out.push(ul([
    '**Calm, neutral, flat.** Proxyma is a tool people work in all day: information first, decoration last. The page is neutral grey in both appearances; **the brand teal acts** - the one primary action, links, focus, a selection\'s indicator - and is never a background.',
    '**Build from the kit, at the highest level that fits.** A page holds layout glue, data, state and words - never hand-made buttons, chips, cards, dialog panels or text sizes. If what you need is close to a kit part, change the kit; if it is genuinely one surface\'s own, keep it local and say why in a comment.',
    '**Semantic tokens only.** No arbitrary hex, no `dark:` variant, no `z-[...]`, no numeric `duration-*`, no `shadow-[...]`, no inline style.',
    '**No glow, anywhere.** Emphasis is a border. The one exception is the chat composer\'s light.',
    '**Glass, in the app and the consoles** (since 2026-10-29, the maintainer\'s request - it reverses 2026-09-26\'s "no glass"). Every surface of an app shell is a pane over a field of the brand\'s teals: the frame and the page are blurred glass, a card is translucent with no blur of its own, and what floats is the most opaque glass. Text stays AA over the worst point of the field, and less transparency gives solid planes. The landing site stays solid.',
    '**Every change is animated.** Things arrive, leave and swap smoothly in all four apps; nothing bounces; reduced motion makes everything instant.',
    '**Content is as wide as the header.** Every page sits in the header\'s column (`PAGE_COLUMN`); no page restates a width of its own.',
    '**Sentence case, plain words.** The English text is also the translation key, so it is written once, as it reads.',
    '**One set of parts for every surface.** The app, both consoles and the landing site draw the same components; the site\'s product scenes are the product\'s own parts at their real size, scaled once by `SceneFrame`.',
    '**Not the generic AI look.** No eyebrow pills over headings, no icon-in-a-tile card grids, no decorative grids or corner brackets, no everything-centered layouts, no neon on dark. Real product UI, concrete words, motion only where it says something.',
  ]))

  // -- 2. Appearance -----------------------------------------------------------------------------
  out.push(`<h2 id="appearance">Appearance</h2>`)
  out.push(p('Three appearances, chosen in Settings or a menu: System, Light and Dark (since 2026-10-28 - the teal-tinted Proxyma appearance is gone, and with it every colored page background). This page switches between Light and Dark beside its logo, and every swatch, specimen and logo below follows it; the token tables show both side by side.'))
  const ATTRS = { system: 'Light or Dark, as the operating system is (`prefers-color-scheme`)', light: '`data-mode="light"`', dark: '`data-mode="dark"`' }
  out.push(table(['Appearance', 'Stored as', 'On <html>', 'What it is'], T.APPEARANCES.map(a => [`**${a.name}**`, `\`${a.id}\``, ATTRS[a.id], a.note])))
  out.push(ul([
    '**One attribute: `data-mode` on `<html>`, light or dark.** Light is the bare `:root` block, so a page paints Light before any script runs; Dark is `:root[data-mode="dark"]`, which re-points the semantic tokens. Never write a `dark:` variant - one semantic class covers both.',
    'A stored `gray` or `black` (what 0.13.0-0.36 wrote for Proxyma and Dark) and the older `dark` all read as Dark, so nobody who chose a dark page wakes up to a white one. Applying an appearance removes the `data-shade` attribute older builds stamped.',
    'The stored choice is applied before first paint by the kit\'s `appearanceInitScript` (resolution in `appearance.ts`, held to it by `appearanceScript.test.ts`). The app and both consoles serve it as a FILE their Vite config emits (`/appearance-init.js`, `/prepaint.js`) because their Content-Security-Policy allows no inline script; the landing site inlines it (`MODE_INIT_SCRIPT`). No app carries a hand-written copy.',
    'A change while the page is open crossfades once (`applyAppearanceSmoothly`, a View Transition); startup applies it with none (`applyAppearance`).',
    'It is offered by `AppearanceMenu` and, inside a menu, `AppearanceMenuRow`. Choosing closes neither the submenu nor the menu, so people can compare.',
    'On the landing site the stored words - `system`, `light`, `dark` - are a published claim: the Privacy and Cookie Policies name them (amended with the maintainer\'s approval on 2026-10-28). Storing anything else there is a legal change - ask first.',
  ]))

  // -- 3. Color -----------------------------------------------------------------------------------
  out.push(`<h2 id="color">Color</h2>`)
  out.push(ul([
    'Pages use the **semantic** tokens below; they re-map per appearance for free.',
    'The raw ramps (`proxima-*`, `mono-*`) are mode-invariant and exist for the logo mark. Never for page chrome, and never an arbitrary hex.',
    'Every token holds an RGB triple, so any of them takes an opacity: `bg-surface/40`, `border-line/45`.',
    '**In an app shell the planes are glass** (`data-app-shell` on `<html>`, since 2026-10-29): the field (`.app-field`), the frame (`.app-glass`), the page (`.app-sheet`, with teal light in its top-left and bottom-right corners), a card (`.surface-card`), what floats (`.surface-float`), the bar (`.header-bar`) and a field\'s fill (`bg-field`, frosted). The solid planes are re-pointed there to what the glass composites to, so a sticky row among glass ones is no patch. **Contrast is measured on each plane composited over the field\'s worst point**, not on the token: caption ink is 4.9:1 or more on every glass plane in both appearances. A reader who asks for less transparency, or a browser without backdrop blur, gets every plane solid. Only the frame, the sheet, the bar, the scrim and what floats blur, at most 24px; never a card or a list\'s row.',
    '**The planes and inks are neutral grey in both appearances** - no teal and no blue tint. Light: canvas `#ffffff`, surface `#f6f6f7`, surface-alt `#efeff1`, line `#e4e4e7`. Dark: canvas `#161617`, surface `#1c1c1e`, surface-alt `#232326`, line `#2e2e32`.',
    '**Contrast, measured on all three planes (canvas, surface, surface-alt) in both appearances:** primary and secondary ink at 9:1 or more; caption ink 4.9:1 or more in Light (`#66666f`) and 6.1:1 or more in Dark (`#a1a1aa`) - AA for text everywhere, because captions are where small text lives. Disabled ink is 3:1 for UI only (a placeholder, a disabled control, a switch\'s off track), never for text that must be read. The status inks clear 4.5:1 on every plane. Lines are decorative and not held to 3:1. Never fade text with opacity to make it quieter; take the next ink down.',
    '**The brand acts; it is not a background.** `brand-solid` is ONE ramp step per appearance - `p900` in Light, `p700` in Dark - and nothing else in that appearance is a brand fill. `brand-legible` (`p800` / `p300`) is teal text. The focus line is teal in both. A selected row, chip or tab is a NEUTRAL fill (`surface-alt`, or `brand-chip-bg`, which is the same grey) with a teal indicator or ink (`SELECTED_ROW`: the fill and a 2px brand bar) - never a teal fill: the review measured `#909090` on a `#172e36` row at 4.46:1.',
  ]))
  const COLS = [['light', 'Light'], ['dark', 'Dark']]
  const swatch = (name, ap) => {
    const v = resolveToken(tokens[ap], name)
    return `<span class="gl-sw" style="background:rgb(${esc(v)})"></span><span class="gl-hex">${esc(hex(v))}</span>`
  }
  for (const group of T.SEMANTIC) {
    out.push(`<h3 id="color-${group.title.toLowerCase().replace(/[^a-z]+/g, '-')}">${esc(group.title)}</h3>`)
    out.push(table(['Token', 'Now', ...COLS.map(c => c[1]), 'Class', 'Role'], group.tokens.map(t => [
      `<code>--${esc(t.name)}</code>`,
      `<span class="gl-sw" style="background:rgb(var(--${esc(t.name)}))"></span>`,
      ...COLS.map(([ap]) => swatch(t.name, ap)),
      md(t.classes.split(', ').map(c => `\`${c}\``).join(' ')),
      md(t.role),
    ]), { raw: true, cls: 'gl-tokens gl-stack' }))
  }
  out.push(`<h3 id="color-roles">Fill, text and decoration</h3>`)
  out.push(pic('brand-roles'))
  out.push(ul([
    '`brand-solid` is a **fill**, one step per appearance: the primary button, a switch that is on, the chosen day. Text on it is `brand-on-solid` (9.1:1 in Light, 5.7:1 in Dark).',
    '`brand-legible` is the same color tuned for **text**: links, the wordmark, work done.',
    '`brand-accent` is **decorative only** - meter fills, dots, borders, a reveal. It cannot carry a label.',
    '`brand-pale` (and `brand-chip-bg`) is NEUTRAL since 2026-10-28 - the surface-alt grey - the fill of a selected chip or pill, always paired with `brand-legible` ink.',
  ]))
  out.push(pic('status-roles'))
  out.push(ul([
    '`success`, `warning` and `danger` are **text** colors: a verdict, an attention line, a failure and the actions that remove.',
    'The `-solid` fills are mode-invariant and carry `on-status` text: a status badge, and the destructive button.',
    'Charts take `CHART_SERIES` in order. The order is checked for color-vision deficiency - do not reorder it; the remainder is `CHART_SERIES_OTHER`.',
    'Focus and emphasis are a border: `.focus-border` (its color is `--focus-line`, teal in both appearances) for fields and cards, a brand border for a promoted card; `.focus-ring` is `brand-solid`.',
  ]))
  out.push(`<h3 id="color-ramps">The ramps (the logo, and what the semantic tokens point at)</h3>`)
  for (const ramp of T.RAMPS) {
    out.push(p(`**${ramp.title}.** ${ramp.note}`))
    out.push(`<div class="gl-ramp">${ramp.steps.map(s => `<div><span class="gl-sw gl-sw-lg" style="background:rgb(${esc(resolveToken(tokens.light, s.name))})"></span><code>${esc(s.name)}</code><span class="gl-hex">${esc(hex(resolveToken(tokens.light, s.name)))}</span>${s.role ? `<span class="gl-role">${md(s.role)}</span>` : ''}</div>`).join('')}</div>`)
  }

  // -- 4. Typography -----------------------------------------------------------------------------
  out.push(`<h2 id="typography">Typography</h2>`)
  out.push(pic('type-roles'))
  out.push(ul([
    '**One scale for every surface: the kit\'s `TEXT` roles** and the components that draw them (`PageTitle`, `Title`, `SectionHeading`, `FieldLabel`, `Caption`, `Eyebrow`, `Paragraph`). Nothing sets a text size by hand. Every role is a step of the preset\'s `fontSize`, and every step has ONE leading.',
    'A heading is `SectionHeading`; a field\'s name is `FieldLabel`. One name never does both jobs.',
    '**The scale, since 2026-10-28** (the review measured 31 size/leading/weight combinations in the app and 64 on the site, with 66% of the app\'s text at 13px): page title 24/32 600; dialog and Settings-tab title 18/26 600; section 16/24 600; **reading body 16/26 400** - a chat answer, a manual, a paragraph read rather than scanned; lead 18/28 400; **UI text 14/20** - a row, a label (500), a field\'s text (400), a button (600); caption 13/18 400; code 13/20 in the mono face.',
    'The off-scale 15px body and 17px lead are gone: a reading role sat between two steps of the scale and nothing else was that size, so every call site that wanted "a bit bigger" picked its own.',
    '**No weight 700 below the page title.** A bold label beside a semibold heading read as the heading; buttons went from 700 to 600. Orbitron\'s wordmark is the one bold word in the product chrome.',
    '**Nothing a person reads is under 12px**, and 12 (`text-2xs`) is for badges and key caps only - what sits inside a small shape.',
    '**A line is at most about 70 characters.** `Paragraph`, `PROSE_CHAT`, `.prose-doc` and `PROSE_MEASURE` cap their text blocks at 70ch of their own size; tables and code keep the column\'s width.',
    'Inline code is 0.9em of its sentence and never under 12px: Consolas\'s x-height is the UI face\'s own (both 0.49 of the em), so a smaller code token only reads as smaller.',
  ]))
  out.push(pic('faces'))
  out.push(pic('readout-vs-code'))
  out.push(ul([
    '**Numbers are the UI face\'s tabular figures** (since 2026-10-28, replacing 2026-10-05\'s Consolas digits). `.data-readout` (or `Readout`) is for anything carrying figures - counts, costs, dates, IDs: Space Grotesk with `font-variant-numeric: tabular-nums`, so digits keep one width and a column aligns, while a figure and the word beside it stay one face. `--font-readout` is the UI face. The review found Consolas digits beside Space Grotesk digits in one caption.',
    '`font-mono` is for **code** only: a code block, a command, a key or token, a stack trace, a template placeholder. An ID, a logger name, a date or a purchase order is a readout, not code - and never mono.',
    'Orbitron is the wordmark only.',
  ]))
  out.push(`<h3 id="sentence-case">Sentence case</h3>`)
  out.push(ul([
    '**Sentence case everywhere**: buttons, table headers, tab labels, card labels, stat-tile labels, menu rows.',
    'Names keep their case: tab and group names (Usage & Cost, AI Providers, Network Share), product names and the tier names. Manuals and server messages quote them.',
    'The one upper-case text in the product is a status `Badge`. `.label-upper` is for badge-like eyebrows only. A fact beside a name - a person\'s role - is the `outline` Badge: sentence case, no fill.',
    'There is no global upper-case rule on headings.',
  ]))
  out.push(pic('font-sizes'))

  // -- 5. Spacing and layout ---------------------------------------------------------------------
  out.push(`<h2 id="spacing">Spacing and layout</h2>`)
  out.push(pic('spacing'))
  out.push(ul([
    '**A 4-point rhythm** (since 2026-10-28): every padding, gap and margin in a kit component is 4, 8, 12, 16, 24, 32, 48 or 64px - Tailwind\'s 1, 2, 3, 4, 6, 8, 12 and 16. The review counted 14 paddings, 20 margins and 8 gaps in the app, 10px the commonest gap; 6, 10, 14, 20 and 28 are gone from the kit. A 2px nudge for optical alignment is not a step.',
    '**Control sizes**: a field and a default button are 36px under a mouse (`h-9`, the field\'s `min-h-9`), a small button 32px (`h-8`), and every one of them 44px on glass (`touch:h-tap`). A form row lines up with the button that submits it.',
  ]))
  out.push(p('Where each step goes. Follow it for new work; a deliberate exception carries a comment saying why.'))
  out.push(table(['Element', 'Classes'], [
    ['Page content', '`Page` - `max-w-5xl 2xl:max-w-7xl mx-auto px-3 sm:px-6 py-6 sm:py-8 short:py-3 space-y-6 short:space-y-3`; a route\'s skeleton uses the same'],
    ['Page title', '`PageHeader` / `PageTitle` - 24/32 600, its actions on its line, a caption line under it'],
    ['Dialog panel', '`w-full max-w-* mx-4` inside the dialog overlay (the app\'s `ModalShell`), which has no horizontal padding below `sm` (`px-0 py-4 sm:px-4`) - so the `mx-4` alone is the backdrop margin on a phone, never drop it'],
    ['Dialog header (divided)', '`DialogHeader` - `px-4 sm:px-6 py-4 border-b border-line/45`'],
    ['Dialog body', '`DialogBody` - `px-4 sm:px-6 py-6`, fields `space-y-4`. A title in the body (no divider) sits in the same padding'],
    ['Centered confirmation body', '`DialogBody centered` - `px-4 sm:px-6 py-6`'],
    ['Dialog footer', '`DialogFooter` - `flex justify-end gap-3 px-4 sm:px-6 py-4 border-t border-line/45`'],
    ['Form action row, footer buttons', '`gap-3`'],
    ['Inline action cluster in a row', '`gap-2` (`RowActions`)'],
    ['Settings tab root', '`SettingsSections` (`.settings-sections`: 3rem above each rule, 3rem below) for several sub-sections, `space-y-6` for one; a lead paragraph goes in its `intro`'],
    ['Settings tab title', 'drawn once by the kit\'s `SettingsWindow` from the tab\'s name (`PageTitle level="tab"`, 18/26) - a tab never draws its own name as a heading'],
    ['Settings sub-section heading', '`SettingsSection` (its `title`, `caption` and `toggle`), or `SectionHeading` (`mb-4`, or `mb-0` - `flush` - inside a `space-y-*` block), its `caption` and `action` (a section switch, an Add button) as props'],
    ['Field label to control', '`FieldLabel` (`mb-2`), in dialogs too; never `Input`\'s own `label` inside Settings. With a hint: the label `mb-1`, the hint (`FieldHint`, 13/18 caption ink) `mb-2`, then the control'],
    ['A description and its title', 'directly under the title, 4px (`mt-1`), 13/18 caption ink, `max-w-[60ch]` - a field\'s hint, a section\'s or a card\'s caption, a switch row\'s or a choice\'s caption alike; never after the content it describes'],
    ['Settings list row (title, caption, trailing action)', 'the kit\'s `ListRow` (`gap-3 py-3`, top-aligned) or `SwitchRow` in a `List` - never an action pinned top-right over a wrapping caption. Selected: `SELECTED_ROW`'],
    ['Card or panel', '`Card` - `px-4 sm:px-6 py-4` (`sm` `p-3`, `lg` `p-6 sm:p-8`), `bg-surface/40 border border-line/40 rounded-lg`, no shadow'],
    ['Stat tile, and a grid of them', '`StatTile` `px-4 py-3`, `StatGrid` `gap-3`'],
  ]))
  out.push(ul([
    'Below `sm` every dialog and every card steps to `px-4`.',
    '**Content width matches the header.** The header and every page share `PAGE_COLUMN`; a new page inherits the shared wrapper (`Page`) and never restates a width.',
    '**Gutters: 12px on a phone** for the header and every page (`PAGE_GUTTER`, `px-3 sm:px-6`), the chat thread and composer included (since 2026-10-28; they were 8px).',
    'The Settings rail fits 1280x720 without scrolling: `py-1` rows with no gap, `space-y-4` groups, `md:py-4`; its rows stay 13px for that reason. No entry is hidden to get there.',
  ]))
  out.push(table(['Constant', 'Value', 'Use'], T.LAYOUT.map(l => [`\`${l.name}\``, `\`${l.value}\``, l.use])))

  // -- 6. Shape: radius, elevation, layers ------------------------------------------------------
  out.push(`<h2 id="shape">Radius, elevation and layers</h2>`)
  out.push(pic('radii'))
  out.push(ul([
    '**Radius by role** (since 2026-10-28; the preset defines these on `theme`, so no other step exists): `rounded-md` - and bare `rounded`, the same 6px - for every control: buttons, inputs, badges, chips, menu rows, code; `rounded-lg` (10px) for every surface: cards, panels, dialogs, popovers, menus, the composer; `rounded-sm` (4px) for a tiny chip only (a kbd, a checkbox square).',
    '`rounded-full` only for what is round by nature: dots, switches, avatars, pills.',
    'The one exception is the desktop splash card, `rounded-[14px]`: it matches the native launcher window (`SplashWindow.java`, `RADIUS = 14`) it replaces on screen.',
  ]))
  out.push(pic('shadows'))
  out.push(ul([
    '**Three shadows, one per plane off the page** (since 2026-10-29): `shadow-card` on a card, a panel, a stat tile, a settings section and a lifted pill; `shadow-float` on menus, popovers, a Select\'s list, tooltips, dialogs, toasts and the lightbox; `shadow-well` (inset) on a sunken well. In an app shell the card and float shadows carry a highlight along the top - the glass\'s light edge. The preset defines no other shadow class.',
    'No arbitrary `shadow-[...]` and no colored drop shadow - on a light page it has nowhere to fade to and reads as a halo.',
    '**The glass** (since 2026-10-29): in an app shell `.surface-float` - dialogs, dropdowns, tooltips, toasts, the composer - is the most opaque glass with the strongest blur, and `.header-bar` a glass bar; a card (`.surface-card`) is translucent with no blur of its own. Give a floating panel `border`, never a border color: the class draws the light edge. On the landing site both are solid.',
    '**Behind an open dialog** everything is darkened and blurred (`MODAL_SCRIM`, `.modal-scrim`: a cool shade in Light, black in Dark); status screens and the lightbox are darker (`MODAL_SCRIM_DARK`). A dialog over a dialog lays a second scrim on top.',
    'The chat\'s pinned question has no background: the answer under it fades into the canvas (`.pin-fade`).',
    '**The composer\'s light is the one glow**: a conic gradient of the brand accent turning round the chat composer as a 1.5px ring, the same gradient blurred behind it. Lit while the composer has focus, turning faster while the agent works. Hover only firms up the neutral border.',
  ]))
  out.push(`<h3 id="layers">Layers</h3>`)
  out.push(p('Layers have names (the preset\'s `zIndex`), bottom to top. Never a `z-[...]` value.'))
  out.push(table(['Class', 'z-index', 'Use'], T.LAYERS.map(l => [`\`${l.cls}\``, String(l.value), l.use])))

  // -- 7. Motion ---------------------------------------------------------------------------------
  out.push(`<h2 id="motion">Motion</h2>`)
  out.push(p('Every UI change is smooth, in all four apps: dialogs, menus, popovers, tooltips, toasts, tab and route switches, rows appearing, state colors - enter and exit.'))
  out.push(table(['Token', 'Class', 'Value', 'Use'], T.MOTION.map(m => [`\`${m.token}\``, `\`${m.cls}\``, m.value, m.use])))
  out.push(ul([
    '**One vocabulary**: three durations and three curves. Never a numeric `duration-*`. Arrivals fade with a small rise or scale; exits are quicker fades; content that swaps fades in; nothing bounces.',
    '**Colors ease everywhere without asking**: every control, ARIA role and element with a `hover:` class gets a `--motion-fast` color and opacity transition.',
    '**Something in the flow that comes and goes** (a banner, an error line, a status note, a form under a button) is `Presence show={...}`, never a bare `{cond && <X/>}`: its height eases, so what is below moves rather than jumps (`collapse={false}` to only fade). A component that returned `null` returns `<Presence show={false} />` in the same place.',
    '**Content swaps** are `FadeSwap swapKey={...}` - nothing remounts.',
    '**Exits**: `usePresence` for a panel a component owns (menus, `Select`, `Popover`, `Tooltip`); `useExitGhost` for what a caller unmounts - every dialog leaves an inert copy (scrim `data-scrim`, panel `data-motion-panel`) that fades out. Mark a hand-made overlay the same way.',
    'Kit fields, switch rows, radio groups, table rows, notices, states and dialog footers fade in on mount by themselves.',
    '**Nothing eases while the page loads**: `installMotion()` in each app\'s entry turns transitions on two frames after first paint.',
    'An animated `transform` replaces an inline one while it runs: position a floating thing that also animates with the `translate` property.',
    '**Reduced motion**: everything instant, no exits, no crossfade, no wave.',
  ]))
  out.push(pic('working'))
  out.push(ul([
    '**An icon alone blinks; words wave** (`workingMotion`, `working.ts`). An icon button, a lone state mark or a status dot blinks at 1Hz (`animate-pulse`). A tool call\'s row, the thinking and waiting lines, a sub-agent\'s name and a running step carry `.text-wave` - a soft band of primary ink sweeping left to right every 1.8s - and their mark holds still. Never blink words.',
    'A spinning loader is the kit `Spinner`, never a lucide icon with `animate-spin` (that turns the icon\'s box, off its center). Refresh arrows that turn as feedback are `RefreshIcon spinning`.',
    'A place the reader was just sent to lights up once (`animate-reveal`).',
  ]))
  out.push(table(['Class', 'Use'], T.ANIMATIONS.map(a => [`\`${a.cls}\``, a.use])))

  // -- 8. Responsive -----------------------------------------------------------------------------
  out.push(`<h2 id="responsive">Responsive and mobile</h2>`)
  out.push(p('Three reference devices, and they do not sort by width: a phone on its side is wider than a tablet upright.'))
  out.push(table(['Device', 'Portrait', 'Landscape'], [['iPhone 11 Pro', '375 x 812', '**812 x 375**'], ['iPad mini 5', '**768 x 1024**', '1024 x 768'], ['1080p 16:9', '-', '1920 x 1080']]))
  out.push(table(['Class', 'Media query', 'Use'], T.BREAKPOINTS.map(b => [`\`${b.cls}\``, `\`${b.query}\``, b.use])))
  out.push(ul([
    'The width ladder is exactly Tailwind\'s default and stays that way. Five device classes sit beside it - use them **with** the ladder: `md:flex-row short:flex-col` reads "rows once there is width, unless there is no height".',
    '**There are no `max-*` variants** - a `max-sm:` class compiles to nothing, silently. Write mobile-first: the phone value bare, `sm:` for the rest.',
    '**Touch targets are named**: `tap` is 44px, `tap-sm` 36px. The pattern is `w-9 h-9 touch:w-11 touch:h-11` - the target grows, the glyph does not.',
    'A hover-only affordance is invisible on a phone: write `mouse:hover:`; a bare `hover:` sticks after a tap.',
    '**Anything that reserves room for a control reserves its touch size** - a 36px control is 44px on glass. `AppHeader` holds that arithmetic (`edgeFrom`); never hand-write a header reservation.',
    '**An app shell is one box pinned to the visible screen** (`data-app-shell`, `installAppViewport()`): the document never scrolls, the body follows the visual viewport. Never size a shell in `vh` or `dvh` - use `h-full`. The landing site scrolls its document and does not carry the attribute.',
    'No double-tap zoom (`touch-action: manipulation`) and no text re-inflation on rotate; pinch zoom is left alone.',
    '**The header steps aside below `lg`** (`AutoHideHeader`: a swipe up pulls it away, a swipe down returns it). The hamburger reaches `lg`; the app\'s nav words stay on the bar whenever they fit, measured rather than on a breakpoint.',
    '**Nothing to tap sits in the middle of a phone screen**: below `sm` a link or small button starts at the left edge or ends at the right one. A full-width button and a centered headline are fine. The exceptions are `DialogNotice`\'s one action and the centered confirmation dialogs.',
    '**Every text control is 16px on a touch device** (one global rule), or Safari zooms in and never zooms back.',
    '**A data table stacks below `sm`**: `TABLE_CLASS` turns each row into a block and each cell into a line labeled by its `data-label`. Give every cell but the row\'s name a `data-label`.',
    '**Nothing shows a horizontal scrollbar.** Anything that scrolls sideways is `HScroll`: bar hidden, the wheel scrolls it, it takes focus for the arrow keys, and the cut side fades out. A table inside a clipping card needs it, or its last columns cannot be reached.',
    'The new-chat composer docks at the bottom below `sm`, outside the scroller.',
  ]))

  // -- 9. Dialogs --------------------------------------------------------------------------------
  out.push(`<h2 id="dialogs">Dialogs</h2>`)
  out.push(ul([
    '**No close X.** A dialog closes on its backdrop and on Escape. So it keeps a margin of backdrop at every size, a phone included.',
    '**A footer is for real actions only - never a lone Close, Done, OK or "Got it".** A read-only dialog, a viewer and a finished step have no footer. Save, Cancel + Save, Confirm, Delete, Copy and Retry are actions.',
    '**No dialog scrolls.** A dialog is laid out to fit a 1280x720 window - wider, with two or three columns, not taller. Content of unknown length (a list of sessions, a transcript, a preview) gets a fixed or capped height and that one region scrolls inside it. The Settings window is the standing exception: each tab scrolls in its own column.',
    '**Every dialog is a layer** (`useModalLayer`): Escape reaches only the top dialog, Tab is trapped inside it, focus returns to the opener. A popover open inside a dialog that consumes Escape calls `preventDefault()`, so the dialog under it stays open.',
    '**Unsaved work asks first**: `DialogShell` with `dirty` shows "Discard unsaved changes?" on Escape, the backdrop or Cancel; its words come in as props.',
    '**One minimum, global and phone-safe**: every dialog panel is at least `min(28rem, 100% - 2rem)` wide and `min(16rem, 100dvh - 2rem)` tall, the body taking the extra height. Never add a `min-w-[...]` to a panel; `max-w-*` alone stops it sprawling.',
    'A dialog whose body is only a warning uses `DialogNotice`: the icon centered, the words under it, one action under those.',
    'On a phone a long list opens as a bottom sheet (`sheet`).',
    'A destructive action is the `danger-quiet` Button, last in its row and set apart (`ml-2`); the confirmation it opens carries the `danger` fill.',
  ]))

  // -- 10. Patterns ------------------------------------------------------------------------------
  out.push(`<h2 id="patterns">Patterns across components</h2>`)
  out.push(`<h3 id="lists-of-records">Lists of records</h3>`)
  out.push(ul([
    '**At most 20 records a page, in all four apps, under `ListPager`** - no "Load more", no infinite scroll, no unbounded list. The number is one constant, `LIST_PAGE_SIZE`; never type 20.',
    'The pager shows only past one page and eases in and out by itself.',
    '**Server-paged** when the list grows without bound: the endpoint takes `page` and `size`, applies every filter and search itself and answers with totals; `usePageFor(filters)` keeps the page.',
    '**Paged in the browser** (`usePagedList`) when the endpoint sends the whole list anyway.',
    'Not lists of records, and not paged: menus, options, navigation, the chat thread, tool calls and sub-agents in a conversation, form fields, charts and ranked summaries, one record\'s own details.',
  ]))
  out.push(`<h3 id="empty-states">Empty, loading, failed</h3>`)
  out.push(ul([
    '**An empty list, table or chart is `EmptyState`**: one left-aligned sentence saying what appears there, and the one action that makes it appear. No bordered box, no icon tile, no centered block. Once the list has rows, its Add button moves up beside the introduction.',
    'A region loading is `LoadingState` or the row skeletons shaped like what is coming; a part that failed is `ErrorState`, with the one action that retries.',
  ]))
  out.push(`<h3 id="fields-pattern">Fields</h3>`)
  out.push(ul([
    '**A field is a hole in the surface, not another surface**: `bg-canvas`. Every text control shares `FIELD_BASE`; a `bg-surface` field is a bug.',
    'Native `<select>` and `<input type="datetime-local">` are drawn by the OS and cannot match: use `Select` and `DateTimePicker`. Free text and numbers stay native.',
    'A field and its words are `Field`: the label, a description, a hint or an error, tied to the control for assistive technology.',
    '**A field\'s hint sits under its label and above the control, since 2026-10-29** (the maintainer\'s review: "Description should be placed below the section title, and have reasonable padding/margins"): the label, 4px, the hint in 13/18 caption ink at about 70 characters a line, 8px, the control - so the explanation is read before the control it explains. **The error stays under the control**, which is what it points at, and the control is described by both. **A field with no label keeps its hint under the control**: words over a bare control read as its title. `fieldWords` (`field.ts`) decides it for every kit field; a hand-made one uses `FieldHint` and `FieldNote`. Give the hint to the `Field`, never also to the control inside it.',
    '**The same holds for every description**: a section\'s (`SectionHeading`\'s `caption`), a card\'s (`CardHeader`\'s `caption`), a switch row\'s, a checkbox\'s or a radio option\'s sits directly under its title at 4px - never as a line after the content it explains. A note about a group of fields goes before them.',
    '**Side-by-side fields keep their controls level** (`FormColumns`): when every child is a labelled kit field, each is a two-row subgrid (`.field-stack` in `proxyma-primitives.css`), so a field with a hint and its neighbor without still line up.',
  ]))
  out.push(`<h3 id="menus-pattern">Menus and lists that open</h3>`)
  out.push(ul([
    '**Every list that opens has one keyboard** (`listNav.ts`): a `Select` or `Combobox` keeps focus on the field and names its active option (`useListbox`); a menu opened from the keyboard takes focus onto its checked or first row (`useMenuFocus`). Arrows, Home/End, typeahead, Enter/Space; Escape and Tab close and give focus back. A new list or menu uses one of the two hooks.',
    '**A panel anchored to a control floats on the `z-float` layer, portaled to the body** (`Popover`, `Combobox`, `DateTimePicker`; `useFloating`), so no dialog or scroller clips it. Where it goes is `place()` (`placement.ts`, tested): it opens on its preferred side, flips only when that side cannot hold it and the other is roomier, and stays 8px inside the window. **A panel that can grow - a `Popover`, and so every popover menu - is capped by the room on the side it opened**, which `place()` states as `--float-room` (`max-h-[min(24rem,var(--float-room,24rem))]`); never by the whole window, or a panel under a low anchor runs off the screen.',
    'Language and appearance inside a menu are one row each (`LanguageMenuRow`, `AppearanceMenuRow` on `SubmenuRow`), never a native select inside a dropdown.',
    'When two surfaces need different markup, the kit takes a **render prop** rather than forking (`LanguageMenu`: anchors on the site, buttons in the app). When the contents have nothing in common, the kit shares the **shell** and takes `children` as a function (`MenuSurface`).',
  ]))
  out.push(`<h3 id="chat-pattern">The agent at work</h3>`)
  out.push(ul([
    '**A tool call\'s row has one mark, the tool\'s own icon, and its color is the call\'s state** (`StateMark`): caption ink while it runs, `brand-legible` when it worked, `danger` when it failed. No spinner, check or cross in front of it.',
    '**The agents button beside the composer is always the map\'s icon** (`IconCountButton`), in every conversation. While any sub-agent works it blinks in its own caption ink, and its name says how many work.',
    'An agent\'s name in the agents map goes to it in the thread, where its panel opens and lights up once.',
    '**The new chat opens on `ChatStartHero`**: on Community the stacked logo, on Enterprise Server "Hi, {name}" (the question for a guest); under it the astronomy fact when it is on, then the composer and its links. Below `sm` the composer docks at the bottom and the links sit under the hero, on the left.',
  ]))
  out.push(`<h3 id="tabs-tables">Tabs, tables, badges</h3>`)
  out.push(ul([
    'Sub-page tabs are `SubNavTabs`: a row of underlined words in sentence case.',
    'Every table takes its parts from the kit (`TableCard`, `Th`, `SortTh`, `TR`, `TD`) with sentence-case headers in caption ink.',
    'A status is a `Badge` (solid or pale, semibold); a fact beside a name is the `outline` Badge (medium, no fill). Every badge keeps the case of its label since 2026-10-28.',
  ]))

  // -- 11. Icons and brand marks -----------------------------------------------------------------
  out.push(`<h2 id="icons">Icons and brand marks</h2>`)
  out.push(ul([
    'Every UI icon is `lucide-react`, with two exceptions: the Proxyma mark (only ever through `Logo`) and the kit\'s `BrandMark`.',
    '**Every icon-only button is `IconButton` / `iconButton(size, tone)`**: color-only hover with no hover fill, caption ink at rest, primary ink under a mouse, `danger` for a control that removes something; 24, 32 or 36px on a mouse and 44px on glass. A control\'s own state (liked, on, a running count) is its rest color. Field adornments inside an input keep their own small sizes.',
  ]))
  out.push(`<div class="gl-two">${pic('icon-buttons')}${pic('lucide')}</div>`)
  out.push(`<h3 id="brand-marks">Brand marks</h3>`)
  out.push(ul([
    '`BrandMark` draws every source type, connection and AI client: a filled silhouette on a 24-unit grid, so a vendor\'s mark and a generic glyph carry the same weight.',
    '**One ink** (`currentColor`, the same token for every mark in a row - no vendor colors), **one size per context** (16 in rows, 14 in chips and tabs), always `aria-hidden` with the name written beside it.',
    'If one type in a place has a mark, every type in that place has one.',
    '**Every mark records its source and terms.** A vendor mark comes from a source whose license permits it (Simple Icons, CC0; Copilot is MIT) and is used nominatively, the product\'s name beside it. **No Microsoft product icons, no OpenAI or Google logos**, and none for a vendor whose terms forbid recoloring or grant no use: those get a generic glyph drawn for Proxyma - never an imitation of the vendor\'s logo.',
    'In the app a source type reaches its mark only through `sourceMarks.tsx`; a slot that takes an icon component takes `markIcon(id)`.',
  ]))
  out.push(pic('marks-in-a-row'))
  const terms = brandMarkTerms(repo)
  const rows = new Map()
  for (const [id, t] of terms) {
    const key = t.terms || id
    if (!rows.has(key)) rows.set(key, { ids: [], t })
    rows.get(key).ids.push(id)
  }
  out.push(table(['Marks', 'Kind', 'Source and terms'], [...rows.values()].map(({ ids, t }) => [
    `<div class="gl-marks">${ids.map(id => `${picture({ inline: true, mark: id })}<code id="mark-${id}">${id}</code>`).join('')}</div>`,
    t.vendor ? 'Vendor mark' : 'Generic glyph',
    md(t.terms || 'A generic glyph drawn for Proxyma. No third-party source.'),
  ]), { raw: true, cls: 'gl-markterms gl-stack' }))
  out.push(p(`${terms.size} marks. Adding one: take it from a source whose terms allow a product to name an integration, convert it to one filled path on the 24 grid, and record the source and terms in \`BrandMark.tsx\`. With no such source, draw a generic glyph.`))

  // -- 12. Logo ----------------------------------------------------------------------------------
  out.push(`<h2 id="brand-logo">The logo</h2>`)
  out.push(p('The mark and the wordmark "Proxyma" are one lockup (`Logo`). The mark alone is `Logo markOnly`; pages never reference `ProxymaIcon` directly.'))
  out.push(pic('logo-lockups'))
  out.push(pic('logo-stacked'))
  out.push(`<div class="gl-two">${pic('logo-subtitles')}${pic('logo-marks')}</div>`)
  out.push(pic('logo-appearances'))
  out.push(pic('logo-clearspace'))
  out.push(`<div class="gl-two">${pic('logo-minimum')}${pic('logo-raster')}</div>`)
  out.push(ul([
    '**The wordmark** is always "Proxyma" in Orbitron 700, tracking 0.05em, in `brand-legible` ink - never upper case, never another face. The app may pass the product name its server reports.',
    '**The name sits beside the mark** - in every header, footer, card, console, document and store image. **The one stacked lockup is the Community new chat\'s hero** (`ChatStartHero`, which draws `Logo stacked` at a 72px mark): the name centered under the mark, a fifth of the mark between them. On Enterprise Server that hero is the greeting instead - "Hi, {name}" for a signed-in person, the question for a guest - never the logo.',
    '**The lockup is one box**: the gap is a quarter of the mark, rounded to the spacing scale (8px beside the 28px header mark). A second line (a console\'s name) sits under the wordmark, the pair centered on the mark. Up to a 44px mark the wordmark is 16px; from 48px it grows with the mark (29px, 37px past 56; stacked a step smaller, 25px, then 29px past 64).',
    '**The header mark is 28px on every surface** - a console whose logo is two pixels off reads as a different application. It turns slowly while idle (`useIdleRotation`; `animate-mark-idle` where nothing measures it).',
    '**Clear space** round the lockup is at least half the mark\'s height. **Minimum sizes**: the mark 16px, the lockup a 24px mark.',
    '**Colors**: the mark is built from the middle of the brand ramp (`p700` outer arcs, `p600` gap arcs, `p500` spokes, `p400` disc), mode-invariant, so it clears both a near-black canvas and white. Depth is two white and black overlays at low alpha, never a new color.',
    '**Never**: upper case, another face, stretched, recolored, rotated by hand, on a tile, in a frame, with a glow or a shadow.',
  ]))
  out.push(pic('logo-donts'))
  out.push(`<h3 id="logo-files">Files and generators</h3>`)
  out.push(table(['Form', 'Where', 'Made by'], [
    ['The mark, SVG with literal hexes', '`design/proxyma.svg`, `design/store/mark.svg`, each app\'s `favicon.svg`, the site\'s `proxyma.svg`, the operator manuals\' assets', '`python tools/design/build-logo.py`'],
    ['The mark, React (fills through the ramp)', '`libs/ui-kit/react/ProxymaIcon.tsx`', '`build-logo.py`, then `node libs/ui-kit/sync.mjs`'],
    ['PNG 512px', '`design/proxyma.png`, the site\'s `proxyma.png`, the desktop\'s static and Linux icon', '`python tools/design/rasterize-logo.py`'],
    ['PNG 256px, 192px', 'the `.deb`\'s hicolor icon; the app\'s `proxyma-192.png`', '`rasterize-logo.py`'],
    ['ICO, nine frames 16-256px', '`design/proxyma.ico`, the site\'s favicon, the Windows installer', '`rasterize-logo.py` (`--from-png` needs no browser)'],
    ['The search-result icon: the mark inset on its own background, PNG and ICO', 'the site\'s `proxyma-search.png`', '`python tools/design/search-icon.py`, from `design/proxyma.png`'],
  ]))
  out.push(p('The geometry is arithmetic and lives once, in `build-logo.py`; never edit a generated file by hand. The search-result icon exists because a search engine crops a favicon into a circle on a chip of its own color: inset on its own background, whatever it crops is background.'))

  // -- 13. Brand assets --------------------------------------------------------------------------
  out.push(`<h2 id="brand-assets">Brand assets</h2>`)
  out.push(p('What sits beside the kit in `design/`: the mark (above), the faces and their licenses, the theme of the operator manuals, and the store\'s product image. Every asset there is generated or rendered from a source named here - re-run the source rather than editing the output.'))
  out.push(`<h3 id="fonts">Faces and their licenses</h3>`)
  out.push(table(['Face', 'Its job', 'How it is shipped', 'License'], [
    ['**Space Grotesk** 400-600 (700 at display sizes)', 'The UI face, and its tabular figures for every number: every surface of the apps and the site, and the reading face of the three engineering documents (this page, the architecture document, the development guide)','The three Vite apps bundle `@fontsource/space-grotesk` (the kit\'s `proxyma-fonts.css`); the site self-hosts it through `next/font`, with the latin, latin-ext and vietnamese subsets; each engineering document embeds `design/docs-theme/fonts/space-grotesk-var-latin.woff2` (`tools/docs/lib/doc-page.mjs`)', 'SIL Open Font License 1.1'],
    ['**Orbitron** 700 (900 on the splash)', 'The wordmark and the site\'s hero lockup. Latin only: it never sets a sentence that is translated', 'As above (`@fontsource/orbitron`, `next/font`)', 'SIL Open Font License 1.1'],
    ['**Space Mono** 400', 'The code face where Consolas and Cascadia Code are not installed (`--font-mono` names it third)', 'As above', 'SIL Open Font License 1.1'],
    ['Consolas, Cascadia Code', 'The code face and the figures of a readout', 'The platform\'s own; never bundled', '-'],
  ]))
  out.push(ul([
    '**Every face is bundled or self-hosted, never fetched from a font CDN at run time.** The app runs offline and on networks that block one, and a font stylesheet from a third party sends every reader\'s address to it.',
    '**The OFL travels with the files.** It permits bundling and embedding, commercial software included, provided the license goes with the fonts and they are not sold on their own - so every directory of font files keeps its `OFL*.txt` beside them (`design/docs-theme/fonts/`, `design/store/fonts/`, `tools/docs/fonts/`). Never separate them.',
    'To update a bundled `woff2`, fetch the file the font service serves a **current** browser and keep the latin subset: an old or absent user agent is served TrueType, about three times the size.',
  ]))
  out.push(`<h3 id="operator-manuals">The operator manuals</h3>`)
  out.push(ul([
    'The license and telemetry consoles\' operator manuals are rendered by `design/docs-theme/build.py` (with `proxyma-docs.css` and `proxyma-docs.js` beside it) from each service\'s `docs/src/` into its `docs/`. **Edit the source, never the output.**',
    'They ship inside release archives and render with **no network**, so the two faces are bundled as latin subsets: `space-grotesk-var-latin.woff2` (400-700 variable, 22 KB) and `orbitron-700-latin.woff2` (6.5 KB). Monospace is not bundled - `--font-mono` resolves to the platform\'s Consolas or Cascadia Code.',
  ]))
  out.push(`<h3 id="store-image">The store\'s product image</h3>`)
  out.push(ul([
    '`design/store/enterprise.html` is Proxyma Enterprise\'s product image, 1600x1200 (4:3). `node design/store/render.mjs enterprise` renders it to `design/store/lemonsqueezy-enterprise.png` in Microsoft Edge through playwright-core (`PLAYWRIGHT_CORE` names another copy of the module).',
    '**Its headline is the Enterprise page\'s own.** Change that page\'s headline and the image together, then re-render; a listing that says what the site does not is the drift this rule exists to stop.',
    'It is the product\'s own look: a copy of the dark tokens as they were when it was made (the retired Proxyma appearance - re-render it from the Dark tokens when it next changes), the lockup with the name beside the mark, no glow and no colored shadow. The picture in it is a real Enterprise Server page (`enterprise-screenshot.png`), never a mock. Its fonts are bundled beside it with their licenses (`design/store/fonts/`), so a render is the same offline.',
  ]))

  // -- 14. Words ---------------------------------------------------------------------------------
  out.push(`<h2 id="words">Words in the UI</h2>`)
  out.push(ul([
    '**English is the source language and the fallback; the English text is the translation key**: `t(\'Connectors\')`, never a dotted key. A missing translation renders correct English, never a key or a blank.',
    'A translatable string is **one quoted literal on one line**, written with the characters it means - never assembled from parts.',
    '**A sentence is never built from English words** (`count == 1 ? "it" : "them"`): write two whole sentences.',
    'A kit component cannot call `t()`: its words come in as props (`labels`, `discardLabels`, month names), and each app\'s thin wrapper supplies them in the reader\'s language.',
    'The server writes English; the page translates a server sentence as it arrives and where a stored one is shown. Anything unknown stays exactly as sent.',
    'Not translated: the tier names (Community, Enterprise), identifiers a person quotes back (`PROJ-318`, `gpt-4o`, `SUPER_ADMIN`), protocol names, money, the wordmark.',
    '**Language names are written in their own language** ("__VI__", not "Vietnamese"); no flags anywhere - a flag is a country, a locale is a language.',
    'Vietnamese runs longer than English: leave room in rows, tabs and buttons, and check long labels.',
    'Tab names and settings paths ("Settings > Connections > Network Share") are quoted by the manuals and by server messages; rename one only together with them.',
  ]))

  // -- 15. Copy and messaging --------------------------------------------------------------------
  out.push(`<h2 id="messaging">Copy and messaging</h2>`)
  out.push(p('What the landing site, the store image and the product say about Proxyma, and in what order. The site and its scenes are drawn from the kit; these are the rules for the words around them.'))
  out.push(`<h3 id="messaging-reader">Who reads it</h3>`)
  out.push(ul([
    'A technical lead or an IT decision-maker who has been pitched "AI search" before and did not buy: skeptical of adjectives, looking for a fix to a problem they already have.',
    'The problems, in the order they hurt: **documents nobody can search** - thousands of Office files and PDFs on a share, so people ask a colleague or find a copy two quarters old; and **an answer is not the end of the job** - a ticket still needs updating, a pull request opening.',
    'The objection that decides the sale first: **confidential documents may not go to a hosted AI product.** Running on the reader\'s own machine or server is said early, not left to a badge at the bottom.',
  ]))
  out.push(`<h3 id="messaging-claims">What is claimed, in order</h3>`)
  out.push(table(['', 'Claim', 'Said as'], [
    ['1', '**Documents indexed and kept in sync**', 'Changes are re-indexed and deletions dropped, so an answer cites the current version. Scanned PDFs are read (OCR); tables stay tables.'],
    ['2', '**Hybrid retrieval**', 'Exact terms and vague descriptions both find the right passage: vectors and keywords, fused and reranked. The mechanism is evidence for the claim, never the headline.'],
    ['3', '**The systems a team already uses, indexed the same way**', 'Jira, Confluence, Bitbucket, Subversion, websites and the rest of the source catalog.'],
    ['4', '**The agent acts**', 'Through each system\'s own API, as the person: it creates and transitions Jira issues and logs work, writes Confluence pages, and opens, reviews and merges Bitbucket pull requests. Subversion is read, never written.'],
    ['5', '**It plugs into the tools people use**', 'An MCP server - Copilot, Claude Code and Cursor search the corpus from the editor - and an HTTP search API.'],
    ['6', '**Any model, local or hosted**', 'Chosen per installation and switchable. With a local model no text leaves the building.'],
    ['7', '**Team controls - Enterprise only**', 'Accounts and single sign-on, roles, per-user isolation and quotas, an administration page, Microsoft 365 sources, key accounts.'],
  ]))
  out.push(ul([
    '**Never blur the editions.** Community - Desktop and Home Server - is one person with no accounts, free and open source. Enterprise is Enterprise Server: many people, single sign-on and roles. A page that implies Community has accounts or SSO sells the wrong edition to the reader who downloads it.',
    '**Every statement about where data goes names its condition** - local or hosted model, which edition. No "private" or "never leaves your network" on its own: with a hosted model, document excerpts go to that provider.',
    '**Not claimed**: the knowledge graph as a headline (it is an optional, per-source map the agent can walk, off by default - it belongs in the manual); any write to Subversion; accounts, SSO or administration on Community; any customer-specific branding.',
  ]))
  out.push(`<h3 id="messaging-voice">Voice</h3>`)
  out.push(ul([
    '**Plain and concrete.** A number or a mechanism beats a superlative: "keeps them in sync as they change", not "always up to date"; "cites the current version", not "accurate answers". Lead with the reader\'s problem, not the product\'s architecture.',
    '**Public text states the present.** The site, the customer documents and the legal pages describe what is true now - never a past fix, a removal or a "formerly". The release notes list what is new and changed, nothing else.',
    '**Never tell a reader the product holds a secret**, not even to reassure them it is protected. Describe the capability they need and stop there.',
    'The customer documents are step-by-step manuals; a release item or a setting\'s caption is one or two sentences.',
  ]))
  out.push(`<h3 id="landing-text">The landing site</h3>`)
  out.push(ul([
    '**As little text as possible.** Headlines, labels, short phrases and the product\'s own pictures. **Nothing on a marketing page explains** how or why something works; the Questions and Answers are the one place that explains.',
    '**A diagram\'s labels name the component precisely**: "Proxyma Telemetry Server", "Proxyma License Server", "Proxyma on your PC or server" - never a bare "Proxyma" where two of them could be meant.',
    'The pictures are the product\'s own kit parts at their real size (`SceneFrame`), playing one concrete question - not icon grids or abstract diagrams (see Principles).',
  ]))
  out.push(`<h3 id="hero-lockup">The hero</h3>`)
  out.push(ul([
    '**The lockup: ENTERPRISE / SEARCH / ACTION in Orbitron 700, one rectangle.** From `sm`, ENTERPRISE on the left and SEARCH over ACTION on the right, set at 0.408em with a line height of 1 so their two lines of ink come to the height of the word beside them. Below `sm` it is three stacked lines of one width, ENTERPRISE at 0.67em. ACTION is in `brand-legible`.',
    'Every word is justified to its column: **each letter a flex child, `space-between` taking up the slack** - never hand-tuned letter-spacing, which holds at one font size only.',
    'The block is `aria-hidden`; the `h1` is named "Enterprise search. Enterprise action." It is not translated - a geometric composition in a face with no Vietnamese glyphs.',
    '"Enterprise" there is the adjective, not the tier, and "Action" is literal: a replacement word has to name something the product does as well as it searches.',
    'Under it, one sentence saying what Proxyma is and which systems it answers from, then the actions: **one primary button, the free download**, and the hosted demo as the secondary one.',
  ]))

  // -- 16. Accessibility -------------------------------------------------------------------------
  out.push(`<h2 id="accessibility">Accessibility</h2>`)
  out.push(ul([
    '**Focus is always visible**: `.focus-ring` on controls and `.focus-border` on fields and cards, on keyboard focus (`focus-visible`).',
    '**Every control has a name.** An icon-only button takes a required `label`; a `Tooltip` is a hint, not the name. A switch, a segmented control and a radio group take `ariaLabel`. Decorative glyphs and marks are `aria-hidden` with the name written beside them; a mark-only logo that stands for the product takes `ariaLabel`.',
    '**The keyboard core**: lists and menus as above; dialogs trap Tab and give Escape to the top layer; `SubNavTabs` move with the arrows; an overflowing `HScroll` is a tab stop.',
    '**Contrast**: text uses the legible tokens (`ink-*`, `brand-legible`, `success`, `warning`, `danger`); `brand-accent` never sits behind a label; a solid status fill carries `on-status` text; `ink-disabled` is for placeholders and disabled controls only.',
    'Something that just appeared announces itself: notices and callouts are `status`, a failure is `alert`.',
    'A disabled button that a person might wonder about says why (`disabledReason`).',
    '**Targets**: 44px on glass, 36px under a mouse - above WCAG 2.2\'s 24px floor.',
    '**Reduced motion** turns every animation and transition off: no exits, no crossfade, no wave.',
    'A language\'s name carries its own `lang`.',
  ]))

  return out.join('\n')
}

/** The levels table, an index of every component, and the groups follow (written by the builder). */
export function componentsIntro(kit) {
  const R = kit.REGISTRY
  const counts = {}
  for (const e of R.KIT_REGISTRY) counts[e.level] = (counts[e.level] || 0) + 1
  const out = [`<h2 id="components">Components</h2>`]
  out.push(p(`${R.KIT_REGISTRY.length} components, every one drawn live below by the kit itself in whichever appearance is chosen. Reach for one before writing markup, at the highest level that fits.`))
  out.push(table(['Level', 'Atomic', 'What', 'Components'], [
    ['Foundations', 'atom', 'Motion and resilience; the helpers in the API index', String(counts.foundation || 0)],
    ['L1', 'atom, molecule', 'Text, controls, marks, meters, the brand', String(counts.L1 || 0)],
    ['L2', 'molecule, organism', 'Surfaces, states, forms, lists, tables, dialogs, menus, chat, sources', String(counts.L2 || 0)],
    ['L3', 'organism, template', 'The header, pages and screens, the settings window, the site\'s sections', String(counts.L3 || 0)],
  ]))
  out.push(`<div class="gl-index">${R.KIT_GROUPS.map(g => {
    const names = R.KIT_REGISTRY.filter(e => e.group === g.id).map(e => `<a href="#${e.name}">${e.name}</a>`)
    return `<div><a class="gl-index-head" href="#group-${g.id}">${esc(g.title)}</a><p>${names.join(' ')}</p></div>`
  }).join('')}</div>`)
  out.push(p('Each entry: what it is, when to use it and when not, the props a page author sets (`*` required; the full types are in the source file named), related parts, and its specimens. A specimen of something that opens shows its trigger; where the kit allows, it is also drawn open. Every specimen is interactive in the gallery, `/__kit` (`npm run dev` in `apps/app/frontend`).'))
  return out.join('\n')
}

/** One registry entry. `specimens` is the HTML of its rendered specimens. */
export function componentEntry(e, specimens, { note } = {}) {
  const props = e.props.length
    ? table(['Prop', 'Type', 'Default', 'What it does'], e.props.map(pr => [
      `<code>${esc(pr.name)}</code>${pr.required ? '<span class="gl-req" title="Required">*</span>' : ''}`,
      `<code>${esc(pr.type)}</code>`,
      pr.default ? `<code>${esc(pr.default)}</code>` : '',
      md(pr.note),
    ]), { raw: true, cls: 'gl-props gl-stack' })
    : ''
  return [
    `<h3 id="${e.name}">${esc(e.name)}</h3>`,
    `<div class="gl-entry">`,
    `<p class="gl-meta"><span class="badge">${esc(e.level)}</span> <span class="badge">${esc(e.atomic)}</span> <code>libs/ui-kit/react/${esc(e.file)}</code></p>`,
    `<p class="gl-purpose">${md(e.purpose)}</p>`,
    `<div class="gl-useavoid"><div><p class="gl-label">Use it for</p>${ul(e.use)}</div><div><p class="gl-label">Not for</p>${ul(e.avoid)}</div></div>`,
    props,
    e.related && e.related.length ? `<p class="gl-related">See also: ${e.related.map(r => `<a href="#${esc(r)}">${esc(r)}</a>`).join(', ')}</p>` : '',
    note ? `<p class="gl-note">${md(note)}</p>` : '',
    `<div class="gl-specs">${specimens}</div>`,
    `</div>`,
  ].join('\n')
}

/** Every export that is not a component: functions, hooks, constants - and the types. */
export function apiIndex(repo, kit) {
  const all = kitExports(repo)
  const values = all.filter(e => e.kind === 'value')
  const types = all.filter(e => e.kind === 'type')
  const byFile = new Map()
  for (const e of values) {
    if (!byFile.has(e.file)) byFile.set(e.file, [])
    byFile.get(e.file).push(e)
  }
  const out = [`<h2 id="api">API index: helpers, hooks and constants</h2>`]
  out.push(p(`The ${values.length} exports that are not components, by source file, each with the first sentence of its own comment. Import them from the kit like the components.`))
  const rows = []
  for (const [file, list] of byFile) {
    for (const e of list) {
      const doc = API_NOTES[e.name] || exportDoc(repo, e.name, e.file) || ''
      rows.push([`<code id="${esc(e.name)}">${esc(e.name)}</code>`, `<code>${esc(file)}</code>`, md(doc.replace(/`/g, '\'').replace(/'([^']+)'/g, (m, x) => (/^[\w.\-:/()[\]{}=<> ]+$/.test(x) && x.length < 40 ? `\`${x}\`` : m)))])
    }
  }
  out.push(table(['Export', 'File', 'What it is'], rows, { raw: true, cls: 'gl-api gl-stack' }))
  out.push(`<h3 id="api-types">Types</h3>`)
  out.push(`<p class="gl-types">${types.map(t => `<code id="${esc(t.name)}">${esc(t.name)}</code>`).join(' ')}</p>`)
  return out.join('\n')
}

export function extending() {
  return [
    `<h2 id="extending">Extending the kit</h2>`,
    table(['Step', 'What'], [
      ['1. Change the source', '`libs/ui-kit/react/` (components), `libs/ui-kit/tokens/` (tokens, primitives, the Tailwind preset), `libs/ui-kit/i18n/` (the translation runtime). Never the generated copies - every one says DO NOT EDIT, and the next sync reverts them.'],
      ['2. Keep it presentational', 'A kit file imports only `react`, `react-dom`, `lucide-react` and its siblings. State and navigation arrive as props; words arrive as props; each app writes the thin wrapper that supplies them. Stateful files start with `\'use client\'`. A function a server component calls lives in a plain module. No two sources may differ only in case.'],
      ['3. Register it', 'An entry in `apps/app/frontend/src/dev/kitRegistry.ts` (level, group, purpose, use, avoid, props, related, specimen titles) and a specimen in `kitSpecimens.tsx`. `kitRegistry.test.ts` fails on a kit export with neither.'],
      ['4. Look at it', '`/__kit` in `npm run dev` (apps/app) renders everything in every appearance - check a change there before it reaches a page.'],
      ['5. Sync', '`node libs/ui-kit/sync.mjs` copies the kit into the four frontends. `sync.mjs --check` fails when a consumer is behind; `verify-kit.mjs` fails when a generated file was edited. Both run in CI.'],
      ['6. Regenerate this guideline', '`node tools/docs/build-ui-guideline.mjs` (needs `npm ci` in `apps/app/frontend`), and commit `docs/ui-guideline.html` with the change. CI runs it with `--check`, and `ui-guideline.test.mjs` fails when a kit export is missing from the committed page.'],
    ]),
    ul([
      'A change only one surface wants does not belong in the kit - put it in that app\'s own stylesheet or a local component. A whole pattern (the site\'s sections, plan cards, scene frame) is the exception: every surface is built from one kit.',
      'Do not build a lookalike from a screenshot. If the kit is missing a primitive, add it to the kit.',
      'What stays app-local on purpose: the wrappers that supply `t()` (`ModalShell`, `DeleteConfirmModal`, `PasswordChecklist`, the date picker\'s month names) and logic around kit parts (the chat\'s markdown parser, the mention editor, the knowledge graph canvas).',
    ]),
  ].join('\n')
}
