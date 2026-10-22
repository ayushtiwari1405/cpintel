import { defineConfig, mergeConfig } from 'vitest/config'
import viteConfig from './vite.config.ts'

// Component and page tests: the app's own Vite config (aliases, defines, plugins) in jsdom.
// The axios/session regression tests stay on Node's runner (tests/*.test.mjs); `npm test`
// runs both.
export default mergeConfig(viteConfig, defineConfig({
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['./src/test/setup.ts'],
    // Each test starts from nothing: spies restored, stubbed globals and env put back.
    restoreMocks: true,
    clearMocks: true,
    unstubGlobals: true,
    unstubEnvs: true,
    // Pages are lazy-loaded; a cold first import under load can take a moment.
    testTimeout: 10_000,
  },
}))
