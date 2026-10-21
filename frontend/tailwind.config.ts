import type { Config } from 'tailwindcss'
import plugin from 'tailwindcss/plugin'
import { V3_PALETTES } from './tailwind.palette'

/*
 * Theme-aware palettes.
 *
 * The UI was written dark-first with plain utilities (bg-gray-900, text-indigo-400, ...), so
 * rather than doubling every class with a dark: variant, each palette shade resolves to a CSS
 * variable. `.dark` maps shades to themselves; light mode points them at the mirrored shade,
 * so a dark surface becomes a light one and light text becomes dark text. Accent 500-700 stay
 * put in light mode so solid buttons (bg-indigo-600 text-white) keep their contrast.
 */
type Shade = '50' | '100' | '200' | '300' | '400' | '500' | '600' | '700' | '800' | '900' | '950'
const SHADES: Shade[] = ['50', '100', '200', '300', '400', '500', '600', '700', '800', '900', '950']

const ACCENTS = [
  'red', 'orange', 'amber', 'yellow', 'lime', 'green', 'emerald', 'teal', 'cyan',
  'sky', 'blue', 'indigo', 'violet', 'purple', 'fuchsia', 'pink', 'rose',
] as const

/*
 * CPIntel's own accent: a teal that reads as "active" on graphite without competing with the
 * status colours. Success stays Tailwind's green, so an accepted verdict and a selected tab
 * never look alike. 600 carries white text at 4.8:1 (solid buttons); 300-400 are for text and
 * outlines on dark surfaces. Light mode mirrors it like every other accent.
 */
const ACCENT: Record<Shade, string> = {
  50:  '#ecfefb', 100: '#cffcf5', 200: '#a0f6ea', 300: '#63ead9', 400: '#2fd6c3',
  500: '#0e8d82', 600: '#0b7f75', 700: '#0a665e', 800: '#0b524c', 900: '#0c4440',
  950: '#032826',
}

const ACCENT_LIGHT: Record<Shade, Shade> = {
  50: '950', 100: '950', 200: '900', 300: '800', 400: '700', 500: '500',
  600: '600', 700: '700', 800: '200', 900: '100', 950: '50',
}

// Neutrals are hand-tuned rather than mirrored: 950 is the page, 900 a panel on it, 850 a
// raised or hovered row, 800 a hairline border or field, 700 a stronger border. Graphite with
// a faint cool cast rather than blue-grey, so the teal accent and the status colours carry the
// colour. Text: 50-200 primary, 400 secondary, 500 muted (4.6:1 on 950), 600 disabled/icons.
// Light mode puts the muted shades (500-700) a little darker than a strict mirror would, so
// secondary text stays readable on white.
const GRAY_DARK: Record<string, string> = {
  50: '#f3f6f7', 100: '#e6eaec', 200: '#cfd5d8', 300: '#b2bac0', 400: '#939ca3',
  500: '#80898f', 600: '#606a70', 700: '#30383d', 800: '#21272b', 850: '#181d20',
  900: '#121618', 950: '#0b0e10',
}
const GRAY_LIGHT: Record<string, string> = {
  50: '#0b0e10', 100: '#14191c', 200: '#21282c', 300: '#343c41', 400: '#4a545a',
  500: '#626c72', 600: '#7f898f', 700: '#b9c1c6', 800: '#e1e6e8', 850: '#edf0f1',
  900: '#ffffff', 950: '#f4f6f7',
}

const rgb = (hex: string) => {
  const n = parseInt(hex.slice(1), 16)
  return `${(n >> 16) & 255} ${(n >> 8) & 255} ${n & 255}`
}

function paletteVars(mode: 'light' | 'dark') {
  const vars: Record<string, string> = {}
  for (const [shade, hex] of Object.entries(mode === 'dark' ? GRAY_DARK : GRAY_LIGHT)) {
    vars[`--c-gray-${shade}`] = rgb(hex)
  }
  const palettes: [string, Record<Shade, string>][] = [
    ...ACCENTS.map(name => [name, V3_PALETTES[name]] as [string, Record<Shade, string>]),
    ['accent', ACCENT],
  ]
  for (const [name, palette] of palettes) {
    for (const shade of SHADES) {
      vars[`--c-${name}-${shade}`] = rgb(palette[mode === 'dark' ? shade : ACCENT_LIGHT[shade]])
    }
  }
  return vars
}

const themedPalette = (name: string, shades: string[]) =>
  Object.fromEntries(shades.map(s => [s, `rgb(var(--c-${name}-${s}) / <alpha-value>)`]))

const themedColors = {
  gray: themedPalette('gray', Object.keys(GRAY_DARK)),
  ...Object.fromEntries(ACCENTS.map(name => [name, themedPalette(name, SHADES)])),
  accent: themedPalette('accent', SHADES),
}

const config: Config = {
  darkMode: 'class',
  content: [
    './index.html',
    './src/**/*.{ts,tsx}',
  ],
  theme: {
    extend: {
      colors: themedColors,
      fontFamily: {
        // Inter and JetBrains Mono come from Google Fonts when the network allows; on a closed
        // lab network the system stacks below take over without a layout jump.
        sans: ['Inter', 'ui-sans-serif', 'system-ui', '-apple-system', 'Segoe UI', 'Roboto',
               'sans-serif'],
        mono: ['JetBrains Mono', 'ui-monospace', 'SFMono-Regular', 'Menlo', 'Consolas',
               'Liberation Mono', 'monospace'],
      },
      // Tighter than Tailwind's defaults: a tool for reading data, not a marketing page. The
      // names stay, so every existing rounded-* class follows.
      borderRadius: {
        md:   '0.3125rem',
        lg:   '0.375rem',
        xl:   '0.5rem',
        '2xl': '0.625rem',
      },
      animation: {
        'fade-in':    'fadeIn 0.2s ease-in-out',
        'slide-up':   'slideUp 0.3s ease-out',
        'pulse-slow': 'pulse 3s cubic-bezier(0.4, 0, 0.6, 1) infinite',
      },
      keyframes: {
        fadeIn:  { from: { opacity: '0' },                        to: { opacity: '1' } },
        slideUp: { from: { transform: 'translateY(8px)', opacity: '0' }, to: { transform: 'translateY(0)', opacity: '1' } },
        loadbar: { from: { transform: 'translateX(-100%)' }, to: { transform: 'translateX(300%)' } },
      },
    },
  },
  plugins: [
    plugin(({ addBase }) => {
      addBase({
        ':root': paletteVars('light'),
        '.dark': paletteVars('dark'),
      })
    }),
  ],
}

export default config
