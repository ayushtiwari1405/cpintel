# Before an exam: rehearsal and checks

What to check on the real server, in order, a few days before a paper and again the morning of
it. Every command is read-only unless it says otherwise. These are the things the load tests
and code review could not settle, because they depend on the college's network, servers and
judge.

## 1. Does the server see each machine's address? (do this first)

Behind Cloudflare or another proxy, nginx sees the proxy's address, and the per-address rate
limits then treat a whole room as one person: sign-ins fail with "slow down" at the bell.

1. From a lab machine, sign in with any account.
2. On the server: `docker logs --tail 20 cpintel-nginx`. The first column is the address nginx
   used. It should be that machine's (or the lab's NAT address), not a Cloudflare or proxy one.
3. If it is the proxy's: set `CPINTEL_REAL_IP_FROM` (Cloudflare's ranges are at
   cloudflare.com/ips) and `docker compose up -d nginx`; repeat step 2. Also set
   `CPINTEL_LAB_NETWORKS` if the lab shares one NAT address. See `docs/deployment.md`.

## 2. Rehearsal with real machines

About twenty lab machines, a throwaway classroom and a paper of a few minutes with one easy
problem. Everyone: sign in with the slip, unlock with the room password, Run, Submit, watch the
verdict. Write down:

- how long sign-in, unlock and the first paper load take at the same moment;
- Run time with everybody pressing Run together (the runner has 3 cores and queues runs for
  20 s; past that a run fails with "took too long");
- submit → verdict time on the college's judge (the judge, not CPIntel, set this in the load
  test: about 6 minutes at 400 candidates on a laptop judge);
- any 429 in `docker logs cpintel-nginx | grep ' 429 '` (should be none).

Size the real exam from these numbers, not from the load test: the judge and the runner are the
limits. Ask the college to size the judgehosts for the room.

## 3. The two servers agree on the time

CPIntel enforces the window with its own clock; DOMjudge with its. If they differ, one accepts
a submission the other refuses. On both: `timedatectl` should say `System clock synchronized:
yes`. Never tested here: if they ever differ by more than a few seconds, fix NTP first.

## 4. Backups and monitoring are actually running

- Cron: `sudo crontab -l | grep backup.sh` shows the nightly line from the top of
  `scripts/backup.sh`.
- A recent pair: `ls -lt /var/backups/cpintel | head`. Newest stamp under 24 hours, both
  Postgres and Mongo files present.
- Off the server: `CPINTEL_BACKUP_REMOTE` is set in `.env`, and the latest dump is on that
  machine. Unset means a dead disk loses everything.
- Free disk: `df -h` on the volume Docker and `/var/backups` use; keep well over 20% free.
- Monitoring is off by default. For the day: `docker compose --profile monitoring up -d`
  (`docs/operations.md`), then check Prometheus's alerts page is empty.

## 5. During the paper

- Do not deploy or restart the backend: it is down for about 30 s.
- If the judge stops answering, CPIntel now refuses its calls at once for 10 s at a time (the
  log says "did not answer 5 times in a row"), so the rest of the app stays up. Submissions
  and verdicts resume by themselves when the judge is back.
- If the college's judge falls behind, tell candidates not to resubmit: each resubmit is more
  work for it.
