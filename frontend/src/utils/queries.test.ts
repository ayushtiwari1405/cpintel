import { describe, expect, it } from 'vitest'
import { QueryClient, QueryObserver } from '@tanstack/react-query'
import { reloadAfterChange } from './queries'
import { gate } from '@/test/server'

describe('reading again after a change', () => {
  it('does not settle for the answer to a request asked before it', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const slow = gate()
    let list = ['old']
    let requests = 0
    const observer = new QueryObserver(client, {
      queryKey: ['admin', 'classrooms'],
      queryFn: async () => {
        const answer = [...list]
        if (requests++ === 0) await slow.wait
        return answer
      },
    })
    const stop = observer.subscribe(() => {})

    // Something is created while the first load is still on its way.
    list = ['old', 'new']
    const reloaded = reloadAfterChange(client, ['admin', 'classrooms'])
    slow.open()
    await reloaded

    expect(client.getQueryData(['admin', 'classrooms'])).toEqual(['old', 'new'])
    expect(requests).toBe(2)
    stop()
  })
})
