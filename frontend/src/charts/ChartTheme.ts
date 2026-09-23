export const CHART_COLORS = {
  indigo:  '#6366f1',
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
  LEETCODE:   '#f59e0b',
  CODECHEF:   '#f97316',
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
  borderRadius: '8px',
  color: gray(50),
  fontSize: 12,
}

export const axisStyle = {
  tick: { fill: CHROME.mutedTick, fontSize: 11 },
  axisLine: { stroke: CHROME.grid },
  tickLine: { stroke: CHROME.grid },
}
