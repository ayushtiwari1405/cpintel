import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { Tabs, panelProps } from './Tabs'

type Key = 'today' | 'week' | 'revision' | 'contest'

function Harness({ initial = 'today' as Key }) {
  const [value, setValue] = useState<Key>(initial)
  return (
    <>
      <Tabs id="plan" label="Practice plans" value={value} onChange={setValue} tabs={[
        { key: 'today', label: 'Today', badge: '1/6' },
        { key: 'week', label: 'This week' },
        { key: 'revision', label: 'Revision', badge: 2 },
        { key: 'contest', label: 'Contest prep' },
      ]} />
      <div {...panelProps('plan', value)}>{`Panel ${value}`}</div>
    </>
  )
}

const tab = (name: RegExp | string) => screen.getByRole('tab', { name })

describe('tabs, as the ARIA tabs pattern', () => {
  it('is one labelled tab list with only the selected tab in the tab order', () => {
    render(<Harness />)
    expect(screen.getByRole('tablist', { name: 'Practice plans' })).toBeInTheDocument()
    expect(tab(/Today/)).toHaveAttribute('aria-selected', 'true')
    expect(tab(/Today/)).toHaveAttribute('tabindex', '0')
    for (const other of [tab('This week'), tab(/Revision/), tab('Contest prep')]) {
      expect(other).toHaveAttribute('aria-selected', 'false')
      expect(other).toHaveAttribute('tabindex', '-1')
    }
  })

  it('ties each tab to its panel', () => {
    render(<Harness initial="week" />)
    const panel = screen.getByRole('tabpanel')
    expect(panel).toHaveAccessibleName('This week')
    expect(tab('This week')).toHaveAttribute('aria-controls', panel.id)
    expect(panel).toHaveTextContent('Panel week')
  })

  it('arrow keys move selection and focus along the row, wrapping at the ends', async () => {
    render(<Harness />)
    await userEvent.tab()
    expect(tab(/Today/)).toHaveFocus()

    await userEvent.keyboard('{ArrowRight}')
    expect(tab('This week')).toHaveFocus()
    expect(screen.getByRole('tabpanel')).toHaveTextContent('Panel week')

    await userEvent.keyboard('{ArrowLeft}{ArrowLeft}')
    expect(tab('Contest prep')).toHaveFocus()
    expect(tab('Contest prep')).toHaveAttribute('aria-selected', 'true')

    await userEvent.keyboard('{ArrowRight}')
    expect(tab(/Today/)).toHaveFocus()
  })

  it('Home and End jump to the first and last tab', async () => {
    render(<Harness initial="week" />)
    tab('This week').focus()
    await userEvent.keyboard('{End}')
    expect(tab('Contest prep')).toHaveFocus()
    await userEvent.keyboard('{Home}')
    expect(tab(/Today/)).toHaveFocus()
    expect(screen.getByRole('tabpanel')).toHaveTextContent('Panel today')
  })

  it('Tab leaves the row for the panel instead of walking every tab', async () => {
    render(<Harness />)
    await userEvent.tab()
    await userEvent.tab()
    expect(screen.getByRole('tabpanel')).toHaveFocus()
  })

  it('shows each badge after its label', () => {
    render(<Harness />)
    expect(tab(/Today/)).toHaveTextContent('Today1/6')
    expect(tab(/Revision/)).toHaveTextContent('Revision2')
  })
})
