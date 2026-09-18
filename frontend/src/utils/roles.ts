import type { Role, User } from '@/types'

/**
 * Who may see what, on the client.
 *
 * Every one of these is presentation. The server checks the role in the token on each request,
 * so the worst a tampered profile in localStorage buys is a set of screens that answer 403 —
 * which is exactly what these helpers are for: drawing the console for the people who can use
 * it, and not drawing controls that would only fail.
 */

export function isAdmin(user: Pick<User, 'role'> | null | undefined): boolean {
  return user?.role === 'ADMIN' || user?.role === 'SUPER_ADMIN'
}

export function isSuperAdmin(user: Pick<User, 'role'> | null | undefined): boolean {
  return user?.role === 'SUPER_ADMIN'
}

const LABELS: Record<Role, string> = {
  USER:        'User',
  ADMIN:       'Admin',
  SUPER_ADMIN: 'Super admin',
}

export function roleLabel(role: Role): string {
  return LABELS[role] ?? role
}
