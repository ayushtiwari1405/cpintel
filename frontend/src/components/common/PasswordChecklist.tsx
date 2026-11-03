import { Check, Circle } from 'lucide-react'

import { passwordRules } from '@/utils/passwordPolicy'

/**
 * What a new password still needs, ticked off as it is typed. The server also refuses
 * common passwords and ones built on the username or address, so that is said too.
 */
export function PasswordChecklist({ password, id }: { password: string; id?: string }) {
  return (
    <div id={id} className="mt-2 space-y-1 text-xs" aria-live="polite">
      <ul className="space-y-1">
        {passwordRules(password).map(rule => (
          <li
            key={rule.label}
            className={`flex items-center gap-1.5 ${rule.met ? 'text-emerald-400' : 'text-gray-500'}`}
          >
            {rule.met
              ? <Check size={12} aria-hidden="true" />
              : <Circle size={12} aria-hidden="true" />}
            <span>{rule.label}</span>
            <span className="sr-only">{rule.met ? '(done)' : '(missing)'}</span>
          </li>
        ))}
      </ul>
      <p className="text-gray-600">
        Not a common password, and not your username or email address.
      </p>
    </div>
  )
}
