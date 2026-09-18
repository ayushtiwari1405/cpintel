import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { FileText, ListOrdered } from 'lucide-react'
import { clsx } from 'clsx'

import { CfSessionCard } from '@/components/practice/CfSessionCard'
import { ProblemPicker } from '@/components/practice/ProblemPicker'
import { StatementView } from '@/components/practice/StatementView'
import { SkillBanner, SkillProblemList, NextUpList } from '@/components/practice/SkillContext'
import { EditorPane } from '@/components/workspace/EditorPane'
import { SplitPane } from '@/components/workspace/SplitPane'
import { TabStrip } from '@/components/workspace/TabStrip'

import { useCfSession, useLanguages, useProblem, useSubmitSolution } from '@/hooks/usePractice'
import { useNextUp, useRoadmapNode } from '@/hooks/useRoadmap'
import type { ProblemSummary } from '@/types'

/**
 * Practice, laid out the way a judge lays it out: the problem on the left, the code on the
 * right, one draggable divider between them.
 *
 * The problem list shares the left pane as a tab rather than taking a third column of its own.
 * Picking a problem is something you do once and then stop doing, so the space it was holding
 * belongs to the statement for the rest of the session.
 *
 * <h2>Arriving from the roadmap</h2>
 *
 * The page accepts `?contest=&index=&node=`. That is how the roadmap and the recommendation
 * sheets hand a problem over: they used to link out to codeforces.com in a new tab, which sent
 * the user out of the product at the moment they decided to practise, past the editor, the
 * local runner and the sample console built for it. With `node` present the workspace also
 * knows which skill the problem was suggested for, so it can show progress in that skill and
 * offer the rest of its problems - the question that actually follows solving one.
 */
export default function PracticePage() {
  const [params, setParams] = useSearchParams()
  const [selected, setSelected] = useState<ProblemSummary | null>(null)
  const [languageId, setLanguageId] = useState('')
  const [source, setSource] = useState('')
  const [tab, setTab] = useState<'description' | 'problems'>('problems')

  const nodeKey = params.get('node')
  const { data: skill } = useRoadmapNode(nodeKey)
  const { data: nextUp, isLoading: nextUpLoading } = useNextUp()

  const { data: problem, isLoading: problemLoading } =
    useProblem(selected?.contestId, selected?.index)
  const { data: languages } = useLanguages()
  const { data: session } = useCfSession()
  const submit = useSubmitSolution()

  // Default the language once the list arrives.
  useEffect(() => {
    if (!languageId && languages?.length) setLanguageId(languages[0].id)
  }, [languages, languageId])

  // A link into the workspace names a problem in the URL. Selecting from it rather than from
  // component state means the deep link survives a reload and a back button, which a link
  // handed over from another page has to.
  const linkedContest = params.get('contest')
  const linkedIndex = params.get('index')
  useEffect(() => {
    if (!linkedContest || !linkedIndex) return
    const contestId = Number(linkedContest)
    if (!Number.isFinite(contestId)) return
    if (selected?.contestId === contestId && selected?.index === linkedIndex) return

    // Name and tags are unknown until the statement loads; the editor and statement only need
    // the pair, and the picker fills the rest in when the user browses.
    setSelected({
      contestId,
      index: linkedIndex,
      name: `${contestId}${linkedIndex}`,
      tags: [],
      url: `https://codeforces.com/problemset/problem/${contestId}/${linkedIndex}`,
    })
    submit.reset()
    setTab('description')
    // submit is a fresh object each render; depending on it would re-run this every time.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [linkedContest, linkedIndex])

  const handleSelect = (p: ProblemSummary) => {
    setSelected(p)
    submit.reset()
    // Picking a problem is a request to read it, so move to the statement rather than
    // leaving the list open over the thing that was just chosen.
    setTab('description')

    // Keep the URL honest about what is open, preserving the skill the user came in under so
    // a reload does not silently drop the context the page is showing.
    const next = new URLSearchParams(params)
    next.set('platform', 'CODEFORCES')
    next.set('contest', String(p.contestId))
    next.set('index', p.index)
    setParams(next, { replace: true })
  }

  const leftPane = (
    <div className="flex flex-col flex-1 min-w-0 min-h-0 bg-gray-950">
      <TabStrip
        tabs={[
          { id: 'description', label: 'Description', icon: FileText },
          { id: 'problems', label: 'Problems', icon: ListOrdered },
        ]}
        active={tab}
        onChange={id => setTab(id as 'description' | 'problems')}
      />

      <div className="flex-1 min-h-0 flex flex-col">
        {skill && (
          <div className="px-3 pt-3 flex-shrink-0">
            <SkillBanner node={skill} />
          </div>
        )}

        <div className="flex-1 min-h-0">
          {tab === 'description' ? (
            <div className="h-full px-4 py-3">
              <StatementView problem={problem} isLoading={problemLoading} />
            </div>
          ) : (
            <div className="h-full px-3 py-3 overflow-y-auto space-y-4">
              {skill && (
                <SkillProblemList
                  node={skill}
                  selected={selected ?? undefined}
                  onSelect={handleSelect}
                />
              )}

              {!skill && !selected && (
                <NextUpList
                  nodes={nextUp}
                  isLoading={nextUpLoading}
                  onSelect={handleSelect}
                />
              )}

              <div className={clsx((skill || (!skill && !selected)) && 'pt-1')}>
                <ProblemPicker
                  selected={selected ?? undefined}
                  onSelect={handleSelect}
                  // Arriving from a skill, the search starts inside that skill's difficulty
                  // band. Anything outside it is by definition not practice for this skill.
                  initialMinRating={skill?.minDifficulty}
                  initialMaxRating={skill?.maxDifficulty}
                />
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  )

  return (
    <div className="flex flex-col flex-1 min-h-0">
      {/* Only in the way when there is something to do about it. Once a session is connected
          the card collapses to a line, and that line is not worth a strip of the workspace. */}
      {!session?.connected && (
        <div className="px-4 pt-3 flex-shrink-0">
          <CfSessionCard />
        </div>
      )}

      <SplitPane
        // The app's own top bar already draws a line. A second one directly under it, with
        // nothing between them, reads as a rendering mistake.
        className={clsx('flex-1', !session?.connected && 'mt-3 border-t border-gray-800')}
        storageKey="cpintel.practice.split"
        initial={45}
        min={25}
        max={70}
        first={leftPane}
        second={
          <EditorPane
            languages={languages ?? []}
            languageId={languageId}
            onLanguageChange={setLanguageId}
            source={source}
            onSourceChange={setSource}
            onSubmit={() => {
              if (!selected) return
              submit.mutate({
                contestId: selected.contestId,
                index: selected.index,
                languageId,
                source,
              })
            }}
            submitting={submit.isPending}
            polling={submit.polling}
            verdict={submit.verdict}
            canSubmit={!!session?.connected && !!session?.submitEnabled}
            problemSelected={!!selected}
            samples={problem?.samples ?? []}
            problemKey={selected ? `${selected.contestId}${selected.index}` : undefined}
            contest={selected
              ? { platform: 'CODEFORCES', id: String(selected.contestId) }
              : undefined}
            problemIndex={selected?.index}
            storageKey="cpintel.practice"
            submitLabel="Submit"
          />
        }
      />
    </div>
  )
}
