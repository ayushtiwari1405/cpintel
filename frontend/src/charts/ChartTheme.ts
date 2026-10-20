// The series colour that means "you" or "this metric": CPIntel's accent, through the same
// palette variable the rest of the UI uses, so it follows the light/dark theme.
export const ACCENT = 'rgb(var(--c-accent-400))'

/**
 * Recharts animates in script, so the stylesheet's reduced-motion rule cannot reach it. Charts
 * pass this as isAnimationActive: drawn at once for anyone who has asked for less motion.
 */
export const ANIMATE = typeof window === 'undefined'
  || !window.matchMedia?.('(prefers-reduced-motion: reduce)').matches

export const CHART_COLORS = {
  accent:  ACCENT,
  teal:    '#14b8a6',
  amber:   '#f59e0b',
  rose:    '#f43f5e',
  green:   '#22c55e',
  blue:    '#3b82f6',
  purple:  '#a855f7',
  gray:    '#6b7280',
}

export const MASTERY_COLORS: Record<string, string> = {
  STRONG:   '#22c55e',
  MODERATE: '#f59e0b',
  WEAK:     '#f43f5e',
  UNTOUCHED:'#6b7280',
}

export const PLATFORM_COLORS: Record<string, string> = {
  CODEFORCES: '#3b82f6',
}

// Chrome colours follow the light/dark theme through the palette variables defined in
// tailwind.config.ts, so charts restyle on toggle without re-rendering.
const gray = (shade: number) => `rgb(var(--c-gray-${shade}))`

export const CHROME = {
  grid:      gray(800),
  label:     gray(400),
  mutedTick: gray(500),
}

export const tooltipStyle = {
  backgroundColor: gray(900),
  border: `1px solid ${gray(800)}`,
  borderRadius: '6px',
  color: gray(50),
  fontSize: 12,
}

export const axisStyle = {
  tick: { fill: CHROME.mutedTick, fontSize: 11 },
  axisLine: { stroke: CHROME.grid },
  tickLine: { stroke: CHROME.grid },
}
