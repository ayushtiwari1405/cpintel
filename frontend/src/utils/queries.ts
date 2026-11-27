import type { QueryClient, QueryKey } from '@tanstack/react-query'

/**
 * Reads again everything under `queryKey` after a change.
 *
 * Invalidating alone is not enough on a slow connection: a query still waiting for its first
 * answer keeps the request it already has in flight, which was asked before the change and may
 * not include it. Those are cancelled first, so a fresh request is made.
 */
export async function reloadAfterChange(client: QueryClient, queryKey: QueryKey) {
  await client.cancelQueries({ queryKey, predicate: query => query.state.data === undefined })
  await client.invalidateQueries({ queryKey })
}
