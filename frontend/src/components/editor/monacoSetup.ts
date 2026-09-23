/**
 * Monaco, wired to the copy in node_modules.
 *
 * @monaco-editor/react fetches Monaco from a CDN (jsdelivr) by default. That is the same
 * dependency that left every Codeforces statement rendering as raw $$$ when an ad blocker
 * blocked it — and unlike a statement, an editor that fails to load leaves the page with no
 * way to type at all. It also has to work in the packaged desktop build, which may have no
 * network. So Monaco is bundled and `loader.config` is pointed at it.
 *
 * Importing this module for its side effects is enough; it must run before the first
 * <Editor> renders, which is why CodeEditor imports it directly.
 */
import { loader } from '@monaco-editor/react'
// editor.api.js, not the package root. The root registers every language Monaco ships
// (abap, solidity, powerquery...) and pulls in the TypeScript language service with it,
// which put a 4 MB chunk in the bundle for an app that edits C++. This entry is the editor
// alone; the languages below are registered explicitly and their grammars load on demand.
import * as monaco from 'monaco-editor/editor/editor.api.js'

// Only the languages the Codeforces dropdown can map to — see languages.ts. Each of these
// registers a lazy loader, so the grammar itself is fetched the first time it is needed.
import 'monaco-editor/languages/definitions/cpp/register.js'
import 'monaco-editor/languages/definitions/python/register.js'
import 'monaco-editor/languages/definitions/java/register.js'
import 'monaco-editor/languages/definitions/sql/register.js'
import 'monaco-editor/languages/definitions/csharp/register.js'
import 'monaco-editor/languages/definitions/rust/register.js'
import 'monaco-editor/languages/definitions/go/register.js'
import 'monaco-editor/languages/definitions/javascript/register.js'
import 'monaco-editor/languages/definitions/kotlin/register.js'
import 'monaco-editor/languages/definitions/ruby/register.js'
import 'monaco-editor/languages/definitions/php/register.js'
import 'monaco-editor/languages/definitions/perl/register.js'
import 'monaco-editor/languages/definitions/scala/register.js'
import 'monaco-editor/languages/definitions/pascal/register.js'

// monaco-editor 0.5x declares an exports map that rewrites "./*" to "./esm/vs/*.js", so
// the specifier omits the esm/vs prefix and keeps the .js. Writing the on-disk path
// (monaco-editor/esm/vs/...) does not resolve, despite the file being exactly there.
import editorWorker from 'monaco-editor/editor/editor.worker.js?worker'

// Monaco runs tokenisation and diffing off the main thread. Without a worker factory it
// falls back to loading workers from a URL, which fails under Vite's module graph.
// Only the plain editor worker is needed: the languages here (C++, Python, Java, SQL) are
// syntax-highlight-only, with none of the language services that TS or JSON would pull in.
window.MonacoEnvironment = {
  getWorker: () => new editorWorker(),
}

loader.config({ monaco })

/** CPIntel's palettes, so the editor matches the surrounding cards in either theme. */
export const CPINTEL_DARK = 'cpintel-dark'
export const CPINTEL_LIGHT = 'cpintel-light'

monaco.editor.defineTheme(CPINTEL_DARK, {
  base: 'vs-dark',
  inherit: true,
  rules: [],
  colors: {
    'editor.background': '#030712',          // gray-950, matching the cards
    'editorGutter.background': '#030712',
    'editorLineNumber.foreground': '#374151',
    'editorLineNumber.activeForeground': '#9ca3af',
    'editor.lineHighlightBackground': '#111827',
    'editor.selectionBackground': '#312e81',
    'editorIndentGuide.background1': '#1f2937',
    'editorWidget.background': '#111827',
    'editorWidget.border': '#1f2937',
    'editorSuggestWidget.background': '#111827',
  },
})

monaco.editor.defineTheme(CPINTEL_LIGHT, {
  base: 'vs',
  inherit: true,
  rules: [],
  colors: {
    'editor.background': '#ffffff',          // light-mode card surface
    'editorGutter.background': '#ffffff',
    'editorLineNumber.foreground': '#aeb4bd',
    'editorLineNumber.activeForeground': '#4b5563',
    'editor.lineHighlightBackground': '#f4f5f7',
    'editor.selectionBackground': '#c7d2fe',
    'editorIndentGuide.background1': '#e5e7eb',
    'editorWidget.background': '#ffffff',
    'editorWidget.border': '#e5e7eb',
    'editorSuggestWidget.background': '#ffffff',
  },
})

export { monaco }
