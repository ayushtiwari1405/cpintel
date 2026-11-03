/**
 * The password rules, mirrored from the backend's PasswordPolicy so a form can show what is
 * still missing before it is sent. The server is the one that decides; keep the two in step.
 */
export const MIN_LENGTH = 10
export const MAX_LENGTH = 200

export interface PasswordRule {
  label: string
  met: boolean
}

export function passwordRules(password: string): PasswordRule[] {
  return [
    { label: `At least ${MIN_LENGTH} characters`, met: password.length >= MIN_LENGTH },
    { label: 'A lower-case letter', met: /\p{Ll}/u.test(password) },
    { label: 'An upper-case letter', met: /\p{Lu}/u.test(password) },
    { label: 'A digit', met: /\p{Nd}/u.test(password) },
    { label: 'A symbol, such as ! or #', met: /[^\p{L}\p{Nd}]/u.test(password) },
  ]
}

/**
 * Whether the rules the browser can check are all met. The common-password and
 * username/address checks happen on the server, which answers with what to change.
 */
export function isStrongPassword(password: string): boolean {
  return password.length <= MAX_LENGTH && passwordRules(password).every(r => r.met)
}
