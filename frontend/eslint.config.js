// Lint rules for the SPA, in ESLint's flat config (ESLint 10 reads no other kind). The rules are
// the ones the earlier .eslintrc.cjs chose; only the format changed.
import js from '@eslint/js'
import tsPlugin from '@typescript-eslint/eslint-plugin'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import globals from 'globals'

export default [
  {
    // Build output, copied runtimes (public/mathjax), Node build scripts and the tool configs.
    ignores: [
      'dist', 'public', 'scripts', '**/*.cjs',
      'vite.config.ts', 'eslint.config.js',
    ],
  },
  js.configs.recommended,
  // Sets @typescript-eslint/parser for every file, as `parser:` did before.
  ...tsPlugin.configs['flat/recommended'],
  {
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: globals.browser,
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      // The two rules plugin:react-hooks/recommended enabled before. The plugin's current
      // presets add the React Compiler rules as well; those are a separate decision.
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'warn',
      // API payloads are typed loosely in places on purpose (see types/index.ts); `any` there is
      // a decision, not an accident.
      '@typescript-eslint/no-explicit-any': 'off',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
      // Only affects hot reload in development (a file exporting a hook beside a component
      // reloads the whole page instead of patching). Not worth splitting files over.
      'react-refresh/only-export-components': 'off',
    },
  },
  {
    // The connector is a Chrome extension rather than SPA code; expose its extension API globals
    // without weakening the browser rules used by the rest of the frontend. Linted from this
    // config on: `--ext ts,tsx` used to leave these two files out.
    files: ['extension-src/**/*.js'],
    languageOptions: { globals: { ...globals.browser, ...globals.webextensions } },
  },
  {
    // Run by Node: the session tests and the browser smoke suite (whose page.evaluate callbacks
    // run in the page, hence the browser globals as well).
    files: ['tests/**/*.mjs', 'e2e/**/*.mjs'],
    languageOptions: { globals: { ...globals.node, ...globals.browser } },
  },
]
