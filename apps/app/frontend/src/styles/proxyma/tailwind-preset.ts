/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/tokens/tailwind-preset.ts
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import type { Config } from 'tailwindcss'

/**
 * PROXYMA TAILWIND PRESET - the type scale, radius scale, elevation scale and colour
 * families, defined once for every Proxyma frontend.
 *
 * GENERATED FILE in each consumer. Edit libs/ui-kit/tokens/tailwind-preset.ts and run
 * `node libs/ui-kit/sync.mjs` from the repository root.
 *
 * Consume it, do not copy it:
 *
 *     import proxymaPreset from './src/styles/proxyma/tailwind-preset'
 *     export default {
 *       presets: [proxymaPreset],
 *       content: ['./index.html', './src/**\/*.{ts,tsx}'],
 *     } satisfies Config
 *
 * `content` stays per-repo (the paths genuinely differ). Everything else belongs here. A
 * repo may still `extend` on top for something only it has - apps/app's `blink`
 * keyframe, for instance - but anything added to more than one repo belongs in this file.
 *
 * Every colour resolves through a CSS custom property from proxyma-tokens.css, so
 * light/dark is one attribute on <html> rather than a second set of classes on every
 * element. Do NOT write `dark:` variants.
 *
 * Comment style note: this file deliberately avoids a long run of `=` at column 1.
 * TypeScript parses seven or more of them at the start of a line as a merge-conflict
 * marker, and the resulting error storm points at the wrong place entirely.
 */
export default {
  // Never referenced by the components (there are no `dark:` variants anywhere in the
  // kit), but declared so that if someone does reach for one it keys off the same
  // attribute as the tokens rather than Tailwind's default `.dark` class, which nothing
  // in any of these apps sets.
  darkMode: ['selector', ':root[data-mode="dark"]'],
  // Deliberately empty. A preset cannot know the consumer's layout, and a wrong `content`
  // silently produces a stylesheet missing half its classes.
  content: [],
  theme: {
    /**
     * THE DEVICE CLASSES, and the reason they are not just widths.
     *
     * The three reference devices are an iPhone 11 Pro, an iPad mini 5 and a 1080p monitor, and
     * they DO NOT SORT BY WIDTH:
     *
     *     iPhone 11 Pro   portrait    375 x 812
     *     iPhone 11 Pro   landscape   812 x 375   <- WIDER than the tablet in portrait
     *     iPad mini 5     portrait    768 x 1024
     *     iPad mini 5     landscape  1024 x 768
     *     1080p                      1920 x 1080
     *
     * A phone on its side is 812px wide and a tablet upright is 768px, so both match `md` and a
     * width-only rule hands the phone a tablet layout on a viewport 375px TALL - which is where a
     * sticky header plus a sticky footer leaves about two lines of content. Nothing in these four
     * frontends looked at anything but width before 2026-09-06.
     *
     * So: the width ladder stays exactly Tailwind's, because every existing class in four apps is
     * written against it and re-cutting it would reflow all of them at once. These are ADDED, and
     * each one answers a question width cannot:
     *
     *   short      the viewport is not tall enough for stacked chrome - phone landscape, and a
     *              desktop window dragged short. Collapse vertical padding, never both bars.
     *   tall       enough height to spend on breathing room.
     *   touch      the pointer is a finger: 44px targets, no hover-only affordance.
     *   mouse      a fine pointer: hover is available and 36px targets are fine.
     *   stylus     fine pointer, no hover - an iPad with a pencil. Sizes like touch, hovers like
     *              nothing. Rare, and cheap to be right about.
     *
     * USE THEM WITH THE WIDTH LADDER, not instead of it: `md:flex-row short:flex-col` reads as
     * "rows once there is width, unless there is no height".
     *
     * THE ORDER OF THIS OBJECT IS PART OF THE CONTRACT - do not sort it. Tailwind emits screen
     * variants in the order they are declared, and `sm:py-24` and `short:py-8` are both media
     * rules at equal specificity, so when BOTH match it is source order that decides. The five
     * additions are declared AFTER the width ladder precisely so they win: `py-16 sm:py-24
     * short:py-8` reads as "the desktop rhythm, unless there is no height", and needs no
     * max-width bound on the `short:` half to say it.
     *
     * Alphabetising these, or moving `touch` above `sm` to group the pointer queries, silently
     * inverts every one of those rules - the classes still compile, the stylesheet still builds,
     * and a phone in landscape quietly gets the desktop's padding back. Emission order has now
     * cost this project two header bugs in one afternoon (see AppHeader and UserMenu); this note
     * is here so the third one is not caused by a tidy-up.
     */
    screens: {
      sm: '640px',
      md: '768px',
      lg: '1024px',
      xl: '1280px',
      '2xl': '1536px',

      // 500px is chosen to sit between a phone in landscape (375) and a tablet in portrait
      // (1024) with room on both sides - not on the nose of either, so a browser chrome bar or a
      // software keyboard cannot flip the class by a pixel.
      short: { raw: '(max-height: 500px)' },
      tall: { raw: '(min-height: 700px)' },

      touch: { raw: '(pointer: coarse)' },
      mouse: { raw: '(pointer: fine) and (hover: hover)' },
      stylus: { raw: '(pointer: fine) and (hover: none)' },
    },
    /**
     * RADIUS BY ROLE (since 2026-10-28; 8/12/6 since 2026-10-29, the depth review - it was 6/10/4).
     * Set on `theme`, not `extend`, so Tailwind's own steps are gone and a stray `rounded-xl` or
     * `rounded-3xl` compiles to nothing rather than to a sixth radius:
     *
     *   rounded-sm    6px  a tiny chip only (a kbd)
     *   rounded       8px  \ every CONTROL - buttons, fields, badges, chips, menu rows
     *   rounded-md    8px  /
     *   rounded-lg   12px  every SURFACE - panels, dialogs, popovers, menus, cards, the composer
     *   rounded-full       a genuine circle or pill - avatars, status dots, switches
     */
    borderRadius: {
      none: '0px',
      sm: '6px',
      DEFAULT: '8px',
      md: '8px',
      lg: '12px',
      // xl and up were lost when this scale replaced Tailwind's on 2026-10-29, and every `rounded-xl`
      // and `rounded-2xl` in four apps silently drew square (the maintainer's review the same day).
      xl: '16px',
      '2xl': '20px',
      '3xl': '24px',
      full: '9999px',
    },

    /**
     * THREE SHADOWS, one per plane above or below the page (since 2026-10-29, the depth review),
     * each mode-aware through its token in proxyma-tokens.css:
     *
     *   shadow-card   a card, a panel, a bar on the canvas; a selected pill lifted out of its well
     *   shadow-float  what floats - menus, popovers, a Select's list, tooltips, dialogs, toasts
     *   shadow-well   inset - a sunken well: a rail, a segmented control's track, a code block
     *
     * On `theme`, so `shadow-sm` and the rest of Tailwind's steps do not exist; no hand-written
     * `shadow-[...]`, and no colored drop shadows - on a light page they have nowhere to fade to
     * and read as a halo.
     */
    boxShadow: {
      none: 'none',
      card: 'var(--shadow-card)',
      float: 'var(--shadow-float)',
      well: 'var(--shadow-well)',
    },

    extend: {
      /**
       * TOUCH TARGETS, as named sizes rather than remembered numbers.
       *
       * `tap` is 44px: the smallest target a finger hits reliably, and what Apple's HIG has
       * asked for since 2010. WCAG 2.2 SC 2.5.8 sets a floor of 24px, which is a floor and not a
       * design - the kit's icon buttons are 36px, which passes it on a mouse and is uncomfortable
       * on glass. Pair them: `w-9 h-9 touch:w-11 touch:h-11`.
       *
       * `tap-sm` is the 36px the kit already uses, named so a component can say which it means.
       */
      minWidth: { tap: '44px', 'tap-sm': '36px' },

      // THE MOTION TOKENS (proxyma-tokens.css), so a class says which step it means. DEFAULT is what
      // a bare `transition` / `transition-colors` takes - Tailwind's own 150ms and curve until
      // 2026-10-10. `ease-out` / `ease-in` / `ease-in-out` keep Tailwind's names and take ours.
      transitionDuration: {
        DEFAULT: 'var(--motion-fast)',
        fast: 'var(--motion-fast)',
        base: 'var(--motion-base)',
        slow: 'var(--motion-slow)',
      },
      transitionTimingFunction: {
        DEFAULT: 'var(--ease-in-out)',
        out: 'var(--ease-out)',
        in: 'var(--ease-in)',
        'in-out': 'var(--ease-in-out)',
      },
      minHeight: { tap: '44px', 'tap-sm': '36px' },
      spacing: { tap: '44px', 'tap-sm': '36px' },

      /**
       * THE LAYERS, by name (2026-10-14). There were twelve arbitrary values in the kit and the
       * apps - 20, 50, 9000, 9999, 10000, 10001, 10030, 10040, 10050, 99999 - and the order between
       * them was written down nowhere, so a Select or a tooltip opened inside a menu drew BENEATH
       * it (10000 under 10030) and a submenu could open under the dialog it came from. Bottom to
       * top:
       *
       *   raised    10     a thing lifted over its siblings in the flow
       *   sticky    20     a sticky row or bar inside a scrolling region
       *   header    50     the page header
       *   dropzone  9000   the full-window file drop layer
       *   dialog    9999   every dialog's overlay, the Settings window, the lightbox
       *   toast     10010  the toast stack - above dialogs, so a save inside one is acknowledged
       *   float     10030  everything anchored to a control: menus, popovers, a Select's list, a
       *                    Combobox's list, the date picker, submenus - above dialogs, since a
       *                    dialog is where most of them open
       *   tooltip   10035  a tooltip, which can belong to anything below it
       *   alert     10040  "Discard unsaved changes?" over the dialog that asks it
       *   tour      10050  the onboarding tour, over everything it points at
       *   status    99999  the restart, maintenance and quit screens - nothing is usable under them
       */
      zIndex: {
        raised: '10',
        sticky: '20',
        header: '50',
        dropzone: '9000',
        dialog: '9999',
        toast: '10010',
        float: '10030',
        tooltip: '10035',
        alert: '10040',
        tour: '10050',
        status: '99999',
      },

      // THE TYPE SCALE, ten steps (since 2026-10-28, the UI review: the app rendered 31 size,
      // leading and weight combinations and the site 64, with 15px and 17px roles and 13px at four
      // line heights). Each step has ONE leading; the kit's `TEXT` roles (typeScale.ts) name what
      // each is for, and nothing sets a size by hand:
      //
      //   2xs  12/16  badges and kbd ONLY - what sits inside a small shape
      //   xs   13/18  caption (and 13/20 code, `TEXT.code`)
      //   sm   14/20  UI text - rows, labels (500), field text (400), buttons (600)
      //   base 16/26  reading body - a chat answer, a manual, a paragraph (section: 16/24, 600)
      //   lg   18/26  a dialog's or a Settings tab's title (600)
      //   xl   20/28
      //   2xl  24/32  a page's title (600)
      //   3xl  30/38, 4xl 36/44, 5xl 48/56 - display, the landing site's
      //
      // NOTHING A PERSON READS IS UNDER 12px (the 2026-10-15 readability pass), and 12 is for a
      // badge or a key cap, not running text. The odd steps the 2026-10-15 pass left (21, 25, 29,
      // 37, 49) moved to the clear scale above.
      fontSize: {
        '2xs':  ['12px', { lineHeight: '16px' }],
        xs:     ['13px', { lineHeight: '18px' }],
        sm:     ['14px', { lineHeight: '20px' }],
        base:   ['16px', { lineHeight: '26px' }],
        lg:     ['18px', { lineHeight: '26px' }],
        xl:     ['20px', { lineHeight: '28px' }],
        '2xl':  ['24px', { lineHeight: '32px' }],
        '3xl':  ['30px', { lineHeight: '38px', letterSpacing: '-0.01em' }],
        '4xl':  ['36px', { lineHeight: '44px', letterSpacing: '-0.01em' }],
        '5xl':  ['48px', { lineHeight: '56px', letterSpacing: '-0.01em' }],
      },

      colors: {
        // --- Semantic. Re-map per mode for free. Reach for these first. ---
        // The four planes (proxyma-tokens.css): the page, a card on it, a well sunk into a card,
        // and what floats. `field` is a text control's fill - white in Light, a well in Dark.
        canvas:          'rgb(var(--canvas) / <alpha-value>)',
        surface:         'rgb(var(--surface) / <alpha-value>)',
        'surface-alt':   'rgb(var(--surface-alt) / <alpha-value>)',
        'surface-float': 'rgb(var(--surface-float) / <alpha-value>)',
        // A text control's fill is GLASS in an app shell (since 2026-10-29): `--field-alpha` is 0.72 in
        // Light and 0.22 in Dark there, and 1 everywhere else, so `bg-field` is frosted on a glass card
        // and solid on the landing site with one class.
        field:           'rgb(var(--field) / calc(var(--field-alpha, 1) * <alpha-value>))',
        line:            'rgb(var(--line) / <alpha-value>)',
        ink: {
          primary:   'rgb(var(--ink-primary) / <alpha-value>)',
          secondary: 'rgb(var(--ink-secondary) / <alpha-value>)',
          caption:   'rgb(var(--ink-caption) / <alpha-value>)',
          disabled:  'rgb(var(--ink-disabled) / <alpha-value>)',
        },
        brand: {
          solid:  'rgb(var(--brand-solid) / <alpha-value>)',
          accent: 'rgb(var(--brand-accent) / <alpha-value>)',
          pale:   'rgb(var(--brand-pale) / <alpha-value>)',
          // Foreground for text and icons sitting ON a brand-solid fill. Every such call
          // site used a literal `text-white`, which only holds while brand-solid stays
          // dark enough for it - and a call site cannot see how light the fill under it
          // is. The inline `255 255 255` fallback is not redundant with the token's own
          // default: if the custom property is ever missing at the point of use, the whole
          // rgb() is invalid at computed-value time, which makes `color` fall back to
          // `unset` (i.e. INHERIT) and the label silently picks up --ink-secondary -
          // near-black on a dark fill. A var() fallback keeps that failure mode legible.
          'on-solid': 'rgb(var(--brand-on-solid, 255 255 255) / <alpha-value>)',
          // Text-only variants. brand-coloured TEXT on the page's own background needs a
          // different step than a brand FILL does; see the token file.
          legible:          'rgb(var(--brand-legible) / <alpha-value>)',
          'accent-legible': 'rgb(var(--brand-accent-legible) / <alpha-value>)',
          // THE SELECTED PAIR: an active nav pill, the open account-menu trigger, a selected filter
          // chip or row. chip-bg is NEUTRAL since 2026-10-28 (--brand-pale is the surface-alt grey in
          // both modes) and chip-text is brand-legible, so a selection is teal ink on grey - never a
          // teal fill, which the review measured failing contrast in Dark.
          'chip-bg':   'rgb(var(--brand-pale) / <alpha-value>)',
          'chip-text': 'rgb(var(--brand-legible) / <alpha-value>)',
        },
        // The status inks are TEXT colours (`text-danger`); `-solid` is the FILL behind a status
        // badge or a destructive button, with `text-on-status` on it. See the token file.
        success: { DEFAULT: 'rgb(var(--success) / <alpha-value>)', solid: 'rgb(var(--success-solid) / <alpha-value>)' },
        danger:  { DEFAULT: 'rgb(var(--danger) / <alpha-value>)',  solid: 'rgb(var(--danger-solid) / <alpha-value>)' },
        warning: { DEFAULT: 'rgb(var(--warning) / <alpha-value>)', solid: 'rgb(var(--warning-solid) / <alpha-value>)' },
        'on-status': 'rgb(var(--on-status) / <alpha-value>)',
        // A switch's knob.
        thumb: 'rgb(var(--thumb) / <alpha-value>)',

        // --- Raw ramps. MODE-INVARIANT: only for brand fills that must not re-step (the
        // logo mark). Page chrome goes through the semantic families. ---
        proxima: {
          50:  'rgb(var(--p50)  / <alpha-value>)',
          100: 'rgb(var(--p100) / <alpha-value>)',
          200: 'rgb(var(--p200) / <alpha-value>)',
          300: 'rgb(var(--p300) / <alpha-value>)',
          400: 'rgb(var(--p400) / <alpha-value>)',
          500: 'rgb(var(--p500) / <alpha-value>)',
          600: 'rgb(var(--p600) / <alpha-value>)',
          700: 'rgb(var(--p700) / <alpha-value>)',
          800: 'rgb(var(--p800) / <alpha-value>)',
          900: 'rgb(var(--p900) / <alpha-value>)',
          950: 'rgb(var(--p950) / <alpha-value>)',
        },
        mono: {
          50:  'rgb(var(--n50)  / <alpha-value>)',
          100: 'rgb(var(--n100) / <alpha-value>)',
          200: 'rgb(var(--n200) / <alpha-value>)',
          300: 'rgb(var(--n300) / <alpha-value>)',
          400: 'rgb(var(--n400) / <alpha-value>)',
          500: 'rgb(var(--n500) / <alpha-value>)',
          600: 'rgb(var(--n600) / <alpha-value>)',
          700: 'rgb(var(--n700) / <alpha-value>)',
          800: 'rgb(var(--n800) / <alpha-value>)',
          900: 'rgb(var(--n900) / <alpha-value>)',
          950: 'rgb(var(--n950) / <alpha-value>)',
        },

        // Categorical chart series. Reach for this instead of a literal `text-violet-400`
        // when colour has to carry an entity's identity rather than emphasis or status.
        chart: {
          1: 'rgb(var(--chart-series-1) / <alpha-value>)',
          2: 'rgb(var(--chart-series-2) / <alpha-value>)',
          3: 'rgb(var(--chart-series-3) / <alpha-value>)',
          4: 'rgb(var(--chart-series-4) / <alpha-value>)',
          other: 'rgb(var(--chart-series-other) / <alpha-value>)',
        },
      },

      // The UI face (Space Grotesk - every word and every number, its tabular figures for a
      // readout), the brand face (Orbitron - the wordmark ONLY) and the code face (code only). The `font-grotesk` / `font-orbitron` class aliases had no call
      // site left and went on 2026-10-14; the landing site's next/font variables of those names
      // are CSS variables the token file reads, not these classes.
      fontFamily: {
        ui:    ['var(--font-ui)'],
        brand: ['var(--font-brand)'],
        mono:  ['var(--font-mono)'],
      },

      keyframes: {
        // "An agent is still working" - the agents button's icon while sub-agents work (the kit's
        // IconCountButton `working`), in the app and in the landing site's chat scenes. Soft rather
        // than a caret's hard on/off, which beside a nav word reads as an alarm; 1Hz like every
        // other blink. It was the app's own until 2026-10-07, when the scenes drew that button too.
        'agent-working': {
          '0%, 100%': { opacity: '1' },
          '50%':      { opacity: '0.2' },
        },
      },
      animation: {
        // EVERY BLINK IS 1Hz (maintainer's rule, 2026-10-07: "some elements blink too slow").
        // Tailwind's own pulse is a 2s cycle - half that - and it is what every ICON ALONE that is
        // still going uses: a lone state mark, a working dot, Stop while it stops. Words at work
        // WAVE instead (`.text-wave`, since 2026-10-14 - the kit's working.ts says which). Same
        // keyframes, one cycle a second. The app's caret and working dot are 1s as well.
        pulse: 'pulse 1s cubic-bezier(0.4, 0, 0.6, 1) infinite',
        'agent-working': 'agent-working 1s ease-in-out infinite',
        // THE MARK'S IDLE TURN where nothing measures it - a large mark at rest (Settings > About,
        // the site's footer): one turn in twelve seconds, the 30 degrees a second `useIdleRotation`
        // (IDLE_DEG_PER_S) turns the header's mark at. Reduced motion stops it (the primitives).
        'mark-idle': 'spin 12s linear infinite',
      },
    },
  },
  plugins: [],
} satisfies Config
