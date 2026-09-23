import { useCallback, useRef } from 'react'
import Editor, { type OnMount } from '@monaco-editor/react'
import { Loader2 } from 'lucide-react'

import { useTheme } from '@/contexts/ThemeContext'
import { CPINTEL_DARK, CPINTEL_LIGHT, monaco } from './monacoSetup'

interface Props {
  value: string
  onChange: (value: string) => void
  /** Monaco language id — see detectLanguage(). */
  language: string
  readOnly?: boolean
  /** Ctrl/Cmd+Enter. Wired inside the editor because Monaco swallows keydown. */
  onRun?: () => void
  /** Ctrl/Cmd+S, for submitting without reaching for the mouse. */
  onSubmit?: () => void
}

/**
 * The code editor, shared by Practice and Compete.
 *
 * Deliberately thin: it owns the editor's configuration and keybindings and nothing about
 * problems, submissions or running. That keeps it usable anywhere a code box is needed, and
 * keeps the pages in charge of what the code is for.
 */
export function CodeEditor({
  value, onChange, language, readOnly, onRun, onSubmit,
}: Props) {
  // Held in refs so the keybindings, which are registered once on mount, always call the
  // current handler rather than the one captured at mount.
  const { theme } = useTheme()
  const runRef = useRef(onRun)
  const submitRef = useRef(onSubmit)
  runRef.current = onRun
  submitRef.current = onSubmit

  const handleMount = useCallback<OnMount>((editor) => {
    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => runRef.current?.())
    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => submitRef.current?.())
  }, [])

  return (
    <Editor
      value={value}
      language={language}
      theme={theme === 'dark' ? CPINTEL_DARK : CPINTEL_LIGHT}
      onChange={v => onChange(v ?? '')}
      onMount={handleMount}
      loading={
        <span className="flex items-center gap-2 text-xs text-gray-600">
          <Loader2 size={13} className="animate-spin" /> Loading editor…
        </span>
      }
      options={{
        readOnly,
        fontSize: 13,
        // Matches the app's mono stack, with Monaco's own fallbacks behind it.
        fontFamily: "'JetBrains Mono', 'Fira Code', Menlo, Consolas, monospace",
        fontLigatures: true,
        automaticLayout: true,             // the panel resizes when the picker collapses
        padding: { top: 10, bottom: 10 },

        // ── wrapping ──────────────────────────────────────────────────────
        // On, and not negotiable in a panel this narrow: at ~400px the editor shows
        // roughly 50 columns, so ordinary competitive code would otherwise need constant
        // horizontal scrolling to read a line you just wrote.
        wordWrap: 'on',
        wrappingIndent: 'indent',          // continuation lines line up under the statement
        wordWrapColumn: 120,

        // ── VS Code's editing behaviour ───────────────────────────────────
        tabSize: 4,
        insertSpaces: true,
        detectIndentation: true,           // a pasted file keeps its own indentation
        autoIndent: 'full',
        formatOnPaste: true,
        formatOnType: true,
        autoClosingBrackets: 'languageDefined',
        autoClosingQuotes: 'languageDefined',
        autoSurround: 'languageDefined',
        linkedEditing: true,
        dragAndDrop: true,
        multiCursorModifier: 'alt',
        columnSelection: false,
        copyWithSyntaxHighlighting: false, // plain text when pasting into a submit box

        // ── the visual cues that make it feel like VS Code ────────────────
        bracketPairColorization: { enabled: true },
        guides: { bracketPairs: true, indentation: true, highlightActiveIndentation: true },
        matchBrackets: 'always',
        occurrencesHighlight: 'singleFile',
        selectionHighlight: true,
        renderLineHighlight: 'all',
        cursorBlinking: 'smooth',
        cursorSmoothCaretAnimation: 'on',
        smoothScrolling: true,
        stickyScroll: { enabled: true },    // keeps the enclosing function visible
        folding: true,
        showFoldingControls: 'mouseover',
        renderWhitespace: 'selection',
        scrollBeyondLastLine: false,
        scrollbar: { verticalScrollbarSize: 10, horizontalScrollbarSize: 10 },

        // Off deliberately: the panel is ~400px wide, where a minimap costs a sixth of the
        // visible code to show a thumbnail of a file that is usually one screen long.
        minimap: { enabled: false },

        // ── suggestions ───────────────────────────────────────────────────
        quickSuggestions: { other: true, comments: false, strings: false },
        suggestOnTriggerCharacters: true,
        tabCompletion: 'on',
        snippetSuggestions: 'inline',
        // VS Code accepts on Enter, but it has a C++ language server behind it. Monaco here
        // has only word-based suggestions drawn from the buffer, so Enter-to-accept would
        // regularly replace a newline with a random identifier. Tab still accepts.
        acceptSuggestionOnEnter: 'off',

        find: { seedSearchStringFromSelection: 'selection', addExtraSpaceOnTop: false },
      }}
    />
  )
}
