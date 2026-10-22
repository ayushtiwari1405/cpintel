#!/usr/bin/env python3
"""
Read-only checks of a deployment's settings before ./deploy.sh, on the server or anywhere else.

    python3 -I scripts/deploy-preflight.py                      # .env beside docker-compose.yml
    python3 -I scripts/deploy-preflight.py --env-file /opt/cpintel/.env --version <commit sha>

Prints one line per check - PASS, WARN or FAIL - naming settings but never their values, and
exits 1 if anything FAILed. It reads the settings file, the repository's own files and, if the
docker CLI is there, `docker compose config` (which reads files only and talks to no daemon).

What it never does: connect to a database or to Docker's daemon, start or stop anything, pull or
push an image, back up, restore, migrate or deploy. It cannot see a server's data, so it cannot
tell whether MongoDB needs the index upgrade, and it does not check DNS, certificates on the
wire, ports or reachability: those are for the server itself (README, "Deploying to a server").
Passing it means the settings look complete, not that a deployment will work.

The rules match what the backend refuses at start under the prod profile (SecretsCheck) and
what Compose requires, so a FAIL here is a failed deploy avoided.
"""
import argparse
import datetime
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# SecretsCheck.KNOWN_PLACEHOLDERS, and the obviously-not-chosen.
PLACEHOLDERS = {"cpintel_super_secret_jwt_key_change_this_in_production_min64chars_ok",
                "cpintel_dev_session_key_change_me_immediately", "CPIntelApp#2025", "CPIntelRedis#2025",
                "CPIntelMongo#2025", "changeme", "change_me", "secret", "password", "example"}

# Setting, minimum length (SecretsCheck / Compose).
SECRETS = [("POSTGRES_PASSWORD", 1), ("MONGO_PASSWORD", 1), ("MONGO_APP_PASSWORD", 1), ("REDIS_PASSWORD", 1),
           ("JWT_SECRET", 32), ("CPINTEL_SESSION_KEY", 16), ("CPINTEL_EXAM_PASSWORD_KEY", 32),
           ("CPINTEL_DOMJUDGE_CREDENTIAL_KEY", 32), ("CPINTEL_RUNNER_TOKEN", 16)]
REQUIRED = ["POSTGRES_USER", "MONGO_USER", "CPINTEL_DOMAIN", "CPINTEL_PUBLIC_URL"]


class Report:
    def __init__(self):
        self.failed = 0
        self.warned = 0

    def line(self, level, text):
        if level == "FAIL":
            self.failed += 1
        if level == "WARN":
            self.warned += 1
        print(f"  {level:<4}  {text}")


def parse_env(path, report):
    """KEY=VALUE lines as Compose reads them; a line it could not read is a FAIL (by number only)."""
    values = {}
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export "):]
        m = re.fullmatch(r"([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)", line)
        if not m:
            report.line("FAIL", f"line {number} of the settings file is not KEY=VALUE")
            continue
        key, value = m.groups()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
            value = value[1:-1]
        elif " #" in value:
            value = value.split(" #", 1)[0].rstrip()
        if key in values:
            report.line("WARN", f"{key} is set twice; the last one counts")
        values[key] = value
    return values


def check_file(path, report):
    if not path.is_file():
        report.line("FAIL", f"no settings file at {path}")
        return False
    mode = stat.S_IMODE(path.stat().st_mode)
    if mode & 0o077:
        report.line("FAIL", f"{path.name} can be read by others (mode {oct(mode)}): chmod 600 {path}")
    else:
        report.line("PASS", f"{path.name} is private (mode {oct(mode)})")
    return True


def check_secrets(env, report):
    seen = {}
    for key, minimum in SECRETS:
        value = env.get(key, "")
        if not value:
            report.line("FAIL", f"{key} is missing or empty")
        elif value in PLACEHOLDERS or value.lower() in PLACEHOLDERS:
            report.line("FAIL", f"{key} is a published placeholder; generate one (openssl rand -base64 48)")
        elif len(value) < minimum:
            report.line("FAIL", f"{key} is {len(value)} characters; at least {minimum} are needed")
        else:
            report.line("PASS", f"{key} is set ({len(value)} characters)")
        if value:
            seen.setdefault(value, []).append(key)
    for keys in seen.values():
        if len(keys) > 1:
            report.line("FAIL", f"{' and '.join(keys)} have the same value; each needs its own")
    for key in REQUIRED:
        if not env.get(key):
            report.line("FAIL", f"{key} is missing or empty")
    if env.get("MONGO_APP_USER") and env.get("MONGO_APP_USER") == env.get("MONGO_USER"):
        report.line("FAIL", "MONGO_APP_USER is the root account (MONGO_USER); the backend needs its own")


def check_settings(env, report):
    domain, url = env.get("CPINTEL_DOMAIN", ""), env.get("CPINTEL_PUBLIC_URL", "")
    if domain in ("localhost", "127.0.0.1") or "localhost" in url:
        report.line("WARN", "CPINTEL_DOMAIN / CPINTEL_PUBLIC_URL are development values (localhost)")
    elif url and not url.startswith("https://"):
        report.line("FAIL", "CPINTEL_PUBLIC_URL is not https://")
    elif url and domain and domain not in url:
        report.line("WARN", "CPINTEL_PUBLIC_URL does not contain CPINTEL_DOMAIN")
    elif url:
        report.line("PASS", "CPINTEL_DOMAIN and CPINTEL_PUBLIC_URL look like a real address (not checked on the network)")

    if not env.get("CPINTEL_ADMIN_EMAIL"):
        report.line("WARN", "CPINTEL_ADMIN_EMAIL is unset: no first super admin will be created or promoted")
    if env.get("CPINTEL_ADMIN_PASSWORD"):
        report.line("WARN", "CPINTEL_ADMIN_PASSWORD is set: remove it once the first super admin exists")

    undelivered = env.get("CPINTEL_MAIL_LOG_UNDELIVERED", "false").strip().lower() == "true"
    if not env.get("SMTP_HOST"):
        report.line("WARN", "SMTP_HOST is unset: password resets reach nobody"
                    + (" - and CPINTEL_MAIL_LOG_UNDELIVERED=true writes reset links to the backend log"
                       if undelivered else " (CPINTEL_MAIL_LOG_UNDELIVERED is off, so no link is logged)"))
    elif undelivered:
        report.line("WARN", "CPINTEL_MAIL_LOG_UNDELIVERED=true has no effect while SMTP_HOST is set")
    else:
        report.line("PASS", "SMTP_HOST is set (delivery not tested)")

    if not env.get("CPINTEL_BACKUP_REMOTE"):
        report.line("WARN", "CPINTEL_BACKUP_REMOTE is unset: nightly backups would stay on the server only")

    certs = [p for p in (ROOT / "nginx" / "certs").glob("*.pem")]
    if env.get("CPINTEL_ACME_EMAIL"):
        report.line("PASS", "CPINTEL_ACME_EMAIL is set: Let's Encrypt (needs the letsencrypt profile and a public name)")
    elif certs:
        report.line("PASS", "a certificate is in nginx/certs/ (not validated)")
    else:
        report.line("WARN", "no CPINTEL_ACME_EMAIL and nothing in nginx/certs/: nginx will serve a temporary self-signed certificate")


def check_version(version, report):
    if version is None:
        report.line("WARN", "no --version given: the image tag was not checked")
    elif re.fullmatch(r"[0-9a-f]{7,40}", version):
        report.line("PASS", f"--version {version} is a commit id (whether CI published its images is not checked)")
    else:
        report.line("FAIL", f"--version {version!r} is not a commit id; deploy.sh takes the SHA CI built")


def check_compose(env_file, version, report):
    if not shutil.which("docker") or subprocess.run(["docker", "compose", "version"], capture_output=True).returncode:
        report.line("WARN", "no docker compose here: the Compose configuration was not checked")
        return
    with tempfile.TemporaryDirectory() as tmp:
        cmd = ["docker", "compose", "-p", "cpintel-preflight", "--env-file", str(env_file),
               "-f", str(ROOT / "docker-compose.yml"), "-f", str(ROOT / "docker-compose.prod.yml"), "config", "--quiet"]
        env = {k: v for k, v in os.environ.items() if not k.startswith(("CPINTEL_", "MONGO_", "POSTGRES_", "REDIS_"))}
        env["CPINTEL_VERSION"] = version or "preflight"
        env["TMPDIR"] = tmp
        r = subprocess.run(cmd, capture_output=True, text=True, env=env, cwd=ROOT)
    if r.returncode == 0:
        report.line("PASS", "docker compose accepts the production configuration")
    else:
        # Compose names the missing variable; nothing else of its message is shown.
        missing = re.findall(r"required variable (\w+) is missing", r.stderr)
        report.line("FAIL", "docker compose refuses the configuration"
                    + (f": {', '.join(sorted(set(missing)))} missing" if missing else ""))


def check_exceptions(report, today):
    path = ROOT / "backend" / ".trivyignore.yaml"
    dates = sorted(set(re.findall(r"expired_at:\s*(\d{4}-\d{2}-\d{2})", path.read_text())) if path.exists() else [])
    for d in dates:
        left = (datetime.date.fromisoformat(d) - today).days
        if left < 0:
            report.line("FAIL", f"vulnerability-scan exceptions expired on {d}: the image build gate fails until they are resolved")
        elif left <= 60:
            report.line("WARN", f"vulnerability-scan exceptions expire on {d} ({left} days): Spring Boot 4, or re-check them (README)")
        else:
            report.line("PASS", f"vulnerability-scan exceptions run to {d} ({left} days)")


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    p.add_argument("--env-file", default=str(ROOT / ".env"))
    p.add_argument("--version", help="the commit SHA you mean to deploy")
    p.add_argument("--today", help=argparse.SUPPRESS)   # tests
    args = p.parse_args(argv)
    report = Report()
    env_file = Path(args.env_file)

    print("Settings file")
    if check_file(env_file, report):
        env = parse_env(env_file, report)
        print("Secrets and required settings")
        check_secrets(env, report)
        print("Deployment settings")
        check_settings(env, report)
        print("Compose")
        check_compose(env_file, args.version, report)
    print("Images")
    check_version(args.version, report)
    print("Security exceptions")
    check_exceptions(report, datetime.date.fromisoformat(args.today) if args.today else datetime.date.today())
    print("Not checked here")
    print("  INFO  MongoDB's indexes and duplicates: on a server with an existing database, run")
    print("        scripts/mongo-index-upgrade.sh --container cpintel-mongo --check first (README)")
    print("  INFO  DNS, certificates on the wire, ports and reachability: only the server can tell")

    print(f"=== {report.failed} failed, {report.warned} warning(s)")
    return 1 if report.failed else 0


if __name__ == "__main__":
    sys.exit(main())
