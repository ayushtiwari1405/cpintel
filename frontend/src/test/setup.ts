import '@testing-library/jest-dom/vitest'
import { afterEach } from 'vitest'
import { cleanup } from '@testing-library/react'
import { useAuthStore } from '@/store/authStore'
import { apiClient } from '@/api/client'
import { useToast } from '@/components/common/Toaster'

const defaultAdapter = apiClient.defaults.adapter

/**
 * jsdom has no media queries. This answers width queries from `window.innerWidth` (jsdom's
 * default is 1024, a wide screen; a test sets it to try a phone) and everything else as false,
 * so layout that depends on the screen is decided the same way on every run.
 */
function matchMedia(query: string): MediaQueryList {
  const min = /min-width:\s*(\d+)px/.exec(query)
  const max = /max-width:\s*(\d+)px/.exec(query)
  const width = window.innerWidth
  const matches = (min || max)
    ? (!min || width >= Number(min[1])) && (!max || width <= Number(max[1]))
    : false
  return {
    matches, media: query, onchange: null,
    addEventListener: () => {}, removeEventListener: () => {},
    addListener: () => {}, removeListener: () => {}, dispatchEvent: () => false,
  }
}
window.matchMedia = matchMedia

afterEach(() => {
  cleanup()
  // The auth store is a module singleton persisted to localStorage: put both back so no test
  // inherits another's session.
  useAuthStore.getState().logout()
  localStorage.clear()
  // So are the toasts, and one stays up for four seconds: without this a test sees the last
  // test's "Revision marked complete" or error alert as if it were its own.
  useToast.setState({ toasts: [] })
  apiClient.defaults.adapter = defaultAdapter
})
