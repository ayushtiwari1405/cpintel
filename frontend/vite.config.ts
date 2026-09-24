import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { '@': path.resolve(import.meta.dirname, './src') },
  },
  server: {
    port: 5173,
    proxy: {
      // Deliberately the unversioned prefix: it covers /api/v1 today and whatever comes
      // after it, so the dev proxy does not need touching every time the API is versioned.
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    // Monaco alone is ~2.7 MB minified, in a chunk of its own that browsers cache across
    // releases. The warning would fire on every build for a size that is expected.
    chunkSizeWarningLimit: 3000,
    rollupOptions: {
      output: {
        // A function: the bundler behind Vite 8 no longer takes the object form.
        manualChunks(id: string) {
          if (!id.includes('node_modules')) return undefined
          // Monaco is by far the largest dependency and changes only when it is upgraded, so
          // it gets a chunk of its own that survives every other change in the cache.
          if (id.includes('/monaco-editor/')) return 'monaco'
          if (id.includes('/recharts/') || id.includes('/d3-')) return 'charts'
          if (id.includes('/@tanstack/')) return 'query'
          if (/\/node_modules\/(react|react-dom|react-router|react-router-dom|scheduler)\//
            .test(id)) return 'vendor'
          return undefined
        },
      },
    },
  },
  define: {
    __IS_ELECTRON__: JSON.stringify(process.env.ELECTRON === 'true'),
  },
})
