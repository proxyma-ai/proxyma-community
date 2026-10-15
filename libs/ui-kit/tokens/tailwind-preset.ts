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

      // Ten steps, each with the leading that keeps the smaller sizes readable rather than
      // merely smaller. Body default is 14/1.6.
      //
      // NOTHING A PERSON READS IS UNDER 12px (readability pass, 2026-10-15 - maintainer: "improve
      // the text readiness (font size, thickness, text color) in all apps as well as docs"). The
      // two bottom steps each went up: `2xs` 10 -> 12 is the FLOOR, for what sits inside a small
      // shape (a compact or status badge, a chart's axis, a count in a mark, a 24px avatar's
      // initials, a step number); `xs` 12 -> 13 is the kit's dense UI text - captions, fields,
      // small buttons, the Settings rail, menus' second lines - about two thirds of every
      // Settings window. Measured in Edge at 1280: 65% of a Settings tab's characters were 12px
      // and 5% were 10px. The line heights did not move, so no row, rail or dialog got taller:
      // the Settings rail still fits 1280x720 (its rows are `py-1` around an 18px line).
      fontSize: {
        '2xs':  ['12px', { lineHeight: '16px' }],
        xs:     ['13px', { lineHeight: '18px' }],
        sm:     ['14px', { lineHeight: '22px' }],
        base:   ['16px', { lineHeight: '26px' }],
        lg:     ['18px', { lineHeight: '28px' }],
        xl:     ['21px', { lineHeight: '30px' }],
        '2xl':  ['25px', { lineHeight: '34px' }],
        '3xl':  ['29px', { lineHeight: '38px' }],
        '4xl':  ['37px', { lineHeight: '44px', letterSpacing: '-0.01em' }],
        '5xl':  ['49px', { lineHeight: '56px', letterSpacing: '-0.02em' }],
      },

      colors: {
        // --- Semantic. Re-map per mode for free. Reach for these first. ---
        canvas:        'rgb(var(--canvas) / <alpha-value>)',
        surface:       'rgb(var(--surface) / <alpha-value>)',
        'surface-alt': 'rgb(var(--surface-alt) / <alpha-value>)',
        line:          'rgb(var(--line) / <alpha-value>)',
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
          // much lighter step than a brand FILL does; see the token file.
          legible:          'rgb(var(--brand-legible) / <alpha-value>)',
          'accent-legible': 'rgb(var(--brand-accent-legible) / <alpha-value>)',
          // The flat pale chip pairing: active nav pill, open account-menu trigger,
          // selected filter chip. chip-text resolves through --brand-legible, NOT
          // --brand-solid: the ramp flips per mode (pale is p50 light / p900 dark; solid
          // is p900 light / p700 dark), so pairing them directly collapses from 8.6:1 in
          // light to 1.6:1 in dark - two adjacent ramp steps, effectively unreadable,
          // while looking perfectly correct in light mode.
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

      // The UI face (Space Grotesk), the brand face (Orbitron - the wordmark and display numerals
      // only) and the code face. The `font-grotesk` / `font-orbitron` class aliases had no call
      // site left and went on 2026-10-14; the landing site's next/font variables of those names
      // are CSS variables the token file reads, not these classes.
      fontFamily: {
        ui:    ['var(--font-ui)'],
        brand: ['var(--font-brand)'],
        mono:  ['var(--font-mono)'],
      },

      // Tighter than Tailwind's own scale: panels, cards, inputs and buttons read as crisp
      // rather than soft-bubbled, and nothing reaches past 14px. `rounded-full` means "this
      // shape is genuinely a circle or a pill" - avatars, status dots, toggles, icon
      // buttons - and nothing else.
      borderRadius: {
        xs:    '2px',
        sm:    '4px',
        md:    '6px',   // buttons, inputs, badges - THE control radius
        lg:    '8px',   // cards, panels, dropdowns - the surface radius
        xl:    '10px',   // the chat composer
        '2xl': '12px',
        '3xl': '14px',   // the desktop splash, as the native launcher draws it
      },

      // All mode-aware via the token file: `shadow-sm` a tooltip, `shadow-card` every floating
      // panel (menus, dialogs, toasts), `shadow-card-lg` the lightbox. ONE name per step: the
      // `shadow-panel` alias of `shadow-card` went on 2026-10-14. No hand-written `shadow-[...]`,
      // and no coloured drop shadows - on a light page they have nowhere to fade to and read as a
      // halo rather than depth.
      boxShadow: {
        sm:        'var(--shadow-sm)',
        card:      'var(--shadow-card)',
        'card-lg': 'var(--shadow-card-lg)',
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
