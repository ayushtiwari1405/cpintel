#!/usr/bin/env python3
"""
Generates the simulated contest's participants — one roster, two systems.

    ./scripts/simulation/make_roster.py [--count 200] [--out DIR]

The whole point is that the two sides agree. A CPIntel account competes as a DOMjudge team by
having its group membership's ``externalHandle`` match that team's name, matched case- and
whitespace-insensitively. Generating the two independently is how you get a contest where
forty people are silently unmatched, so both are written from one list here.

Produces, in ``out/roster/``:

    roster.json          the list, and what CPIntel provisioning reads
    credentials.csv      username, email, password, team - for handing out or for spot checks
    domjudge/teams.tsv   classic ICPC import, Jury -> Import/Export
    domjudge/accounts.tsv    ditto, only needed if people log into DOMjudge directly
    domjudge/teams.json  the same teams for DOMjudge 8's JSON import

**On DOMjudge accounts.** CPIntel submits on each team's behalf through the deployment's admin
API account, so contestants do not need DOMjudge logins at all — the teams alone are enough to
attribute submissions and populate the scoreboard. ``accounts.tsv`` is generated anyway, for
the case where you also want people able to open DOMjudge's own UI, and can be ignored
otherwise. Some DOMjudge builds do want each team to have a user before it will accept a
submission on its behalf; if the probe reports that, import the accounts too.

Passwords are derived from a seed rather than random, so re-running this reproduces the same
roster instead of orphaning the accounts already created from the last one.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import shutil
from pathlib import Path

# Deliberately dull and sortable. A load test is read as a list far more often than it is read
# as prose, and "sim007 / Team 007" collates properly where a name generator would not.
USERNAME_FMT = "sim{n:03d}"
TEAM_FMT = "Team {n:03d}"
EMAIL_FMT = "sim{n:03d}@sim.cpintel.local"
DJ_USER_FMT = "team{n:03d}"


def password_for(seed: str, kind: str, n: int) -> str:
    """
    A deterministic password, so a re-run produces the same roster.

    Derived rather than stored: nothing here is a secret worth protecting — these are throwaway
    accounts on a test deployment — but they still have to satisfy CPIntel's eight-character
    minimum and be reproducible from the seed alone.
    """
    digest = hashlib.sha256(f"{seed}:{kind}:{n}".encode()).hexdigest()
    # Mixed case and a digit, so it satisfies any policy that gets stricter later.
    return f"Sim{digest[:10]}!"


def build(count: int, seed: str) -> list[dict]:
    return [
        {
            "n": n,
            "username": USERNAME_FMT.format(n=n),
            "email": EMAIL_FMT.format(n=n),
            "password": password_for(seed, "cpintel", n),
            "fullName": f"Simulated Contestant {n:03d}",
            "team": TEAM_FMT.format(n=n),
            "djUsername": DJ_USER_FMT.format(n=n),
            "djPassword": password_for(seed, "domjudge", n),
        }
        for n in range(1, count + 1)
    ]


def write_domjudge(roster: list[dict], out: Path, group_id: int) -> None:
    """
    DOMjudge import files, in both the formats its versions accept.

    The TSVs are the classic ICPC contest-control format that every DOMjudge reads through
    Jury -> Import/Export. The JSON is the shape DOMjudge 8 takes over its API. Which one your
    instance prefers is exactly the sort of thing ``domjudge-probe.sh`` answers.
    """
    out.mkdir(parents=True, exist_ok=True)

    # Written by hand rather than through csv: the ICPC TSV format quotes nothing and escapes
    # nothing, which is a rule the csv module cannot quite be talked into expressing.
    def tsv(rows: list[list]) -> str:
        return "".join("\t".join(str(cell) for cell in row) + "\n" for row in rows)

    # teams.tsv — version line, then: teamid, external id, category, team name,
    # institution name, institution short name, country.
    (out / "teams.tsv").write_text(tsv(
        [["teams", "1"]] + [
            [
                row["n"],                 # team id
                row["n"],                 # external id
                group_id,                 # category / team group
                row["team"],              # the name CPIntel matches on
                "CPIntel Simulation",     # institution
                "SIM",                    # institution short name
                "NLD",                    # country: arbitrary, DOMjudge wants something
            ]
            for row in roster
        ]))

    # accounts.tsv — version line, then: role, display name, username, password.
    (out / "accounts.tsv").write_text(tsv(
        [["accounts", "1"]] + [
            ["team", row["team"], row["djUsername"], row["djPassword"]]
            for row in roster
        ]))

    (out / "teams.json").write_text(json.dumps([
        {
            "id": str(row["n"]),
            "name": row["team"],
            "display_name": row["team"],
            "group_ids": [str(group_id)],
        }
        for row in roster
    ], indent=2) + "\n")

    (out / "accounts.json").write_text(json.dumps([
        {
            "id": row["djUsername"],
            "username": row["djUsername"],
            "name": row["team"],
            "password": row["djPassword"],
            "type": "team",
            "team_id": str(row["n"]),
        }
        for row in roster
    ], indent=2) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--count", type=int, default=200,
                        help="how many participants (default 200)")
    parser.add_argument("--seed", default="cpintel-sim-2026",
                        help="seed for deterministic passwords")
    parser.add_argument("--group-id", type=int, default=3,
                        help="DOMjudge team category id to file the teams under "
                             "(3 is 'Participants' on a default install)")
    parser.add_argument("--out", default=None,
                        help="output directory (default: scripts/simulation/out/roster)")
    args = parser.parse_args()

    here = Path(__file__).resolve().parent
    out = Path(args.out) if args.out else here / "out" / "roster"
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)

    roster = build(args.count, args.seed)

    (out / "roster.json").write_text(json.dumps({
        "seed": args.seed,
        "count": args.count,
        "djGroupId": args.group_id,
        "participants": roster,
    }, indent=2) + "\n")

    with (out / "credentials.csv").open("w", newline="") as fh:
        writer = csv.writer(fh)
        writer.writerow(["username", "email", "password", "domjudgeTeam",
                         "domjudgeUser", "domjudgePassword"])
        for row in roster:
            writer.writerow([row["username"], row["email"], row["password"],
                             row["team"], row["djUsername"], row["djPassword"]])

    write_domjudge(roster, out / "domjudge", args.group_id)

    print(f"{args.count} participants written to {out}")
    print(f"  {roster[0]['username']} / {roster[0]['team']}  …  "
          f"{roster[-1]['username']} / {roster[-1]['team']}")
    print()
    print("Next:")
    print(f"  1. DOMjudge → Jury → Import/Export → import {out}/domjudge/teams.tsv")
    print("     (accounts.tsv only if contestants also need to log into DOMjudge itself)")
    print("  2. Import the problem zips into the contest")
    print(f"  3. ./scripts/simulation/provision.py --roster {out}/roster.json …")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
