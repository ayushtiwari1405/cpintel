import { useQuery } from '@tanstack/react-query'
import { desktopApi } from '@/api/desktopApi'

/**
 * The newest desktop release. The server caches GitHub's answer for ten minutes, so asking
 * more often than that would only repeat it.
 */
export function useDesktopRelease(enabled = true) {
  return useQuery({
    queryKey: ['desktop', 'release'],
    enabled,
    queryFn: desktopApi.release,
    staleTime: 1000 * 60 * 10,
    refetchInterval: 1000 * 60 * 30,
  })
}
