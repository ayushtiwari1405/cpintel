"""
Records a Python program's execution, step by step, for the code visualiser.

Runs inside Pyodide in a worker (and under plain CPython in the tests). The JavaScript side
calls `__cpviz_trace(source, stdin, max_steps)` and gets one JSON string back, shaped as the
`Trace` type in frontend/src/visualiser/trace.ts. Every step is a full snapshot: the stack of
the program's own frames and every heap object reachable from them, which keeps the viewer
simple (any step can be shown on its own) at a cost that is fine for teaching-sized programs.

Values are encoded as:
  {"k": "num"|"str"|"bool"|"none", "v": text}     inline primitives
  {"k": "ref", "id": "h3"}                        a reference to a heap object
  {"k": "opaque", "t": type, "v": text}           a function, class, module or other thing
Heap objects:
  {"k": "seq", "t": "list"|"tuple"|"set"|"frozenset"|"deque", "items": [...], "more": n}
  {"k": "map", "t": "dict", "entries": [[key, value], ...], "more": n}
  {"k": "obj", "t": class name, "fields": [[name, value], ...]}
"""

import io
import json
import linecache
import sys
import traceback
import types
from collections import deque

FILENAME = "main.py"
MAX_ITEMS = 120          # elements shown per collection; the rest are counted in "more"
CO_OPTIMIZED = 0x0001
MAX_STR = 300            # characters of a string or repr shown


class _StepLimit(BaseException):
    """BaseException, so a student's `except Exception:` cannot swallow the stop."""


class _Recorder:
    def __init__(self, max_steps):
        self.max_steps = max_steps
        self.steps = []
        self.ids = {}          # id(obj) -> "h<n>", stable for the whole run
        self.keep = []         # holds every labelled object so its id() is never reused
        self.frame_ids = {}    # id(frame) -> frame number
        self.frame_keep = []
        self.out = None
        self.truncated = False

    # ── values ──────────────────────────────────────────────────────────

    def label(self, obj):
        key = id(obj)
        name = self.ids.get(key)
        if name is None:
            name = "h%d" % (len(self.ids) + 1)
            self.ids[key] = name
            self.keep.append(obj)
        return name

    def encode(self, value, heap):
        if value is None:
            return {"k": "none", "v": "None"}
        if isinstance(value, bool):
            return {"k": "bool", "v": "True" if value else "False"}
        if isinstance(value, (int, float)):
            return {"k": "num", "v": repr(value)}
        if isinstance(value, complex):
            return {"k": "num", "v": repr(value)}
        if isinstance(value, str):
            text = value if len(value) <= MAX_STR else value[:MAX_STR] + "…"
            return {"k": "str", "v": text}
        if isinstance(value, (types.FunctionType, types.BuiltinFunctionType, types.MethodType)):
            return {"k": "opaque", "t": "function", "v": getattr(value, "__qualname__", "?")}
        if isinstance(value, type):
            return {"k": "opaque", "t": "class", "v": value.__name__}
        if isinstance(value, types.ModuleType):
            return {"k": "opaque", "t": "module", "v": value.__name__}
        name = self.label(value)
        if name not in heap:
            heap[name] = None            # reserved first, so a cycle stops here
            heap[name] = self.encode_object(value, heap)
        return {"k": "ref", "id": name}

    def encode_object(self, value, heap):
        t = type(value).__name__
        if isinstance(value, (list, tuple, deque)):
            items = list(value)
            kind = "tuple" if isinstance(value, tuple) else "deque" if isinstance(value, deque) else "list"
            return {"k": "seq", "t": kind,
                    "items": [self.encode(v, heap) for v in items[:MAX_ITEMS]],
                    "more": max(0, len(items) - MAX_ITEMS)}
        if isinstance(value, (set, frozenset)):
            try:
                items = sorted(value)
            except TypeError:
                items = list(value)
            return {"k": "seq", "t": t,
                    "items": [self.encode(v, heap) for v in items[:MAX_ITEMS]],
                    "more": max(0, len(items) - MAX_ITEMS)}
        if isinstance(value, dict):
            pairs = list(value.items())
            return {"k": "map", "t": t,
                    "entries": [[self.encode(k, heap), self.encode(v, heap)]
                                for k, v in pairs[:MAX_ITEMS]],
                    "more": max(0, len(pairs) - MAX_ITEMS)}
        fields = None
        if hasattr(value, "__dict__") and isinstance(getattr(value, "__dict__", None), dict):
            fields = list(vars(value).items())
        elif hasattr(type(value), "__slots__"):
            fields = [(s, getattr(value, s)) for s in type(value).__slots__ if hasattr(value, s)]
        if fields is not None and type(value).__module__ in ("__main__", "builtins"):
            return {"k": "obj", "t": t,
                    "fields": [[k, self.encode(v, heap)] for k, v in fields
                               if not k.startswith("__")]}
        try:
            text = repr(value)
        except Exception:
            text = "<%s>" % t
        if len(text) > MAX_STR:
            text = text[:MAX_STR] + "…"
        return {"k": "opaque", "t": t, "v": text}

    # ── frames ──────────────────────────────────────────────────────────

    def frame_id(self, frame):
        key = id(frame)
        n = self.frame_ids.get(key)
        if n is None:
            n = len(self.frame_keep) + 1
            self.frame_ids[key] = n
            self.frame_keep.append(frame)
        return n

    def snapshot(self, frame, event, line, ret=None, error=None):
        if len(self.steps) >= self.max_steps:
            self.truncated = True
            raise _StepLimit()
        stack = []
        f = frame
        while f is not None:
            if f.f_code.co_filename == FILENAME:
                stack.append(f)
            f = f.f_back
        stack.reverse()

        heap = {}
        frames = []
        for f in stack:
            module = f.f_code.co_name == "<module>"
            scope = f.f_globals if module else f.f_locals
            names = list(scope.keys())
            vars_ = []
            for name in names:
                if name.startswith("__"):
                    continue
                value = scope[name]
                if module and isinstance(value, types.ModuleType):
                    continue
                vars_.append([name, self.encode(value, heap)])
            frames.append({
                "id": self.frame_id(f),
                "name": "Global" if module else f.f_code.co_name,
                "line": f.f_lineno,
                "vars": vars_,
            })
        step = {
            "event": event,
            "line": line,
            "frames": frames,
            "heap": heap,
            "out": self.out.tell() if self.out is not None else 0,
        }
        if ret is not None:
            step["ret"] = ret
        if error is not None:
            step["error"] = error
        self.steps.append(step)

    def tracer(self, frame, event, arg):
        if frame.f_code.co_filename != FILENAME:
            return None
        if event == "call":
            if frame.f_code.co_name == "<module>":
                return self.tracer
            if not frame.f_code.co_flags & CO_OPTIMIZED:
                return None              # a class body: noise, not a call anyone wrote
            self.snapshot(frame, "call", frame.f_lineno)
        elif event == "line":
            self.snapshot(frame, "line", frame.f_lineno)
        elif event == "return":
            if frame.f_code.co_name == "<module>":
                self.snapshot(frame, "return", frame.f_lineno)
                return self.tracer
            heap_for_ret = {}
            ret = self.encode(arg, heap_for_ret)
            self.snapshot(frame, "return", frame.f_lineno, ret=ret)
            # The returned value may be a heap object the frames no longer reach.
            self.steps[-1]["heap"].update({k: v for k, v in heap_for_ret.items()
                                           if k not in self.steps[-1]["heap"]})
        elif event == "exception":
            exc_type, exc, _ = arg
            if exc_type is _StepLimit:
                return self.tracer
            self.snapshot(frame, "exception", frame.f_lineno,
                          error="%s: %s" % (exc_type.__name__, exc))
        return self.tracer


class _Out(io.StringIO):
    """stdout: records the text so far; each step stores how much existed at that point."""
    def tell(self):
        return len(self.getvalue())


def __cpviz_trace(source, stdin, max_steps):
    linecache.cache[FILENAME] = (len(source), None, source.splitlines(True), FILENAME)
    result = {"language": "python", "steps": [], "stdout": "", "error": None,
              "truncated": False}
    try:
        code = compile(source, FILENAME, "exec")
    except SyntaxError as e:
        result["error"] = "SyntaxError: %s (line %s)" % (e.msg, e.lineno)
        result["errorLine"] = e.lineno
        return json.dumps(result)

    rec = _Recorder(int(max_steps))
    out = _Out()
    rec.out = out
    saved = sys.stdin, sys.stdout, sys.stderr
    sys.stdin = io.StringIO(stdin)
    sys.stdout = out
    sys.stderr = out
    sys.settrace(rec.tracer)
    try:
        exec(code, {"__name__": "__main__", "__builtins__": __builtins__})
    except _StepLimit:
        pass
    except SystemExit:
        pass
    except BaseException as e:
        tb = e.__traceback__
        line = None
        while tb is not None:
            if tb.tb_frame.f_code.co_filename == FILENAME:
                line = tb.tb_lineno
            tb = tb.tb_next
        result["error"] = "%s: %s" % (type(e).__name__, e)
        result["errorLine"] = line
    finally:
        sys.settrace(None)
        sys.stdin, sys.stdout, sys.stderr = saved
    result["steps"] = rec.steps
    result["stdout"] = out.getvalue()
    result["truncated"] = rec.truncated
    return json.dumps(result)
