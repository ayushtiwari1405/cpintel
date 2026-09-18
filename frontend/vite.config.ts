import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { '@': path.resolve(__dirname, './src') },
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
    rollupOptions: {
      output: {
        manualChunks: {
          vendor:  ['react', 'react-dom', 'react-router-dom'],
          query:   ['@tanstack/react-query'],
          charts:  ['recharts'],
          // Monaco is by far the largest dependency and changes only when it is upgraded.
          // Kept out of the SubmitPanel chunk so editing that component does not invalidate
          // ~700 kB of gzipped editor for everyone on the next load.
          monaco:  ['monaco-editor/editor/editor.api.js'],
        },
      },
    },
  },
  define: {
    __IS_ELECTRON__: JSON.stringify(process.env.ELECTRON === 'true'),
  },
})
