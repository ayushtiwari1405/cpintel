import { Component, type ErrorInfo, type ReactNode } from 'react'
import { AlertTriangle, RotateCcw } from 'lucide-react'

interface Props {
  children: ReactNode
  /** What broke, in the user's words — "the editor", "this page". Names the scope in the copy. */
  scope?: string
  /** Rendered instead of the default panel, for boundaries inside a pane rather than a page. */
  fallback?: (reset: () => void) => ReactNode
}

interface State {
  error: Error | null
}

/**
 * Keeps one broken component from taking the whole application with it.
 *
 * React unmounts the entire tree when a render throws and nothing catches it, so before this
 * existed a single bad render left a blank page with no route back — a harsher failure than any
 * of the data-fetch errors the app already handles carefully. `Suspense` does not help: it
 * catches promises, not exceptions.
 *
 * This has to be a class. Error boundaries are the one thing hooks still cannot express.
 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // Kept to the console rather than sent anywhere: this deployment has no error reporting
    // service, and inventing one here would be a decision made in the wrong place.
    console.error('Render error caught by boundary:', error, info.componentStack)
  }

  private reset = () => this.setState({ error: null })

  render() {
    const { error } = this.state
    if (!error) return this.props.children

    if (this.props.fallback) return this.props.fallback(this.reset)

    const scope = this.props.scope ?? 'this page'

    return (
      <div className="flex min-h-[16rem] flex-1 items-center justify-center p-6">
        <div className="w-full max-w-md rounded-xl border border-gray-800 bg-gray-900 p-6">
          <div className="mb-3 flex items-center gap-2.5">
            <AlertTriangle size={18} className="flex-shrink-0 text-amber-400" />
            <h2 className="text-sm font-medium text-gray-200">
              Something went wrong in {scope}
            </h2>
          </div>

          <p className="text-sm text-gray-400">
            The rest of the app is still working. Try again, and if it keeps happening the
            message below is the useful part to report.
          </p>

          <pre className="mt-3 max-h-32 overflow-auto rounded-lg border border-gray-800
            bg-gray-950 p-3 text-xs text-gray-500">
            {error.message || String(error)}
          </pre>

          <div className="mt-4 flex gap-2">
            <button
              onClick={this.reset}
              className="flex items-center gap-2 rounded-lg bg-indigo-600 px-3 py-2 text-sm
                         font-medium text-white transition-colors hover:bg-indigo-500"
            >
              <RotateCcw size={14} /> Try again
            </button>
            <button
              onClick={() => window.location.assign('/dashboard')}
              className="rounded-lg border border-gray-800 px-3 py-2 text-sm text-gray-300
                         transition-colors hover:bg-gray-800"
            >
              Back to dashboard
            </button>
          </div>
        </div>
      </div>
    )
  }
}
