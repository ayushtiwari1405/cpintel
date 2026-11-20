import { useState } from 'react'
import { formatDistanceToNow } from 'date-fns'
import { BadgeCheck, Copy, ExternalLink, Loader2, ShieldAlert } from 'lucide-react'
import { useCheckVerify, useStartVerify } from '@/hooks/usePlatforms'
import { copyText } from '@/utils/clipboard'
import type { HandleChallenge, PlatformAccount } from '@/types'

/**
 * Proving a linked handle is yours. Lab assignments only credit solves from verified handles,
 * so a student cannot link a friend's account and collect their solves.
 */
export function HandleVerification({ account }: { account: PlatformAccount }) {
  const start = useStartVerify()
  const check = useCheckVerify()
  const [challenge, setChallenge] = useState<HandleChallenge | null>(null)

  if (account.verifiedAt) {
    return (
      <p className="mt-3 inline-flex items-center gap-1.5 text-xs text-green-300">
        <BadgeCheck size={14} aria-hidden="true" />
        Verified {formatDistanceToNow(new Date(account.verifiedAt), { addSuffix: true })}.
        Lab solves from this handle count.
      </p>
    )
  }

  const isCf = account.platform === 'CODEFORCES'

  return (
    <div className="mt-4 rounded-lg border border-amber-900/60 bg-amber-950/20 p-3">
      <p className="flex items-start gap-1.5 text-xs text-amber-200">
        <ShieldAlert size={14} className="mt-px shrink-0" aria-hidden="true" />
        <span>
          Not verified — labs only count verified handles.
        </span>
      </p>

      {!challenge ? (
        <button type="button" className="btn-secondary btn-sm mt-3"
          disabled={start.isPending}
          onClick={() => start.mutate(account.platform, { onSuccess: setChallenge })}>
          {start.isPending && <Loader2 size={13} className="animate-spin" aria-hidden="true" />}
          Verify this handle
        </button>
      ) : (
        <div className="mt-3 space-y-3 text-sm text-gray-300">
          {isCf ? (
            <ol className="list-decimal space-y-1 pl-5 text-xs">
              <li>
                Signed in to Codeforces as <span className="font-mono">{account.handle}</span>, open{' '}
                <a href={challenge.problemUrl} target="_blank" rel="noreferrer"
                  className="inline-flex items-center gap-0.5 text-accent-300 hover:underline">
                  problem 4A <ExternalLink size={11} aria-hidden="true" />
                </a>.
              </li>
              <li>Submit any code that does not compile (for example, just the word below).
                A compilation error does not affect your rating.</li>
              <li>Come back and press Check.</li>
            </ol>
          ) : (
            <ol className="list-decimal space-y-1 pl-5 text-xs">
              <li>
                Signed in to LeetCode as <span className="font-mono">{account.handle}</span>, open{' '}
                <a href="https://leetcode.com/profile/" target="_blank" rel="noreferrer"
                  className="inline-flex items-center gap-0.5 text-accent-300 hover:underline">
                  Edit profile <ExternalLink size={11} aria-hidden="true" />
                </a>.
              </li>
              <li>Put the code below anywhere in your <b>Summary</b> and save. You can remove it
                once verified.</li>
              <li>Come back and press Check.</li>
            </ol>
          )}
          <div className="flex flex-wrap items-center gap-2">
            <code className="rounded-md border border-gray-700 bg-gray-950 px-2 py-1 font-mono text-sm text-gray-50">
              {challenge.code}
            </code>
            <button type="button" className="btn-ghost btn-sm" onClick={() => copyText(challenge.code)}
              aria-label="Copy the code">
              <Copy size={13} aria-hidden="true" /> Copy
            </button>
          </div>
          <div className="flex flex-wrap items-center gap-3">
            <button type="button" className="btn-primary btn-sm"
              disabled={check.isPending}
              onClick={() => check.mutate(account.platform)}>
              {check.isPending && <Loader2 size={13} className="animate-spin" aria-hidden="true" />}
              {check.isPending ? 'Checking…' : 'Check'}
            </button>
            <span className="text-xs text-gray-500">
              Expires {formatDistanceToNow(new Date(challenge.expiresAt), { addSuffix: true })}
            </span>
          </div>
        </div>
      )}
    </div>
  )
}
