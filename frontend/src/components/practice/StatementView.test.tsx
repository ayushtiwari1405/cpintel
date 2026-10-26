import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { StatementView } from './StatementView'
import type { ProblemDetail } from '@/types'

/** A judge problem whose statement is a document, as DOMjudge serves it. */
const problem = {
  contestId: 'c1', index: 'A', name: 'Sums', tags: [], samples: [],
  statementPdfUrl: '/compete/domjudge/c1/problems/A/statement.pdf', statementAvailable: true,
} as unknown as ProblemDetail

describe('a judge statement document', () => {
  it('HTML from the judge is shown in a sandbox with no origin and no scripts', () => {
    render(<StatementView problem={problem} pdfUrl="blob:statement" pdfIsPdf={false} />)
    expect(screen.getByTitle('Problem statement')).toHaveAttribute('sandbox', '')
  })

  it('a PDF is not sandboxed, since browsers will not show one there', () => {
    render(<StatementView problem={problem} pdfUrl="blob:statement" pdfIsPdf />)
    expect(screen.getByTitle('Problem statement')).not.toHaveAttribute('sandbox')
  })
})
