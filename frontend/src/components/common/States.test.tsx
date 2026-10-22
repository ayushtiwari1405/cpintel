import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { EmptyState, ErrorState, LoadingState, errorMessage } from './States'

describe('shared page states', () => {
  it('an error shows the server\'s own message and retries on request', async () => {
    const retry = vi.fn()
    const failure = { response: { data: { message: 'Codeforces is not answering' } }, message: 'Request failed' }
    render(<ErrorState title="Could not load the weekly plan" error={failure} onRetry={retry} />)

    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('Could not load the weekly plan')
    expect(alert).toHaveTextContent('Codeforces is not answering')
    expect(alert).not.toHaveTextContent('Request failed')

    await userEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(retry).toHaveBeenCalledTimes(1)
  })

  it('an error without a retry offers no button', () => {
    render(<ErrorState error={new Error('boom')} />)
    expect(screen.getByRole('alert')).toHaveTextContent('boom')
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('falls back from the server message to the error message to a default', () => {
    expect(errorMessage({ response: { data: { message: 'from server' } }, message: 'x' })).toBe('from server')
    expect(errorMessage(new Error('plain'))).toBe('plain')
    expect(errorMessage(null, 'nothing known')).toBe('nothing known')
  })

  it('loading is announced as a status with its label', () => {
    render(<LoadingState label="Building today's sheet…" />)
    expect(screen.getByRole('status')).toHaveTextContent("Building today's sheet…")
  })

  it('an empty state says what is missing and offers the next step', () => {
    render(<EmptyState title="Nothing to revise" action={<button type="button">Sync now</button>}>
      Skills appear once they fade.
    </EmptyState>)
    expect(screen.getByText('Nothing to revise')).toBeInTheDocument()
    expect(screen.getByText('Skills appear once they fade.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sync now' })).toBeInTheDocument()
  })
})
