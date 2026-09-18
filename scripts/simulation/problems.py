"""
The simulated contest's problem set.

Six problems, easy to hard, each with a reference solution and a set of deliberately wrong
ones. The wrong solutions are the point as much as the right ones: a load test that only ever
submits correct code exercises exactly one path through the verdict pipeline and tells you
nothing about what the arena does with a compile error at minute forty.

Everything here is deterministic. Test data comes from a seeded generator, so the same package
is produced on every run and a failure is reproducible rather than a story about one afternoon.

Each problem carries:

  * a statement, rendered to PDF for DOMjudge's problem package
  * ``samples``  — shown to contestants, and seeded into CPIntel's testcase console
  * ``secret``   — what the contest is actually judged on
  * ``solutions`` — one accepted, plus wrong ones tagged with the verdict they should draw

The reference implementations are also what the harness uses to compute expected answers, so
the answer files cannot drift from the statement: there is one implementation of each problem's
rules, in Python, and the C++ submission is checked against it by ``make_problemset.py``.
"""

from __future__ import annotations

import random
from dataclasses import dataclass, field
from typing import Callable, Dict, List, Tuple


@dataclass
class Solution:
    """One submission the simulator can send, and what it is expected to draw."""
    name: str
    #: DOMjudge's judgement type — AC, WA, TLE, RTE, CE. Used to name the package directory
    #: and to check the simulator saw what it expected.
    expected: str
    source: str


@dataclass
class Problem:
    letter: str
    slug: str
    title: str
    #: Seconds. Generous enough that a correct solution never fails on a busy judge, tight
    #: enough that the deliberate TLE submissions actually time out.
    time_limit: float
    colour: str
    statement: str
    input_spec: str
    output_spec: str
    notes: str = ""
    samples: List[Tuple[str, str]] = field(default_factory=list)
    #: Builds the secret input files. Takes a seeded Random, returns a list of input strings.
    secret_gen: Callable[[random.Random], List[str]] = None  # type: ignore[assignment]
    #: The reference implementation of the rules, in Python. The single source of truth for
    #: every answer file.
    solve: Callable[[str], str] = None  # type: ignore[assignment]
    solutions: List[Solution] = field(default_factory=list)


# --------------------------------------------------------------------------- A

A_REF = r"""
#include <bits/stdc++.h>
int main(){int t;if(scanf("%d",&t)!=1)return 0;while(t--){long long a,b;scanf("%lld %lld",&a,&b);printf("%lld\n",a+b);}return 0;}
"""

A_WA = r"""
#include <bits/stdc++.h>
// Subtracts instead of adding. Passes the first sample by luck when a == 2b.
int main(){int t;if(scanf("%d",&t)!=1)return 0;while(t--){long long a,b;scanf("%lld %lld",&a,&b);printf("%lld\n",a-b);}return 0;}
"""

A_CE = r"""
#include <bits/stdc++.h>
int main(){
    int t; scanf("%d", &t)
    // Missing semicolon above: this must not compile.
    return 0;
}
"""

A_RTE = r"""
#include <bits/stdc++.h>
int main(){int t;if(scanf("%d",&t)!=1)return 0;int z=0;while(t--){long long a,b;scanf("%lld %lld",&a,&b);printf("%lld\n",(a+b)/z);}return 0;}
"""


def a_secret(rng: random.Random) -> List[str]:
    files = []
    # A file of small values, one of extremes, and three random ones — the shapes a wrong
    # solution is most likely to survive if the data is bland.
    files.append("3\n0 0\n1 -1\n-5 5\n")
    files.append("2\n1000000000000 1000000000000\n-1000000000000 -1000000000000\n")
    for _ in range(3):
        n = rng.randint(50, 200)
        rows = [f"{rng.randint(-10**12, 10**12)} {rng.randint(-10**12, 10**12)}"
                for _ in range(n)]
        files.append(f"{n}\n" + "\n".join(rows) + "\n")
    return files


def a_solve(data: str) -> str:
    it = data.split()
    t = int(it[0])
    out = []
    for i in range(t):
        a = int(it[1 + 2 * i])
        b = int(it[2 + 2 * i])
        out.append(str(a + b))
    return "\n".join(out) + "\n"


# --------------------------------------------------------------------------- B

B_REF = r"""
#include <bits/stdc++.h>
int main(){std::string s;if(!std::getline(std::cin,s))return 0;long long c=0;for(char ch:s){char l=std::tolower((unsigned char)ch);if(l=='a'||l=='e'||l=='i'||l=='o'||l=='u')c++;}std::cout<<c<<"\n";return 0;}
"""

B_WA = r"""
#include <bits/stdc++.h>
// Forgets that the input is mixed case, so every capital vowel is missed.
int main(){std::string s;if(!std::getline(std::cin,s))return 0;long long c=0;for(char ch:s){if(ch=='a'||ch=='e'||ch=='i'||ch=='o'||ch=='u')c++;}std::cout<<c<<"\n";return 0;}
"""


def b_secret(rng: random.Random) -> List[str]:
    alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ "
    files = ["BCDFG\n", "AEIOUaeiou\n"]
    for _ in range(3):
        n = rng.randint(1000, 5000)
        files.append("".join(rng.choice(alphabet) for _ in range(n)).strip() + "\n")
    return files


def b_solve(data: str) -> str:
    line = data.split("\n")[0]
    return str(sum(1 for ch in line if ch.lower() in "aeiou")) + "\n"


# --------------------------------------------------------------------------- C

C_REF = r"""
#include <bits/stdc++.h>
int main(){int n;if(scanf("%d",&n)!=1)return 0;long long best=LLONG_MIN,cur=0;for(int i=0;i<n;i++){long long x;scanf("%lld",&x);cur=std::max(x,cur+x);best=std::max(best,cur);}printf("%lld\n",best);return 0;}
"""

C_TLE = r"""
#include <bits/stdc++.h>
// The obvious quadratic scan. Correct, and far too slow once n reaches 200000.
int main(){int n;if(scanf("%d",&n)!=1)return 0;std::vector<long long>v(n);for(auto&x:v)scanf("%lld",&x);long long best=LLONG_MIN;for(int i=0;i<n;i++){long long s=0;for(int j=i;j<n;j++){s+=v[j];best=std::max(best,s);}}printf("%lld\n",best);return 0;}
"""

C_WA = r"""
#include <bits/stdc++.h>
// Clamps the running sum at zero, which is right until every number is negative — then it
// answers 0 for a segment that must be non-empty.
int main(){int n;if(scanf("%d",&n)!=1)return 0;long long best=0,cur=0;for(int i=0;i<n;i++){long long x;scanf("%lld",&x);cur=std::max(0LL,cur+x);best=std::max(best,cur);}printf("%lld\n",best);return 0;}
"""


def c_secret(rng: random.Random) -> List[str]:
    files = []
    # All-negative: the case the clamping bug dies on, and the one people forget.
    files.append("5\n-8 -3 -9 -2 -7\n")
    for size in (2000, 200000):
        vals = [rng.randint(-10**6, 10**6) for _ in range(size)]
        files.append(f"{size}\n" + " ".join(map(str, vals)) + "\n")
    # Large and all-negative, so the quadratic solution has nowhere to hide either.
    vals = [rng.randint(-10**6, -1) for _ in range(200000)]
    files.append("200000\n" + " ".join(map(str, vals)) + "\n")
    return files


def c_solve(data: str) -> str:
    it = data.split()
    n = int(it[0])
    best = None
    cur = 0
    for i in range(n):
        x = int(it[1 + i])
        cur = x if cur + x < x else cur + x
        best = cur if best is None or cur > best else best
    return f"{best}\n"


# --------------------------------------------------------------------------- D

D_REF = r"""
#include <bits/stdc++.h>
int main(){int n;if(scanf("%d",&n)!=1)return 0;std::vector<long long>v(n);for(auto&x:v)scanf("%lld",&x);std::sort(v.begin(),v.end());printf("%lld\n",v[(n-1)/2]);return 0;}
"""

D_WA = r"""
#include <bits/stdc++.h>
// Takes the upper median on even n, where the statement asks for the lower.
int main(){int n;if(scanf("%d",&n)!=1)return 0;std::vector<long long>v(n);for(auto&x:v)scanf("%lld",&x);std::sort(v.begin(),v.end());printf("%lld\n",v[n/2]);return 0;}
"""


def d_secret(rng: random.Random) -> List[str]:
    files = ["4\n4 1 3 2\n", "1\n7\n"]
    for size in (999, 100000):
        vals = [rng.randint(-10**9, 10**9) for _ in range(size)]
        files.append(f"{size}\n" + " ".join(map(str, vals)) + "\n")
    return files


def d_solve(data: str) -> str:
    it = data.split()
    n = int(it[0])
    vals = sorted(int(x) for x in it[1:1 + n])
    return f"{vals[(n - 1) // 2]}\n"


# --------------------------------------------------------------------------- E

E_REF = r"""
#include <bits/stdc++.h>
int main(){int n;if(scanf("%d",&n)!=1)return 0;long long g=0;for(int i=0;i<n;i++){long long x;scanf("%lld",&x);g=std::__gcd(g,x);}printf("%lld\n",g);return 0;}
"""

E_WA = r"""
#include <bits/stdc++.h>
// Seeds the fold with the first value but then folds it in again from index 0 — harmless —
// while starting the accumulator at 1, which makes every answer 1.
int main(){int n;if(scanf("%d",&n)!=1)return 0;long long g=1;for(int i=0;i<n;i++){long long x;scanf("%lld",&x);g=std::__gcd(g,x);}printf("%lld\n",g);return 0;}
"""


def e_secret(rng: random.Random) -> List[str]:
    files = ["3\n12 18 24\n", "2\n1000000000000 500000000000\n"]
    for _ in range(3):
        n = rng.randint(100, 1000)
        base = rng.choice([2, 3, 6, 7, 12, 100])
        vals = [base * rng.randint(1, 10**9) for _ in range(n)]
        files.append(f"{n}\n" + " ".join(map(str, vals)) + "\n")
    return files


def e_solve(data: str) -> str:
    from math import gcd
    it = data.split()
    n = int(it[0])
    g = 0
    for x in it[1:1 + n]:
        g = gcd(g, int(x))
    return f"{g}\n"


# --------------------------------------------------------------------------- F

F_REF = r"""
#include <bits/stdc++.h>
int main(){int r,c;if(scanf("%d %d",&r,&c)!=2)return 0;std::vector<std::string>g(r);for(auto&s:g)std::cin>>s;
std::vector<std::vector<int>>d(r,std::vector<int>(c,-1));std::deque<std::pair<int,int>>q;d[0][0]=0;q.push_back({0,0});
int dx[]={1,-1,0,0},dy[]={0,0,1,-1};
while(!q.empty()){auto[x,y]=q.front();q.pop_front();for(int k=0;k<4;k++){int nx=x+dx[k],ny=y+dy[k];
if(nx<0||ny<0||nx>=r||ny>=c)continue;if(g[nx][ny]=='#')continue;if(d[nx][ny]!=-1)continue;d[nx][ny]=d[x][y]+1;q.push_back({nx,ny});}}
printf("%d\n",d[r-1][c-1]);return 0;}
"""

F_WA = r"""
#include <bits/stdc++.h>
// Only ever steps right or down. Correct on any grid whose shortest route happens to be
// monotone - which is most of them, and both samples - and wrong the moment the route has to
// double back. The classic version of this bug.
int main(){int r,c;if(scanf("%d %d",&r,&c)!=2)return 0;std::vector<std::string>g(r);for(auto&s:g)std::cin>>s;
std::vector<std::vector<int>>d(r,std::vector<int>(c,-1));std::deque<std::pair<int,int>>q;d[0][0]=0;q.push_back({0,0});
int dx[]={1,0},dy[]={0,1};
while(!q.empty()){auto[x,y]=q.front();q.pop_front();for(int k=0;k<2;k++){int nx=x+dx[k],ny=y+dy[k];
if(nx<0||ny<0||nx>=r||ny>=c)continue;if(g[nx][ny]=='#')continue;if(d[nx][ny]!=-1)continue;d[nx][ny]=d[x][y]+1;q.push_back({nx,ny});}}
printf("%d\n",d[r-1][c-1]);return 0;}
"""


def f_secret(rng: random.Random) -> List[str]:
    files = []
    # An open grid, where the answer is exactly the Manhattan distance.
    files.append("3 3\n...\n...\n...\n")
    # A serpentine corridor: the only route runs right, then back left, then right again, so
    # anything that can only step right and down never reaches the corner at all. Both the
    # start and the finish are open, as the statement promises.
    files.append("5 5\n.....\n####.\n.....\n.####\n.....\n")
    for _ in range(3):
        r, c = rng.randint(40, 90), rng.randint(40, 90)
        while True:
            grid = [
                "".join("#" if rng.random() < 0.22 else "." for _ in range(c))
                for _ in range(r)
            ]
            grid[0] = "." + grid[0][1:]
            grid[r - 1] = grid[r - 1][:c - 1] + "."
            if _reachable(grid, r, c):
                break
        files.append(f"{r} {c}\n" + "\n".join(grid) + "\n")
    return files


def _reachable(grid: List[str], r: int, c: int) -> bool:
    return _bfs(grid, r, c) != -1


def _bfs(grid: List[str], r: int, c: int) -> int:
    from collections import deque
    dist = [[-1] * c for _ in range(r)]
    dist[0][0] = 0
    q = deque([(0, 0)])
    while q:
        x, y = q.popleft()
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, ny = x + dx, y + dy
            if 0 <= nx < r and 0 <= ny < c and grid[nx][ny] != "#" and dist[nx][ny] == -1:
                dist[nx][ny] = dist[x][y] + 1
                q.append((nx, ny))
    return dist[r - 1][c - 1]


def f_solve(data: str) -> str:
    lines = data.strip("\n").split("\n")
    r, c = map(int, lines[0].split())
    grid = lines[1:1 + r]
    return f"{_bfs(grid, r, c)}\n"


# --------------------------------------------------------------------------- set

PROBLEMS: List[Problem] = [
    Problem(
        letter="A", slug="sum-of-two", title="Sum of Two", time_limit=2.0, colour="#2E86DE",
        statement=(
            "Ada is checking a ledger. Each line of the ledger holds two whole numbers, and "
            "she needs their total. The numbers can be negative — a credit and a debit look "
            "the same on this ledger — and they can be large enough that a 32-bit integer "
            "will not hold the answer."
        ),
        input_spec=(
            "The first line contains an integer t, the number of ledger lines "
            "(1 <= t <= 200). Each of the next t lines contains two integers a and b "
            "(-10^12 <= a, b <= 10^12)."
        ),
        output_spec="For each ledger line, print a + b on its own line.",
        samples=[("3\n1 2\n-5 5\n1000000000000 1\n", "3\n0\n1000000000001\n")],
        secret_gen=a_secret, solve=a_solve,
        solutions=[
            Solution("accepted", "AC", A_REF),
            Solution("subtracts", "WA", A_WA),
            Solution("missing-semicolon", "CE", A_CE),
            Solution("divides-by-zero", "RTE", A_RTE),
        ],
    ),
    Problem(
        letter="B", slug="vowel-count", title="Vowel Count", time_limit=2.0, colour="#10AC84",
        statement=(
            "Given one line of text, count how many of its characters are vowels. The five "
            "vowels are a, e, i, o and u, in either case. Everything else — consonants, "
            "spaces, digits, punctuation — is not a vowel."
        ),
        input_spec=(
            "A single line containing up to 5000 characters. The line may contain spaces."
        ),
        output_spec="Print one integer: the number of vowels on the line.",
        samples=[("Hello World\n", "3\n"), ("AEIOUaeiou\n", "10\n")],
        secret_gen=b_secret, solve=b_solve,
        solutions=[
            Solution("accepted", "AC", B_REF),
            Solution("case-sensitive", "WA", B_WA),
        ],
    ),
    Problem(
        letter="C", slug="maximum-segment", title="Maximum Segment", time_limit=2.0,
        colour="#EE5A24",
        statement=(
            "A sensor logs a reading every second, and a reading may be negative. Find the "
            "largest total any run of consecutive readings can produce.\n\n"
            "The run must contain at least one reading. That matters: if every reading is "
            "negative, the answer is the least bad single reading, not zero."
        ),
        input_spec=(
            "The first line contains n (1 <= n <= 200000). The second line contains n "
            "integers, each between -10^6 and 10^6."
        ),
        output_spec="Print the largest sum obtainable from a non-empty run of readings.",
        notes=(
            "In the second sample every reading is negative, so the best available run is the "
            "single reading -2."
        ),
        samples=[
            ("9\n-2 1 -3 4 -1 2 1 -5 4\n", "6\n"),
            ("5\n-8 -3 -9 -2 -7\n", "-2\n"),
        ],
        secret_gen=c_secret, solve=c_solve,
        solutions=[
            Solution("accepted", "AC", C_REF),
            Solution("quadratic", "TLE", C_TLE),
            Solution("clamps-at-zero", "WA", C_WA),
        ],
    ),
    Problem(
        letter="D", slug="lower-median", title="Lower Median", time_limit=2.0, colour="#8E44AD",
        statement=(
            "Given a list of numbers, report its lower median: sort the list, then take the "
            "element at position floor((n-1)/2), counting from zero.\n\n"
            "For an odd-length list this is the middle element. For an even-length list it is "
            "the lower of the two middle elements."
        ),
        input_spec=(
            "The first line contains n (1 <= n <= 100000). The second line contains n "
            "integers, each between -10^9 and 10^9."
        ),
        output_spec="Print the lower median.",
        samples=[("4\n4 1 3 2\n", "2\n"), ("5\n5 4 3 2 1\n", "3\n")],
        secret_gen=d_secret, solve=d_solve,
        solutions=[
            Solution("accepted", "AC", D_REF),
            Solution("upper-median", "WA", D_WA),
        ],
    ),
    Problem(
        letter="E", slug="common-measure", title="Common Measure", time_limit=2.0,
        colour="#F79F1F",
        statement=(
            "A workshop cuts rods to a set of lengths. It wants the longest single measure "
            "that divides every one of those lengths exactly, so that every rod can be cut "
            "into a whole number of pieces of that measure.\n\n"
            "That is the greatest common divisor of the whole list."
        ),
        input_spec=(
            "The first line contains n (1 <= n <= 1000). The second line contains n integers, "
            "each between 1 and 10^12."
        ),
        output_spec="Print the greatest common divisor of the n numbers.",
        samples=[("3\n12 18 24\n", "6\n"), ("2\n17 5\n", "1\n")],
        secret_gen=e_secret, solve=e_solve,
        solutions=[
            Solution("accepted", "AC", E_REF),
            Solution("seeds-with-one", "WA", E_WA),
        ],
    ),
    Problem(
        letter="F", slug="grid-escape", title="Grid Escape", time_limit=3.0, colour="#C0392B",
        statement=(
            "You are standing in the top-left cell of a rectangular grid and want to reach the "
            "bottom-right cell. Some cells are walls and cannot be entered. From a cell you "
            "may step to any of the four cells sharing an edge with it.\n\n"
            "Report the fewest steps needed. The start and finish are always open, but there "
            "may be no route at all."
        ),
        input_spec=(
            "The first line contains r and c (1 <= r, c <= 100). Each of the next r lines "
            "contains c characters: '.' for an open cell and '#' for a wall."
        ),
        output_spec=(
            "Print the fewest steps from the top-left to the bottom-right cell, or -1 if the "
            "bottom-right cell cannot be reached."
        ),
        notes="A single-cell grid needs no steps at all, so the answer there is 0.",
        samples=[
            ("3 3\n...\n.#.\n...\n", "4\n"),
            ("2 2\n.#\n#.\n", "-1\n"),
        ],
        secret_gen=f_secret, solve=f_solve,
        solutions=[
            Solution("accepted", "AC", F_REF),
            Solution("right-and-down-only", "WA", F_WA),
        ],
    ),
]


def by_letter() -> Dict[str, Problem]:
    return {p.letter: p for p in PROBLEMS}
