# Running code

The Run button compiles and executes your solution, then compares stdout against each test
case. It is a rehearsal harness, not a judge: passing the
samples means your code builds and agrees with the examples, nothing more — Codeforces tests far
more than that, and a problem with several valid answers will show a mismatch here even when the
answer is right.

Execution is sandboxed with [bubblewrap](https://github.com/containers/bubblewrap): only `/usr`
is mounted, read-only, so `/home`, `/etc` and `/var` do not exist inside, and all namespaces are
unshared, which takes the network with them. On top of that every run gets a wall-clock timeout
and CPU, address-space and output-size limits. `GET /api/run/status` reports whether the sandbox
is actually in effect; if bubblewrap is missing, runs still get the resource limits but not the
isolation, and the UI says so.

**This is arbitrary code execution by design**, so where it happens depends on who is using it:

- **Dev** runs it inside the backend process — the only code there is yours. Outside
  examinations the page does not use the server at all: the desktop app runs code with the
  compilers it carries and the website runs it in WebAssembly (see `docs/ARCHITECTURE.md`).
- **A shared server** never runs it in the backend, which holds the database credentials and
  every signing key. `CPINTEL_RUNNER_URL` sends each run to the `runner` container
  (`com.cpintel.runner.RunnerServer`, the same jar with a different entry point). That container
  has no secrets but its token, no route to the internet (`runner-net` is internal), a read-only
  filesystem, no Linux capabilities, and capped CPU, memory and processes — and each run still
  gets its own bubblewrap sandbox, so concurrent runs cannot see each other's files. If the
  sandbox cannot be built there, the runner refuses every run instead of running unsandboxed,
  and the editor says running is paused. At most `CPINTEL_RUNNER_CONCURRENCY` runs execute at
  once; the rest wait up to 20 seconds and are then told the runner is busy.

Bubblewrap needs unprivileged user namespaces inside the container, so the runner uses Docker's
default seccomp profile plus exactly the five calls that takes (`deploy/runner-seccomp.json`)
and runs without the docker-default AppArmor profile, which forbids the mounts. On Ubuntu 24.04
hosts, unprivileged user namespaces are restricted by default; if `docker logs cpintel-runner`
does not say `sandbox active`, set `kernel.apparmor_restrict_unprivileged_userns=0`.

## Languages

**C++** (`g++`, `-std=c++20 -O2 -m64`, statically linked so the binary does not need shared
libraries visible inside the sandbox), **C** (`gcc`, `-std=gnu11 -O2 -m64`, linked with `-lm` as
the judges do) and **Python 3** (CPython). Each toolchain is probed on
`PATH` per request, so installing one does not need a restart, and a language whose toolchain is
absent appears in the picker as unavailable *with the reason* rather than silently missing.
The runner image carries gcc/g++ 13 and Python 3.12 (Ubuntu 24.04, glibc — as on the judges),
with `<bits/stdc++.h>` precompiled, and starts under `tini` (see `docs/ARCHITECTURE.md` for why
the runner must not be PID 1).

Python gets a build step even though it does not need one: `py_compile` parses the file before
anything runs. Without it, a syntax error would surface as the same runtime error on all twelve
of a problem's samples, with the real message buried in twelve copies of one traceback; with it,
it is reported once, with the line and the caret, exactly as a `g++` error is. A program that
parses and then raises still fails per test, as it should.

The interpreter runs in isolated mode (`-I`), so `PYTHONPATH`, the user site-packages directory
and `PYTHON*` environment variables are ignored — which matters on the documented fallback path,
where bubblewrap is unavailable and `~/.local/lib` would otherwise be readable. Memory is capped
the same way C++ is: CPython starts fine under it, and without it a one-line Python program will
quietly take a gigabyte of the host.

Adding Java or SQL means implementing `LanguageRuntime` — one class, no other changes; see the
notes on that interface for how SQL differs from the others.

The backend image ships **neither** toolchain, deliberately: it never runs submitted code.
