// Lint rules for the SPA. `npm run lint` was wired up without a configuration, so it could not
// run at all; this is the configuration its script and dependencies were chosen for.
module.exports = {
  root: true,
  env: { browser: true, es2022: true },
  parser: '@typescript-eslint/parser',
  parserOptions: { ecmaVersion: 'latest', sourceType: 'module' },
  plugins: ['@typescript-eslint', 'react-hooks', 'react-refresh'],
  extends: [
    'eslint:recommended',
    'plugin:@typescript-eslint/recommended',
    'plugin:react-hooks/recommended',
  ],
  ignorePatterns: ['dist', 'node_modules', 'public', 'scripts', '*.cjs', 'vite.config.ts'],
  rules: {
    // API payloads are typed loosely in places on purpose (see types/index.ts); `any` there is a
    // decision, not an accident.
    '@typescript-eslint/no-explicit-any': 'off',
    '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
    // Only affects hot reload in development (a file exporting a hook beside a component
    // reloads the whole page instead of patching). Not worth splitting files over.
    'react-refresh/only-export-components': 'off',
  },
}
