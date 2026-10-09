/**
 * Compares two dotted versions numerically, 1.10.0 above 1.9.3. Anything after a hyphen (a
 * pre-release tag) is ignored, which is all the desktop app's versions need.
 */
export function compareVersions(a: string, b: string): number {
  const parts = (v: string) => v.replace(/^v/, '').split('-')[0].split('.').map(n => parseInt(n, 10) || 0)
  const x = parts(a)
  const y = parts(b)
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    const d = (x[i] ?? 0) - (y[i] ?? 0)
    if (d !== 0) return d
  }
  return 0
}
