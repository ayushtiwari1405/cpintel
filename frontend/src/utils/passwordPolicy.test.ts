import { describe, expect, it } from 'vitest'
import { isStrongPassword, passwordRules } from './passwordPolicy'

describe('passwordPolicy', () => {
  it('accepts a password meeting every rule', () => {
    expect(isStrongPassword('Summer#2026x')).toBe(true)
  })

  it.each(['short1!A', 'alllowercase1!', 'ALLUPPERCASE1!', 'NoDigitsHere!', 'NoSymbols2026x'])(
    'refuses %s', pw => expect(isStrongPassword(pw)).toBe(false))

  it('reports each rule separately', () => {
    const missing = passwordRules('abc').filter(r => !r.met).map(r => r.label)
    expect(missing).toEqual([
      'At least 10 characters', 'An upper-case letter', 'A digit', 'A symbol, such as ! or #',
    ])
  })
})
