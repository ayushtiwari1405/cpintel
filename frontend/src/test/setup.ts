import '@testing-library/jest-dom/vitest'
import { afterEach } from 'vitest'
import { cleanup } from '@testing-library/react'
import { useAuthStore } from '@/store/authStore'
import { apiClient } from '@/api/client'

const defaultAdapter = apiClient.defaults.adapter

afterEach(() => {
  cleanup()
  // The auth store is a module singleton persisted to localStorage: put both back so no test
  // inherits another's session.
  useAuthStore.getState().logout()
  localStorage.clear()
  apiClient.defaults.adapter = defaultAdapter
})
