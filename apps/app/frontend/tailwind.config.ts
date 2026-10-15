import type { Config } from 'tailwindcss'
import proxymaPreset from './src/styles/proxyma/tailwind-preset'

// The type scale, radius scale, elevation scale and every colour family live in the shared
// preset (generated from proxyma-design/ui-kit). Only `content` and the one animation
// nothing else has are genuinely per-repo.
export default {
  presets: [proxymaPreset],
  // ee/frontend holds Proxyma Enterprise's pages (closed source, private repository only): their
  // classes have to be generated too. A tree without ee/ matches nothing there, which is fine.
  content: ['./index.html', './src/**/*.{ts,tsx}', '../../../ee/frontend/src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      // The chat composer's caret. Product-only - neither console nor the marketing site
      // has a streaming text surface, so it does not belong in the shared preset.
      keyframes: {
        blink: {
          '0%, 49%':   { opacity: '1' },
          '50%, 100%': { opacity: '0' },
        },
        // 'agent-working' moved to the shared preset on 2026-10-07: the site's chat scenes draw it too.
      },
      animation: {
        blink: 'blink 1s step-start infinite',
      },
    },
  },
} satisfies Config
