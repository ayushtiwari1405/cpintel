#!/usr/bin/env python3
"""
Builds the simulated contest's problem packages, ready to import into DOMjudge.

    ./scripts/simulation/make_problemset.py [--out DIR] [--seed N] [--verify]

One zip per problem, in the ICPC problem-package layout DOMjudge reads:

    problem.yaml                  name and limits
    domjudge-problem.ini          probid, label, timelimit, colour
    problem_statement/problem.pdf the statement contestants read
    data/sample/1.in, 1.ans       shown to contestants; CPIntel seeds these into the console
    data/secret/1.in, 1.ans       what the contest is judged on
    submissions/accepted/*.cpp    DOMjudge can run these on import to check the data
    submissions/wrong_answer/*.cpp
    submissions/time_limit_exceeded/*.cpp
    submissions/run_time_error/*.cpp
    submissions/compile_error/*.cpp

Answer files are produced by the Python reference implementation in ``problems.py``, so there
is exactly one statement of each problem's rules and the answers cannot drift from it.

``--verify`` additionally compiles the C++ accepted solution and runs it against every test,
which catches the failure mode this whole harness would otherwise hide: data that the judge
accepts but that no correct program can actually satisfy. It needs g++ and takes a minute or
two. Worth doing once before a contest and never again.
"""

from __future__ import annotations

import argparse
import json
import random
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from pdfgen import Pdf                     # noqa: E402
from problems import PROBLEMS, Problem     # noqa: E402


# DOMjudge's expected directory names for each judgement, so an import-time check
# knows what each submission is supposed to draw.
EXPECTED_DIR = {
    "AC": "accepted",
    "WA": "wrong_answer",
    "TLE": "time_limit_exceeded",
    "RTE": "run_time_error",
    "CE": "compile_error",
}


def statement_pdf(problem: Problem) -> bytes:
    """Renders one problem's statement as a PDF the arena can embed."""
    pdf = Pdf()
    pdf.heading(f"Problem {problem.letter}: {problem.title}")
    pdf.para(f"Time limit: {problem.time_limit:g} seconds")
    pdf.gap(6)

    for paragraph in problem.statement.split("\n\n"):
        pdf.para(paragraph)

    pdf.subheading("Input")
    pdf.para(problem.input_spec)

    pdf.subheading("Output")
    pdf.para(problem.output_spec)

    for i, (sample_in, sample_out) in enumerate(problem.samples, start=1):
        label = f"Sample {i}" if len(problem.samples) > 1 else "Sample"
        pdf.subheading(f"{label} input")
        pdf.mono(sample_in.rstrip("\n"))
        pdf.subheading(f"{label} output")
        pdf.mono(sample_out.rstrip("\n"))

    if problem.notes:
        pdf.subheading("Notes")
        pdf.para(problem.notes)

    return pdf.build()


def build_problem(problem: Problem, out_dir: Path, rng: random.Random) -> dict:
    """Writes one problem's zip and returns a manifest entry for it."""
    secret_inputs = problem.secret_gen(rng)

    # Every answer, sample and secret alike, comes from the one reference implementation.
    samples = [(i, o) for i, o in problem.samples]
    for sample_in, expected in samples:
        produced = problem.solve(sample_in)
        if produced != expected:
            raise SystemExit(
                f"{problem.letter}: the statement's sample output disagrees with the "
                f"reference solution.\n  sample says: {expected!r}\n  reference says: "
                f"{produced!r}\nFix one of them before shipping this package."
            )

    secret = [(data, problem.solve(data)) for data in secret_inputs]

    zip_path = out_dir / f"{problem.letter.lower()}-{problem.slug}.zip"
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("problem.yaml", (
            f"name: {problem.title}\n"
            f"limits:\n"
            f"  time_multiplier: 1\n"
            f"validation: default\n"
        ))
        z.writestr("domjudge-problem.ini", (
            f'probid = {problem.letter}\n'
            f'name = {problem.title}\n'
            f'timelimit = {problem.time_limit:g}\n'
            f'color = {problem.colour}\n'
        ))
        z.writestr("problem_statement/problem.pdf", statement_pdf(problem))

        for i, (data, answer) in enumerate(samples, start=1):
            z.writestr(f"data/sample/{i}.in", data)
            z.writestr(f"data/sample/{i}.ans", answer)

        for i, (data, answer) in enumerate(secret, start=1):
            z.writestr(f"data/secret/{i}.in", data)
            z.writestr(f"data/secret/{i}.ans", answer)

        for solution in problem.solutions:
            directory = EXPECTED_DIR[solution.expected]
            z.writestr(f"submissions/{directory}/{solution.name}.cpp",
                       solution.source.lstrip("\n"))

    return {
        "letter": problem.letter,
        "slug": problem.slug,
        "title": problem.title,
        "timeLimit": problem.time_limit,
        "zip": zip_path.name,
        "samples": len(samples),
        "secretTests": len(secret),
        "solutions": [
            {"name": s.name, "expected": s.expected} for s in problem.solutions
        ],
    }


def write_solutions(problem: Problem, solutions_dir: Path) -> None:
    """
    Drops each solution on disk as well as into the zip.

    The simulator submits from here rather than reaching into the zips: it needs the source
    text of a specific wrong solution at a specific moment, and unpacking an archive per
    submission during a load test would measure the harness rather than the server.
    """
    for solution in problem.solutions:
        path = solutions_dir / f"{problem.letter}-{solution.name}.{solution.expected}.cpp"
        path.write_text(solution.source.lstrip("\n"))


def _compile(source: str, workdir: Path, stem: str):
    """Compiles one solution. Returns (binary path or None, compiler stderr)."""
    src = workdir / f"{stem}.cpp"
    binary = workdir / f"{stem}.bin"
    src.write_text(source)
    result = subprocess.run(["g++", "-O2", "-std=c++17", "-o", str(binary), str(src)],
                            capture_output=True, text=True)
    return (binary if result.returncode == 0 else None), result.stderr


def verify(problem: Problem, tests: list, workdir: Path) -> None:
    """
    Checks every solution draws the verdict it claims.

    The accepted one must agree with the reference on all data. The wrong ones must actually
    be wrong, in the specific way they are filed under — a "TLE" submission that quietly
    passes would have the simulator asserting against a verdict that never arrives, and the
    harness would report a bug in the arena that is really a bug in the harness.
    """
    accepted = next((s for s in problem.solutions if s.expected == "AC"), None)
    if accepted is None:
        raise SystemExit(f"{problem.letter}: no accepted solution to verify")

    binary, stderr = _compile(accepted.source, workdir, f"{problem.letter}-ac")
    if binary is None:
        raise SystemExit(
            f"{problem.letter}: the accepted solution does not compile.\n" + stderr[:2000])

    for label, data in tests:
        expected = problem.solve(data)
        run = subprocess.run([str(binary)], input=data, capture_output=True,
                             text=True, timeout=60)
        if run.returncode != 0:
            raise SystemExit(
                f"{problem.letter}: accepted solution crashed on {label} "
                f"(exit {run.returncode})")
        if run.stdout.split() != expected.split():
            raise SystemExit(
                f"{problem.letter}: accepted solution disagrees with the reference on "
                f"{label}.\n  C++ gave:       {run.stdout[:200]!r}\n"
                f"  reference gave: {expected[:200]!r}")
    print(f"  {problem.letter}: accepted agrees with the reference on {len(tests)} tests")

    # The biggest test is the one a slow solution has to fail on, so time against that.
    largest = max(tests, key=lambda t: len(t[1]))

    for solution in problem.solutions:
        if solution.expected == "AC":
            continue
        _verify_wrong(problem, solution, tests, largest, workdir)


def _verify_wrong(problem: Problem, solution, tests: list, largest, workdir: Path) -> None:
    stem = f"{problem.letter}-{solution.name}"
    binary, stderr = _compile(solution.source, workdir, stem)

    if solution.expected == "CE":
        if binary is not None:
            raise SystemExit(
                f"{problem.letter}/{solution.name}: filed as a compile error but it compiles.")
        print(f"    {solution.name:<20} CE   - refused by the compiler, as intended")
        return

    if binary is None:
        raise SystemExit(
            f"{problem.letter}/{solution.name}: expected {solution.expected} but it does not "
            f"even compile.\n" + stderr[:1000])

    if solution.expected == "TLE":
        limit = problem.time_limit
        try:
            subprocess.run([str(binary)], input=largest[1], capture_output=True,
                           text=True, timeout=limit * 3)
        except subprocess.TimeoutExpired:
            print(f"    {solution.name:<20} TLE  - still running after "
                  f"{limit * 3:g}s on {largest[0]}")
            return
        raise SystemExit(
            f"{problem.letter}/{solution.name}: filed as TLE but finished {largest[0]} "
            f"within {limit * 3:g}s. The data is not big enough to separate it.")

    # WA and RTE: find a test where it actually misbehaves.
    for label, data in tests:
        expected = problem.solve(data)
        try:
            run = subprocess.run([str(binary)], input=data, capture_output=True,
                                 text=True, timeout=problem.time_limit * 4)
        except subprocess.TimeoutExpired:
            continue
        if solution.expected == "RTE" and run.returncode != 0:
            print(f"    {solution.name:<20} RTE  - exits {run.returncode} on {label}")
            return
        if solution.expected == "WA" and run.returncode == 0 \
                and run.stdout.split() != expected.split():
            print(f"    {solution.name:<20} WA   - wrong on {label}")
            return

    raise SystemExit(
        f"{problem.letter}/{solution.name}: filed as {solution.expected} but no test "
        f"provokes that. It would be judged accepted, and the simulator would be asserting "
        f"against a verdict that never comes.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", default=None,
                        help="output directory (default: scripts/simulation/out/problemset)")
    parser.add_argument("--seed", type=int, default=20260903,
                        help="seed for the test-data generator, so runs are reproducible")
    parser.add_argument("--verify", action="store_true",
                        help="also compile and run the accepted C++ solution against the data")
    args = parser.parse_args()

    here = Path(__file__).resolve().parent
    out_dir = Path(args.out) if args.out else here / "out" / "problemset"
    solutions_dir = out_dir / "solutions"

    if out_dir.exists():
        shutil.rmtree(out_dir)
    solutions_dir.mkdir(parents=True)

    print(f"Building {len(PROBLEMS)} problems into {out_dir}")
    manifest = {"seed": args.seed, "problems": []}

    for problem in PROBLEMS:
        # A fresh generator per problem, seeded from the run seed plus the letter, so adding
        # a problem does not reshuffle the data of the ones before it.
        rng = random.Random(f"{args.seed}:{problem.letter}")
        entry = build_problem(problem, out_dir, rng)
        write_solutions(problem, solutions_dir)
        manifest["problems"].append(entry)
        print(f"  {problem.letter}  {problem.title:<20} "
              f"{entry['samples']} samples, {entry['secretTests']} secret tests, "
              f"{len(entry['solutions'])} solutions")

    (out_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

    if args.verify:
        print("\nVerifying every solution draws its stated verdict (needs g++)…")
        with tempfile.TemporaryDirectory() as tmp:
            workdir = Path(tmp)
            for problem in PROBLEMS:
                rng = random.Random(f"{args.seed}:{problem.letter}")
                tests = [(f"sample {i+1}", d) for i, (d, _) in enumerate(problem.samples)]
                tests += [(f"secret {i+1}", d)
                          for i, d in enumerate(problem.secret_gen(rng))]
                verify(problem, tests, workdir)

    print(f"\nDone. Import the zips in {out_dir} through DOMjudge's jury interface:")
    print("  Jury → Problems → Import problem, one zip at a time,")
    print("  or:  for z in *.zip; do curl -u admin:PASS -F zip=@$z "
          "\"$DJ/api/v4/contests/$CID/problems\"; done")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
