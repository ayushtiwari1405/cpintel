import { Pill } from './AdminUi'
import type { LabPlatform } from '@/types'

const LOOK: Record<LabPlatform, { label: string; tone: 'accent' | 'amber' | 'green' }> = {
  CODEFORCES: { label: 'Codeforces', tone: 'accent' },
  LEETCODE: { label: 'LeetCode', tone: 'amber' },
  CSES: { label: 'CSES', tone: 'green' },
}

/** Which site a lab problem is on, the same everywhere it is shown. */
export function PlatformPill({ platform }: { platform: LabPlatform }) {
  const look = LOOK[platform]
  return <Pill tone={look.tone}>{look.label}</Pill>
}
