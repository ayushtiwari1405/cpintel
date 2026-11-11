# Code visualiser

`/visualise` steps through a C, C++ or Python program line by line, forwards and backwards, and
shows what it does to memory. It is meant for teaching: a professor can present it in a lecture,
and students can use it while they write code.

**Who has it:** admins (under **Teach**) and users signed in normally (under **Solve**). An
examination session cannot open it: the route guard keeps those at `/exam`.

**Where it runs:** in the user's own browser tab, or in the desktop app, which uses the same
in-browser engine. There is no backend for it. The server stores nothing and runs nothing, so a
whole class using it costs the server nothing. The only thing kept is the editor draft, in that
browser's own storage.

## What it shows

| View | What it draws |
| --- | --- |
| **Code** | The line about to run, the line just run, and how many times each line ran (`×12`). Click a line to jump to its next run. Click the gutter to set a breakpoint. |
| **Memory** | The call stack (outermost first) and the heap, with an arrow for every reference and pointer. Values that changed since the previous step are highlighted. C++ references are violet dashed arrows; a dangling or uninitialised pointer is drawn as such and never followed. |
| **Structures** | Each variable with parts, drawn as what it is: an array with its index variables (`lo`, `mid`, `hi`, `i`, `j`…) under it, optionally as bars for sorting, and the cells the line about to run reads ringed (`a[j]` and `a[j + 1]` in a bubble sort, `dp[i - 1][j]` in a DP); a 2D grid or DP table with the `[i][j]` cell marked; a graph from an adjacency list (on a circle when small, in layers when it is a tree, force-directed otherwise; the same graph always lands in the same place), coloured by a `vis`/`visited` array, with `dist`/`parent` arrays beside it; a binary tree; a linked list (cycles are detected); a heap or priority queue as a tree; stacks, queues and maps. The view is guessed from shape and name and can be changed per variable. |
| **Calls** | The recursion tree as it grows: arguments, return values, which calls are on the stack and which one is running. Click a call to jump to it. Past 40 calls, finished calls fold into one box (`→ 13 · +40`), keeping the path being run readable. This can be switched off. |
| **Output** | What the program has printed up to this step, and any error. |

Controls: first / previous breakpoint (or out of this call) / back / play / forward / next
breakpoint (or step over) / last, a scrubber, and a speed setting. The keys are `←` `→`,
`Shift+←` `Shift+→`, `Space`, `Home` and `End`.

### Teaching tools

- **Notes**: pin a note to any step (the sticky-note button). It shows as a callout when that step is reached.
- **Breakpoints**: playing stops at them, and the skip buttons jump between them. With **Start
  recording at the first breakpoint**, nothing before that point is recorded, so the set-up of a
  long program does not use up the step budget.
- **Present**: full screen, with larger code and diagrams.
- **Share**: copies a link with the code, input, notes, breakpoints and the current step. The
  data lives in the URL fragment, which browsers never send to a server. Opening the link
  re-runs the trace and lands on the same step. The person opening it must be signed in, as for
  any page.

Screen readers hear each step as it is reached, for example "Step 12 of 55, line 5. j is 3". The
graph and tree drawings carry a text description of their nodes and edges.

## How it works

The views all read one format, the `Trace` in `frontend/src/visualiser/trace.ts`: a full
snapshot of the stack and the reachable heap at every step. Both languages produce that format.

**Python** runs in Pyodide in a worker (`tracers/python.worker.ts`). It uses the same pinned
Pyodide as the Run button, from `runner/browser/toolchains.ts`. The tracer is a plain Python
file (`tracers/python_tracer.py`) built on `sys.settrace`, so the tests run it under CPython.

**C and C++** are traced without a debugger. C is compiled as C++, with one difference: `malloc`,
`calloc` and `realloc` return a value that converts to any pointer, as `void *` does in C. That
way `int *p = malloc(...)` needs no cast. When a C program will not compile that way, it is
checked with the real C compiler first, so the student's own errors are reported as theirs.

1. `tracers/cpp/instrument.ts` parses the program with tree-sitter (`@vscode/tree-sitter-wasm`,
   served from this site) and inserts hooks *on the lines they belong to*. Each function opens a
   `cpviz::Frame`, each variable is registered by a `cpviz::Var` when it is declared, and
   `cpviz::step(line)` runs before each statement. Compiler messages and steps therefore keep
   the student's line numbers. Loop macros (`#define rep(i,a,b) for(int i=a;…)`) are recognised,
   and their loop variable is shown.
2. The in-browser Clang (the Run button's compiler worker) builds it at `-O0` with the runtime
   header `tracers/cpp/cpviz.h`. That header serialises any type with templates: STL containers
   and adapters, pairs and tuples, smart pointers, C arrays (including run-time-sized 1D and 2D
   ones), and user structs (the instrumenter gives each struct a member that lists its fields,
   base classes first). A pointer into a container inside a heap object points at that object. It also tracks `new`/`delete` and
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
  `pb_ds`. A loop macro without braces around its body is run but not stepped into. Arrays of
  three or more run-time dimensions fall back to the cautious instrumentation.
- C: C-only code apart from `malloc`'s `void *` (assigning another `void *` without a cast, a
  variable named `new` or `class`) is not traced, and the visualiser says so.
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
