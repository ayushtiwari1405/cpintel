// The code visualiser's Python tracer (src/visualiser/tracers/python_tracer.py) — the file Pyodide
// runs in the browser — under this machine's CPython. Skipped where there is no python3.
import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const python = spawnSync('python3', ['--version']).status === 0
const TRACER = fileURLToPath(new URL('../src/visualiser/tracers/python_tracer.py', import.meta.url))

function trace(source, stdin = '', maxSteps = 500, startLines = '') {
  const script = `import json, sys
g = {}
exec(compile(open(sys.argv[1]).read(), 'tracer', 'exec'), g)
print(g['__cpviz_trace'](sys.stdin.read(), ${JSON.stringify(stdin)}, ${maxSteps}, ${JSON.stringify(startLines)}))`
  const r = spawnSync('python3', ['-I', '-c', script, TRACER], { input: source, encoding: 'utf8' })
  if (r.status !== 0) throw new Error(r.stderr)
  return JSON.parse(r.stdout)
}

describe('python tracer', { skip: !python && 'no python3 here' }, () => {
  it('records calls, returns, objects and output', () => {
    const t = trace('def f(n):\n    return n * 2\nxs = [1, 2]\nprint(f(xs[1]))\n')
    assert.equal(t.error, null)
    assert.equal(t.stdout, '4\n')
    const ret = t.steps.find(s => s.event === 'return' && s.frames.length === 2)
    assert.deepEqual(ret.ret, { k: 'num', v: '4' })
    const last = t.steps[t.steps.length - 1]
    const xs = last.frames[0].vars.find(([n]) => n === 'xs')[1]
    assert.equal(xs.k, 'ref')
    assert.equal(last.heap[xs.id].k, 'seq')
    assert.equal(last.heap[xs.id].t, 'list')
    // Output is attributed to the step it appeared in.
    assert.equal(last.out, 2)
  })

  it('stops at the step limit, even inside except Exception', () => {
    const t = trace('while True:\n    try:\n        x = 1\n    except Exception:\n        pass\n', '', 40)
    assert.equal(t.truncated, true)
    assert.equal(t.steps.length, 40)
  })

  it('reports errors with their line, and reads stdin', () => {
    const t = trace('n = int(input())\nprint([0][n])\n', '5\n')
    assert.match(t.error, /IndexError/)
    assert.equal(t.errorLine, 2)
  })

  it('does not trace class bodies as calls', () => {
    const t = trace('class A:\n    x = 1\na = A()\n')
    assert.equal(t.steps.some(s => s.frames.some(f => f.name === 'A')), false)
  })

  it('starts recording at the first run of a start line', () => {
    const src = 'total = 0\nfor i in range(50):\n    total += i\nprint(total)\n'
    const t = trace(src, '', 500, '4')
    assert.equal(t.steps[0].line, 4)
    assert.equal(t.stdout, '1225\n')
    assert.ok(t.steps.length < 5)
  })
})

