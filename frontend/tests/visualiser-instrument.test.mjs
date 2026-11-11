// The code visualiser's C++ instrumenter (src/visualiser/tracers/cpp/instrument.ts), on the real
// tree-sitter C++ grammar. Run with `npm test` (Node 24 runs the TypeScript module directly).
import { before, describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import path from 'node:path'
import { instrument } from '../src/visualiser/tracers/cpp/instrument.ts'

let parse
let parseSafe

before(async () => {
  const require = createRequire(import.meta.url)
  const dir = path.dirname(require.resolve('@vscode/tree-sitter-wasm'))
  const ts = (await import('@vscode/tree-sitter-wasm')).default
  await ts.Parser.init({ locateFile: f => path.join(dir, f) })
  const parser = new ts.Parser()
  parser.setLanguage(await ts.Language.load(path.join(dir, 'tree-sitter-cpp.wasm')))
  parse = src => instrument(src, parser.parse(src).rootNode)
  parseSafe = src => instrument(src, parser.parse(src).rootNode, { safe: true })
})

const contains = (text, part) => assert.ok(text.includes(part), `expected to find:\n  ${part}\nin:\n  ${text}`)
const lacks = (text, part) => assert.ok(!text.includes(part), `did not expect:\n  ${part}`)
const body = out => out.split('\n').slice(2).join('\n')

describe('instrument', () => {
  it('keeps every line where it was', () => {
    const src = `#include <bits/stdc++.h>
// a comment in Hindi: मान लो
using namespace std;
int g[5];
struct P { int x; P* nxt; };
int f(int n) {
    if (n < 2) return n;
    return f(n - 1) + f(n - 2);
}
int main() {
    vector<int> v = {1, 2};
    for (int i = 0; i < 2; i++) v[i]++;
    for (auto& x : v)
        x *= 2;
    while (v[0] > 0) { v[0]--; }
    cout << f(4);
}
`
    const out = parse(src)
    assert.ok(out.startsWith('#include "cpviz.h"\n#line 1\n'))
    assert.equal(body(out).split('\n').length, src.split('\n').length)
    const lines = body(out).split('\n')
    // Original text survives on its own line, with the hooks around it.
    src.split('\n').forEach((l, i) => {
      for (const word of l.match(/[A-Za-z_]\w*|मान/g) ?? []) contains(lines[i], word)
    })
    contains(lines[3], 'static cpviz::Var __cpviz_v1("g", g);')
    contains(lines[4], '__w.field("x", x); __w.field("nxt", nxt);')
    contains(lines[5], 'cpviz::Frame __cpviz_f("f", 6, 9);')
    contains(lines[5], 'cpviz::Var __cpviz_v2("n", n);')
    contains(lines[6], '{ cpviz::step(7); { cpviz::note_ret(__cpviz_f, 7, n); return n; } }')
    contains(lines[7], 'return cpviz::ret(__cpviz_f, 8, (f(n - 1) + f(n - 2)));')
    contains(lines[11], '{ cpviz::Var')
    contains(lines[11], '("i", i); cpviz::step(12); cpviz::step(12); v[i]++; }')
    // The loop's body is on the next line, so its reference is registered there.
    contains(lines[13], 'cpviz::by_ref); cpviz::step(13); cpviz::step(14); x *= 2; }')
    contains(lines[14], '{ cpviz::step(15); cpviz::step(15); v[0]--; }')
    contains(lines[16], 'cpviz::note_ret_void(__cpviz_f, 17);')
  })

  it('registers a run-time-sized array as a pointer and a length', () => {
    const out = parse('int main() { int n = 3; int a[n]; int b[10]; }')
    contains(out, '("a", a, (std::size_t)(n))')
    contains(out, '("b", b);')
  })

  it('leaves constexpr functions alone and skips risky rewrites in safe mode', () => {
    const src = 'constexpr int sq(int x) { return x * x; }\nstruct S { int a; };\nint h() { return 1 + 2; }'
    lacks(parse(src).split('\n')[2], 'cpviz')
    const safe = parseSafe(src)
    lacks(safe, '__cpviz_dump')
    lacks(safe, 'cpviz::ret(')
    contains(safe, 'cpviz::Frame __cpviz_f("h"')
  })

  it('does not step into a macro loop body or register variables after a case label', () => {
    const out = parse(`#define rep(i,n) for(int i=0;i<n;i++)
int main() { int s = 0; rep(i, 3) s += i; switch (s) { case 1: int t; t = 2; break; } }`)
    assert.doesNotMatch(out, /rep\(i, 3\) cpviz::step/)
    lacks(out, '("t", t)')
  })

  it('shows the variable of a loop macro, braced or nested as a "definition"', () => {
    const out = parse(`#define rep(i,a,b) for(int i=a;i<b;i++)
#define FOR(x, n) for (long long x = 0; x < (n); ++x)
int main() {
  rep(i, 0, 3) {
    FOR(j, 2) {
      int s = i + j;
    }
  }
}`)
    const lines = body(out).split('\n')
    contains(lines[3], 'cpviz::step(4); rep(i, 0, 3) { cpviz::Var')
    contains(lines[3], '("i", i); cpviz::step(4);')
    contains(lines[4], 'cpviz::step(5); FOR(j, 2) { cpviz::Var')
    contains(lines[4], '("j", j); cpviz::step(5);')
    lacks(out, 'Frame __cpviz_f("FOR"')
  })

  it('lists base-class fields and registers 2D arrays of run-time size', () => {
    const out = parse(`struct A { int x; };
struct B : public A { int y; };
int main() { int n = 2, m = 3; int dp[n][m]; }`)
    contains(out, 'cpviz::dump_base(__w, static_cast<const A&>(*this)); __w.field("y", y);')
    contains(out, '("dp", &dp[0][0], (std::size_t)(n), (std::size_t)(m));')
  })
})

