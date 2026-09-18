#!/usr/bin/env python3
"""
Creates the simulated contest inside CPIntel: accounts, a group, its members, and the contest.

    ./scripts/simulation/provision.py \\
        --api http://localhost:8080/api/v1 \\
        --admin-email you@example.com --admin-password ... \\
        --roster scripts/simulation/out/roster/roster.json \\
        --contest-id nwerc18 --contest-name "Simulation Round" \\
        --starts-in 2 --duration 180

What it does, in order:

1. Signs in as the super admin.
2. Creates every account on the roster. An account that already exists is looked up and reused
   rather than reported as a failure.
3. Creates (or reuses) the group.
4. Adds each account to the group with ``externalHandle`` set to its DOMjudge team name. This
   is the mapping the whole DOMjudge path hangs off: it is how a CPIntel account is resolved to
   a team when submitting, and how the standings board finds its members.
5. Adds the DOMjudge contest to the group, with a start and end window.

**Idempotent on purpose.** Provisioning 200 accounts is exactly the sort of job that dies
two-thirds of the way through the first time, and a script that cannot be re-run leaves you
manually reconciling 130 half-created accounts. Every step here tolerates "already exists".

The window matters more than it looks. CPIntel accepts monitoring reports only inside the
contest window plus a short grace, and the group board only refreshes contests it considers
live, so a window that does not match DOMjudge's own produces a contest that looks fine and
quietly records nothing.
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from simclient import Client, HttpError   # noqa: E402


def iso(moment: datetime) -> str:
    return moment.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def sign_in(client: Client, email: str, password: str) -> str:
    response = client.post("/auth/login", body={"email": email, "password": password})
    if not response.ok:
        raise SystemExit(
            f"Could not sign in as {email}: {response.message()}\n"
            "The super admin is the account named by CPINTEL_ADMIN_EMAIL.")
    data = response.data()
    return data["accessToken"]


def existing_users(client: Client, token: str) -> dict:
    """
    Every account already on the deployment, keyed by username.

    Fetched once and in pages rather than probed per roster entry: 200 lookups against a
    deployment that may already hold 200 accounts is 40,000 comparisons done server-side for
    no reason.
    """
    found = {}
    page = 0
    while True:
        response = client.get(f"/admin/users?page={page}&size=200", token=token)
        data = client.expect(response, "listing accounts")
        for row in data.get("users", []):
            found[row["username"]] = row
        if page + 1 >= data.get("totalPages", 1):
            break
        page += 1
    return found


def create_accounts(client: Client, token: str, roster: list, verbose: bool) -> dict:
    """Returns username -> userId for every roster entry."""
    known = existing_users(client, token)
    ids = {}
    created = reused = 0

    for entry in roster:
        username = entry["username"]
        if username in known:
            ids[username] = known[username]["userId"]
            reused += 1
            continue

        response = client.post("/admin/users", token=token, body={
            "username": username,
            "email": entry["email"],
            "password": entry["password"],
            "fullName": entry["fullName"],
            "role": "USER",
        })

        if response.ok:
            ids[username] = response.data()["userId"]
            created += 1
        elif response.status == 409:
            # Created between the listing above and now, or the email is taken by an account
            # under a different username. Re-read rather than guess.
            refreshed = existing_users(client, token)
            if username in refreshed:
                ids[username] = refreshed[username]["userId"]
                reused += 1
            else:
                raise SystemExit(
                    f"{username}: refused as a conflict but does not exist under that name. "
                    f"Its email ({entry['email']}) is probably taken by another account. "
                    f"{response.message()}")
        else:
            raise HttpError(response, f"creating {username}")

        if verbose and (created + reused) % 25 == 0:
            print(f"    {created + reused}/{len(roster)}…")

    print(f"  accounts: {created} created, {reused} already existed")
    return ids


def ensure_group(client: Client, token: str, name: str) -> int:
    groups = client.expect(client.get("/admin/groups", token=token), "listing groups")
    for group in groups:
        if group["name"].strip().lower() == name.strip().lower():
            print(f"  group: reusing '{name}' (id {group['groupId']})")
            return group["groupId"]

    created = client.expect(
        client.post("/admin/groups", token=token,
                    body={"name": name, "description": "Generated by the load simulation."}),
        "creating the group")
    print(f"  group: created '{name}' (id {created['groupId']})")
    return created["groupId"]


def add_members(client: Client, token: str, group_id: int, roster: list,
                ids: dict, verbose: bool) -> None:
    detail = client.expect(client.get(f"/admin/groups/{group_id}", token=token),
                           "reading the group")
    already = {member["userId"] for member in detail.get("members", [])}

    added = updated = 0
    for i, entry in enumerate(roster, start=1):
        user_id = ids[entry["username"]]
        payload = {"userId": user_id, "externalHandle": entry["team"]}

        if user_id in already:
            # Already a member, but the team name may have changed since — a re-run after
            # renaming teams in DOMjudge has to fix the mapping, not skip it.
            response = client.request(
                "PUT", f"/admin/groups/{group_id}/members/{user_id}",
                token=token, body=payload)
            if not response.ok:
                raise HttpError(response, f"updating {entry['username']}")
            updated += 1
        else:
            response = client.post(f"/admin/groups/{group_id}/members",
                                   token=token, body=payload)
            if response.ok:
                added += 1
            elif response.status == 409:
                updated += 1
            else:
                raise HttpError(response, f"adding {entry['username']}")

        if verbose and i % 25 == 0:
            print(f"    {i}/{len(roster)}…")

    print(f"  members: {added} added, {updated} already present (team names refreshed)")


def ensure_contest(client: Client, token: str, group_id: int, external_id: str,
                   name: str, starts_at: datetime, ends_at: datetime,
                   lockdown: bool, url: str) -> int:
    detail = client.expect(client.get(f"/admin/groups/{group_id}", token=token),
                           "reading the group")
    for contest in detail.get("contests", []):
        if contest["platform"] == "DOMJUDGE" and contest["externalId"] == external_id:
            print(f"  contest: reusing DOMJUDGE/{external_id} (id {contest['contestId']})")
            print(f"           window {contest['startsAt']} → {contest['endsAt']}")
            print("           delete and re-run if the window needs to change")
            return contest["contestId"]

    created = client.expect(
        client.post(f"/admin/groups/{group_id}/contests", token=token, body={
            "platform": "DOMJUDGE",
            "externalId": external_id,
            "name": name,
            "url": url or None,
            "startsAt": iso(starts_at),
            "endsAt": iso(ends_at),
            "lockdownRequired": lockdown,
        }),
        "adding the contest")
    print(f"  contest: created DOMJUDGE/{external_id} (id {created['contestId']})")
    print(f"           window {iso(starts_at)} → {iso(ends_at)}")
    return created["contestId"]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--api", default="http://localhost:8080/api/v1")
    parser.add_argument("--admin-email", required=True)
    parser.add_argument("--admin-password", required=True)
    parser.add_argument("--roster", required=True)
    parser.add_argument("--group-name", default="Simulation Cohort")
    parser.add_argument("--contest-id", required=True,
                        help="the DOMjudge contest id, e.g. nwerc18 or 3")
    parser.add_argument("--contest-name", default="Simulation Round")
    parser.add_argument("--contest-url", default="")
    parser.add_argument("--starts-in", type=float, default=2,
                        help="minutes from now until the contest window opens (default 2)")
    parser.add_argument("--duration", type=float, default=180,
                        help="contest length in minutes (default 180)")
    parser.add_argument("--no-lockdown", action="store_true",
                        help="do not ask contestants' clients to report focus loss")
    parser.add_argument("--limit", type=int, default=0,
                        help="only provision the first N of the roster, for a smaller run")
    parser.add_argument("--insecure", action="store_true",
                        help="skip TLS verification, for a self-signed test certificate")
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args()

    roster_doc = json.loads(Path(args.roster).read_text())
    roster = roster_doc["participants"]
    if args.limit:
        roster = roster[:args.limit]

    client = Client(args.api, timeout=60, verify_tls=not args.insecure)

    print(f"Provisioning {len(roster)} participants against {args.api}")
    token = sign_in(client, args.admin_email, args.admin_password)

    ids = create_accounts(client, token, roster, args.verbose)
    group_id = ensure_group(client, token, args.group_name)
    add_members(client, token, group_id, roster, ids, args.verbose)

    starts_at = datetime.now(timezone.utc) + timedelta(minutes=args.starts_in)
    ends_at = starts_at + timedelta(minutes=args.duration)
    contest_row = ensure_contest(
        client, token, group_id, args.contest_id, args.contest_name,
        starts_at, ends_at, not args.no_lockdown, args.contest_url)

    out = Path(args.roster).parent / "provisioned.json"
    out.write_text(json.dumps({
        "api": args.api,
        "groupId": group_id,
        "groupContestId": contest_row,
        "domjudgeContestId": args.contest_id,
        "startsAt": iso(starts_at),
        "endsAt": iso(ends_at),
        "participants": len(roster),
    }, indent=2) + "\n")

    print(f"\nWrote {out}")
    print("\nNext:")
    print(f"  ./scripts/simulation/simulate.py --api {args.api} \\")
    print(f"      --roster {args.roster} --contest {args.contest_id} --users {len(roster)}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except HttpError as e:
        print(f"\n{e}", file=sys.stderr)
        raise SystemExit(1)
