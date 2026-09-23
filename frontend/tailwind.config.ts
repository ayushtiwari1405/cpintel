import type { Config } from 'tailwindcss'
import defaultColors from 'tailwindcss/colors'
import plugin from 'tailwindcss/plugin'

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

const ACCENT_LIGHT: Record<Shade, Shade> = {
  50: '950', 100: '950', 200: '900', 300: '800', 400: '700', 500: '500',
  600: '600', 700: '700', 800: '200', 900: '100', 950: '50',
}

// Neutrals are hand-tuned rather than mirrored: 900 is the card surface, 950 the page behind
// it, and the muted text shades (500-700) sit a little darker than a strict mirror would put
// them so secondary text stays readable on white.
const GRAY_DARK: Record<string, string> = {
  ...(defaultColors.gray as Record<string, string>),
  850: '#182030',
}
const GRAY_LIGHT: Record<string, string> = {
  50: '#030712', 100: '#111827', 200: '#1f2937', 300: '#374151', 400: '#4b5563',
  500: '#6b7280', 600: '#858c98', 700: '#aeb4bd', 800: '#e5e7eb', 850: '#eef0f3',
  900: '#ffffff', 950: '#f4f5f7',
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
  for (const name of ACCENTS) {
    const palette = defaultColors[name] as Record<Shade, string>
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
}

const config: Config = {
  darkMode: 'class',
  content: [
    './index.html',
    './src/**/*.{ts,tsx}',
  ],
  theme: {
    extend: {
      colors: {
        ...themedColors,
        brand: {
          50:  '#eef2ff',
          100: '#e0e7ff',
          200: '#c7d2fe',
          400: '#818cf8',
          500: '#6366f1',
          600: '#4f46e5',
          700: '#4338ca',
          800: '#3730a3',
          900: '#312e81',
        },
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
        mono: ['JetBrains Mono', 'Fira Code', 'monospace'],
      },
      animation: {
        'fade-in':    'fadeIn 0.2s ease-in-out',
        'slide-up':   'slideUp 0.3s ease-out',
        'pulse-slow': 'pulse 3s cubic-bezier(0.4, 0, 0.6, 1) infinite',
      },
      keyframes: {
        fadeIn:  { from: { opacity: '0' },                        to: { opacity: '1' } },
        slideUp: { from: { transform: 'translateY(8px)', opacity: '0' }, to: { transform: 'translateY(0)', opacity: '1' } },
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
