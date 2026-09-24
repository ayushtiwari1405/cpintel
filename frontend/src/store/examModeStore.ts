import { create } from 'zustand'

/**
 * Whether an examination is being sat in this tab right now.
 *
 * <p>Set by the compete page, read by the app's frame. Moving to another page inside the app
 * is not a page unload, so nothing records it — and it unmounts the examination, which releases
 * the monitor and stops the heartbeat the submission gate reads. While this is set the frame
 * offers no way out except finishing, so that cannot happen by a stray click.
 */
interface ExamModeState {
  live: boolean
  setLive: (live: boolean) => void
}

export const useExamMode = create<ExamModeState>(set => ({
  live: false,
  setLive: live => set({ live }),
}))
