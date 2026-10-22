// Deterministic stand-ins for Codeforces, shaped like its API: the problem set, the contest
// list, and a student's submission history built from them. The tags are real Codeforces tags,
// so the backend files the problems under its skill tree exactly as it would real ones; the
// problems themselves are invented.

const TAGS = [
  'implementation', 'math', 'greedy', 'dp', 'brute force', 'constructive algorithms', 'sortings',
  'binary search', 'number theory', 'strings', 'graphs', 'dfs and similar', 'trees',
  'data structures', 'two pointers', 'combinatorics', 'bitmasks', 'dsu', 'shortest paths', 'hashing',
]
const INDEXES = ['A', 'B', 'C', 'D', 'E', 'F']

/** Two problems for every tag at every rating from 800 to 2400, each with a second tag. */
export function problems() {
  const out = []
  TAGS.forEach((tag, t) => {
    for (let r = 800; r <= 2400; r += 100) {
      for (let k = 0; k < 2; k++) {
        const step = (r - 800) / 100
        const contestId = 1000 + t * 40 + step * 2 + k
        const index = INDEXES[(step + k) % INDEXES.length]
        // Every third name is as long as real ones get, which is what pushes a careless layout
        // off a phone screen.
        const title = `${tag[0].toUpperCase()}${tag.slice(1)} ${r} #${k + 1}`
        const name = (step + k) % 3 === 0
          ? `${title}: Counting Arrangements Under Strange Constraints (Hard Version)`
          : title
        out.push({
          contestId, index, name, type: 'PROGRAMMING',
          rating: r, tags: [tag, TAGS[(t + 1 + k) % TAGS.length]],
        })
      }
    }
  })
  return out
}

export function problemset() {
  const list = problems()
  return {
    status: 'OK',
    result: {
      problems: list,
      problemStatistics: list.map(p => ({ contestId: p.contestId, index: p.index, solvedCount: 1000 })),
    },
  }
}

/** One finished round and one rated Div. 2 round two days from `now`. */
export function contestList(now) {
  const inTwoDays = Math.floor(now / 1000) + 2 * 86400
  return {
    status: 'OK',
    result: [
      { id: 2999, name: 'Codeforces Round 999 (Div. 2)', type: 'CF', phase: 'BEFORE', frozen: false,
        durationSeconds: 7200, startTimeSeconds: inTwoDays, relativeTimeSeconds: -172800 },
      { id: 2990, name: 'Codeforces Round 990 (Div. 2)', type: 'CF', phase: 'FINISHED', frozen: false,
        durationSeconds: 7200, startTimeSeconds: inTwoDays - 30 * 86400, relativeTimeSeconds: 2592000 },
    ],
  }
}

/**
 * A student's submissions, as the sync would store them in Mongo's cf_submissions: steady recent
 * work in a few skills, older work that has since faded, and a weak skill tried and mostly failed.
 *
 * @param userId the student's id
 * @param now    milliseconds; dates are relative to it so decay is the same on every run
 */
export function submissions(userId, now) {
  const day = 86_400_000
  const byTag = tag => problems().filter(p => p.tags[0] === tag)
  const plan = [
    // tag, ratings, solved?, days ago
    ['implementation', [800, 900, 1000, 1100, 1200], true, 3],
    ['greedy', [800, 900, 1000, 1100, 1200, 1300], true, 2],
    ['math', [800, 900, 1000], true, 10],
    ['dp', [1000, 1100, 1200, 1300], true, 120],
    ['sortings', [800, 900, 1000, 1100], true, 95],
    ['graphs', [1200, 1300, 1400, 1500], false, 20],
  ]
  const out = []
  let id = 50_000_000
  for (const [tag, ratings, solved, ago] of plan) {
    ratings.forEach((rating, i) => {
      const p = byTag(tag).find(q => q.rating === rating)
      const at = now - ago * day - i * 3_600_000
      const base = {
        userId, contestId: p.contestId, problemIndex: p.index, problemName: p.name, problemRating: p.rating,
        tags: p.tags, language: 'GNU C++20', participantType: 'PRACTICE', normalized: true,
      }
      out.push({ ...base, cfSubmissionId: id++, verdict: 'WRONG_ANSWER', submittedAt: new Date(at).toISOString() })
      if (solved) out.push({ ...base, cfSubmissionId: id++, verdict: 'OK', submittedAt: new Date(at + 1_200_000).toISOString() })
    })
  }
  return out
}
