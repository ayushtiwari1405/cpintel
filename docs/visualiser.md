# Code visualiser

`/visualise` steps through a C++ or Python program line by line, forwards and backwards, and
shows what it does to memory. It is meant for teaching: a professor can present it in a lecture,
and students can use it while they write code. Who sees it in the navigation is still to be
decided. For now every signed-in user has it: students under **Solve**, admins under **Teach**.

Everything runs in the browser tab. The server stores nothing and runs nothing, so a whole class
using it costs the server nothing.

## What it shows

| View | What it draws |
| --- | --- |
| **Code** | The line about to run, the line just run, and how many times each line ran (`×12`). Click a line to jump to its next run. Click the gutter to set a breakpoint. |
| **Memory** | The call stack (outermost first) and the heap, with an arrow for every reference and pointer. Values that changed since the previous step are highlighted. C++ references are violet dashed arrows; a dangling or uninitialised pointer is drawn as such and never followed. |
| **Structures** | Each variable with parts, drawn as what it is: an array with its index variables (`lo`, `mid`, `hi`, `i`, `j`…) under it, optionally as bars for sorting; a 2D grid or DP table with the `[i][j]` cell marked; a graph from an adjacency list, coloured by a `vis`/`visited` array, with `dist`/`parent` arrays beside it; a binary tree; a linked list (cycles are detected); a heap or priority queue as a tree; stacks, queues and maps. The view is guessed from shape and name and can be changed per variable. |
| **Calls** | The recursion tree as it grows: arguments, return values, which calls are on the stack and which one is running. Click a call to jump to it. |
| **Output** | What the program has printed up to this step, and any error. |

Controls: first / previous breakpoint (or out of this call) / back / play / forward / next
breakpoint (or step over) / last, a scrubber, and a speed setting. The keys are `←` `→`,
`Shift+←` `Shift+→`, `Space`, `Home` and `End`.

### Teaching tools

- **Notes**: pin a note to any step (the sticky-note button). It shows as a callout when that step is reached.
- **Breakpoints**: playing stops at them, and the skip buttons jump between them.
- **Present**: full screen, with larger code and diagrams.
- **Share**: copies a link with the code, input, notes, breakpoints and the current step. The
  data lives in the URL fragment, which browsers never send to a server. Opening the link
  re-runs the trace and lands on the same step. The person opening it must be signed in, as for
  any page.

## How it works

The views all read one format, the `Trace` in `frontend/src/visualiser/trace.ts`: a full
snapshot of the stack and the reachable heap at every step. Both languages produce that format.

**Python** runs in Pyodide in a worker (`tracers/python.worker.ts`). It uses the same pinned
Pyodide as the Run button, from `runner/browser/toolchains.ts`. The tracer is a plain Python
file (`tracers/python_tracer.py`) built on `sys.settrace`, so the tests run it under CPython.

**C++** is traced without a debugger:

1. `tracers/cpp/instrument.ts` parses the program with tree-sitter (`@vscode/tree-sitter-wasm`,
   served from this site) and inserts hooks *on the lines they belong to*. Each function opens a
   `cpviz::Frame`, each variable is registered by a `cpviz::Var` when it is declared, and
   `cpviz::step(line)` runs before each statement. Compiler messages and steps therefore keep
   the student's line numbers.
2. The in-browser Clang (the Run button's compiler worker) builds it at `-O0` with the runtime
   header `tracers/cpp/cpviz.h`. That header serialises any type with templates: STL containers
   and adapters, pairs and tuples, smart pointers, C arrays, and user structs (the instrumenter
   gives each struct a member that lists its fields). It also tracks `new`/`delete` and
   `malloc`/`free`, so a pointer is only followed into live memory.
3. The trace build also turns on Clang's trap-only sanitizers (`null`, `bounds`, `vla-bound`,
   `return`) and libc++ hardening. In WebAssembly a null write or `a[10]` on a 5-element array
   would otherwise "work". With these it stops on the offending line, as it would on a real
   machine.
4. `tracers/cppRun.worker.ts` runs the program. It splits the step lines (stderr, prefixed with
   `\x1e`) from the program's output and records how much output existed at each step.

If the instrumented program does not compile, the original is compiled. If that also fails, its
errors are shown as the student's. Otherwise the program is retried with a cautious
instrumentation (no return values, no struct fields, no run-time-sized arrays) before the
visualiser reports that it cannot trace this program.

## Limits

- 3000 steps per run (`MAX_STEPS` in `visualiser/engine.ts`), and 20 s of tracing. Past that, the
  trace stops and says so. Collections show their first 120 elements.
- C++: no exceptions (the browser's libc++ is built without them, as for Run), and no GNU
  `pb_ds`. Code inside macros (a `rep(i,0,n)` loop, say) runs, but its loop variable is not
  shown. Base-class fields are not listed. A pointer into a container that lives inside a heap
  object is drawn as its own heap block.
- Python: the standard library runs untraced. Only the student's own `main.py` frames are shown.
- The parser is 0.4 MB gzipped, and loads on the first C++ visualisation. It is cached after
  that. nginx gzips `application/wasm` for this.

## Tests

- `frontend/src/visualiser/__tests__/`: the call tree, navigation, structure detection, heap
  layout and share links (Vitest).
- `frontend/tests/visualiser-instrument.test.mjs`: the instrumenter on the real C++ grammar.
- `frontend/tests/visualiser-python-tracer.test.mjs`: the Python tracer under CPython (skipped
  without `python3`).
- `frontend/e2e/visualiser-check.mjs` and `visualiser-teach.mjs` are ad-hoc browser checks.
  They are not part of any suite. They fake a signed-in session at the network layer and drive
  a dev server (`node e2e/visualiser-check.mjs http://localhost:5173 <out-dir> <example-id>`).
