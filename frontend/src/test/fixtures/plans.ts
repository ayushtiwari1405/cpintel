import type { PlanItem, PlanProblem, PracticePlan, RevisionItem } from '@/types'

/**
 * Practice plans as the recommendation endpoints return them (RecommendationPayload and
 * RevisionItem on the backend). Problem and skill names are real Codeforces and skill-tree ones;
 * nothing here identifies a person.
 */
export function problem(contestId: number, index: string, rating: number, role: PlanProblem['role'],
                        solved = false, name = `Problem ${contestId}${index}`): PlanProblem {
  return {
    contestId, index, name, rating, solved, fit: 70, role,
    practicePath: `/practice?platform=CODEFORCES&contest=${contestId}&index=${index}&node=dp-basics`,
    judgeUrl: `https://codeforces.com/problemset/problem/${contestId}/${index}`,
  }
}

export function item(nodeKey: string, title: string, overrides: Partial<PlanItem> = {}): PlanItem {
  return {
    nodeKey, title, track: 'Dynamic Programming', topic: 'Dynamic Programming',
    blurb: 'States, transitions and the order to fill them in.',
    masteryScore: 25, decayScore: 0, targetRating: 1400,
    reason: 'Weak area - mastery 25% over 6 attempted problems', reasonCode: 'WEAK_TOPIC',
    priority: null, basis: 'MEASURED', session: null,
    problems: [
      problem(1900, 'B', 1100, 'WARM_UP'),
      problem(1901, 'C', 1200, 'CORE'),
      problem(1902, 'C', 1300, 'CORE'),
      problem(1903, 'D', 1400, 'STRETCH'),
    ],
    ...overrides,
  }
}

export function plan(type: PracticePlan['type'], items: PlanItem[], overrides: Partial<PracticePlan> = {}): PracticePlan {
  const all = items.flatMap(i => i.problems)
  return {
    type, items,
    generatedAt: '2026-10-22T06:00:00Z',
    expiresAt: '2099-10-23T06:00:00Z',
    summary: { basis: 'MEASURED', notes: [] },
    progress: { solved: all.filter(p => p.solved).length, total: all.length },
    ...overrides,
  }
}

export function revision(revisionId: number, title: string, status: RevisionItem['status'],
                         overrides: Partial<RevisionItem> = {}): RevisionItem {
  return {
    revisionId, nodeKey: `node-${revisionId}`, title, track: 'Graphs', topic: 'Graphs',
    nextRevisionAt: status === 'UPCOMING' ? '2099-01-01T00:00:00Z' : '2026-10-20T00:00:00Z',
    revisionPriority: 40, decayScore: 40, intervalDays: 5, repetitionCount: 0, easeFactor: 2.1,
    lastRevisedAt: null, practicePath: `/practice?node=node-${revisionId}`,
    status, reason: status === 'DUE' ? 'About 40% of this has faded - last practised 70 days ago' : 'Next revision in 5 days',
    lastPracticedAt: '2026-08-13T00:00:00Z',
    queuePosition: status === 'QUEUED' ? 1 : null,
    problems: status === 'DUE'
      ? [problem(1500, 'A', 1200, 'RESOLVE', true, 'Old solve'), problem(1600, 'B', 1300, 'CORE')]
      : [],
    ...overrides,
  }
}
