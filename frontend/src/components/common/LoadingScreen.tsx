import { BrandMark } from './BrandMark'

/** While a page's code is fetched. The mark, a quiet bar, and a label for screen readers. */
export function LoadingScreen() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-gray-950" role="status">
      <div className="flex flex-col items-center gap-4">
        <BrandMark className="h-10 w-10" />
        <div className="h-0.5 w-24 overflow-hidden rounded-full bg-gray-800" aria-hidden="true">
          <div className="h-full w-1/3 animate-[loadbar_1.1s_ease-in-out_infinite] rounded-full
                          bg-accent-400" />
        </div>
        <span className="sr-only">Loading CPIntel</span>
      </div>
    </div>
  )
}
