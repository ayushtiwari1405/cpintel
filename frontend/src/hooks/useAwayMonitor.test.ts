import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { useAwayMonitor } from './useAwayMonitor'
import { whileCfWindowOpen } from '@/utils/cfWindowExcuse'

describe('time away during an examination on the website', () => {
  afterEach(() => vi.restoreAllMocks())

  it('counts leaving the window', () => {
    vi.spyOn(document, 'hasFocus').mockReturnValue(true)
    const { result } = renderHook(() => useAwayMonitor(true))
    act(() => { window.dispatchEvent(new Event('blur')) })
    expect(result.current?.focusLosses).toBe(1)
    expect(result.current?.away).toBe(true)
  })

  it('does not count the Codeforces window CPIntel opened, until it closes elsewhere', async () => {
    const focused = vi.spyOn(document, 'hasFocus').mockReturnValue(true)
    const { result } = renderHook(() => useAwayMonitor(true))

    let close!: () => void
    let pending!: Promise<void>
    act(() => { pending = whileCfWindowOpen(() => new Promise<void>(r => { close = r })) })
    act(() => { window.dispatchEvent(new Event('blur')) })
    expect(result.current?.focusLosses).toBe(0)

    // Closed with the candidate back on the page: still nothing.
    await act(async () => { close(); await pending })
    expect(result.current?.focusLosses).toBe(0)

    // Closed while the candidate is somewhere else: away from then on.
    act(() => { pending = whileCfWindowOpen(() => new Promise<void>(r => { close = r })) })
    focused.mockReturnValue(false)
    await act(async () => { close(); await pending })
    expect(result.current?.focusLosses).toBe(1)
  })
})
