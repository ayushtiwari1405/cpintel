#!/usr/bin/env python3
"""
Tests for scripts/deploy-preflight.py, with made-up settings files only.

    python3 -I scripts/test_deploy_preflight.py

The Compose case needs the docker CLI (no daemon: `docker compose config` reads files only) and
is skipped without it. Standard library only.
"""
import os
import secrets
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "deploy-preflight.py"


def good():
    s = lambda: "fake-" + secrets.token_urlsafe(36)
    return {"POSTGRES_USER": "cpintel", "POSTGRES_PASSWORD": s(), "MONGO_USER": "cpintel_root", "MONGO_PASSWORD": s(),
            "MONGO_APP_USER": "cpintel_app", "MONGO_APP_PASSWORD": s(), "REDIS_PASSWORD": s(), "JWT_SECRET": s(),
            "CPINTEL_SESSION_KEY": s(), "CPINTEL_EXAM_PASSWORD_KEY": s(), "CPINTEL_DOMJUDGE_CREDENTIAL_KEY": s(),
            "CPINTEL_RUNNER_TOKEN": s(), "CPINTEL_DOMAIN": "cpintel.college.example",
            "CPINTEL_PUBLIC_URL": "https://cpintel.college.example", "CPINTEL_ADMIN_EMAIL": "admin@college.example",
            "SMTP_HOST": "smtp.college.example", "CPINTEL_BACKUP_REMOTE": "backup@nas.example:/srv/cpintel",
            "CPINTEL_ACME_EMAIL": "it@college.example", "GRAFANA_PASSWORD": s()}


class PreflightTest(unittest.TestCase):

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="cpintel-preflight-test-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def run_with(self, values, *args, mode=0o600, text=None):
        path = self.tmp / "settings.env"
        path.write_text(text if text is not None else "".join(f"{k}={v}\n" for k, v in values.items()))
        path.chmod(mode)
        env = {k: v for k, v in os.environ.items() if not k.startswith(("CPINTEL_", "MONGO_", "POSTGRES_", "REDIS_"))}
        r = subprocess.run(["python3", "-I", str(SCRIPT), "--env-file", str(path), "--today", "2026-10-22", *args],
                           capture_output=True, text=True, env=env)
        for key, value in values.items():
            if len(value) > 20:
                self.assertNotIn(value, r.stdout + r.stderr, f"{key}'s value was printed")
        return r

    def test_complete_settings_pass(self):
        r = self.run_with(good(), "--version", "05c60db")
        self.assertEqual(r.returncode, 0, r.stdout)
        self.assertIn("=== 0 failed", r.stdout)
        self.assertIn("PASS  JWT_SECRET is set", r.stdout)
        self.assertIn("vulnerability-scan exceptions run to 2026-12-31 (70 days)", r.stdout)
        self.assertIn("mongo-index-upgrade.sh --container cpintel-mongo --check", r.stdout)

    def test_missing_short_placeholder_and_shared_secrets_fail(self):
        values = good()
        del values["MONGO_APP_PASSWORD"]
        values["JWT_SECRET"] = "too-short"
        values["CPINTEL_SESSION_KEY"] = "cpintel_dev_session_key_change_me_immediately"
        values["REDIS_PASSWORD"] = values["POSTGRES_PASSWORD"]
        r = self.run_with(values)
        self.assertEqual(r.returncode, 1)
        self.assertIn("FAIL  MONGO_APP_PASSWORD is missing or empty", r.stdout)
        self.assertIn("FAIL  JWT_SECRET is 9 characters; at least 32 are needed", r.stdout)
        self.assertIn("FAIL  CPINTEL_SESSION_KEY is a published placeholder", r.stdout)
        self.assertIn("FAIL  POSTGRES_PASSWORD and REDIS_PASSWORD have the same value", r.stdout)

    def test_a_readable_file_fails(self):
        r = self.run_with(good(), mode=0o644)
        self.assertEqual(r.returncode, 1)
        self.assertIn("can be read by others (mode 0o644)", r.stdout)

    def test_a_malformed_line_fails_by_number_only(self):
        values = good()
        text = "".join(f"{k}={v}\n" for k, v in values.items()) + "this line has no equals sign\n"
        r = self.run_with(values, text=text)
        self.assertEqual(r.returncode, 1)
        self.assertIn(f"line {len(values) + 1} of the settings file is not KEY=VALUE", r.stdout)
        self.assertNotIn("no equals sign", r.stdout)

    def test_development_and_insecure_addresses(self):
        values = good()
        values["CPINTEL_DOMAIN"], values["CPINTEL_PUBLIC_URL"] = "localhost", "http://localhost"
        self.assertIn("WARN  CPINTEL_DOMAIN / CPINTEL_PUBLIC_URL are development values", self.run_with(values).stdout)
        values["CPINTEL_DOMAIN"], values["CPINTEL_PUBLIC_URL"] = "cpintel.college.example", "http://cpintel.college.example"
        r = self.run_with(values)
        self.assertEqual(r.returncode, 1)
        self.assertIn("FAIL  CPINTEL_PUBLIC_URL is not https://", r.stdout)

    def test_mail_and_reset_link_logging_are_reported(self):
        values = good()
        del values["SMTP_HOST"]
        self.assertIn("(CPINTEL_MAIL_LOG_UNDELIVERED is off, so no link is logged)", self.run_with(values).stdout)
        values["CPINTEL_MAIL_LOG_UNDELIVERED"] = "true"
        self.assertIn("writes reset links to the backend log", self.run_with(values).stdout)

    def test_the_version_and_the_exception_deadline(self):
        r = self.run_with(good(), "--version", "latest")
        self.assertIn("FAIL  --version 'latest' is not a commit id", r.stdout)
        path = self.tmp / "settings.env"
        late = subprocess.run(["python3", "-I", str(SCRIPT), "--env-file", str(path), "--today", "2026-11-15"],
                              capture_output=True, text=True)
        self.assertIn("WARN  vulnerability-scan exceptions expire on 2026-12-31 (46 days)", late.stdout)
        expired = subprocess.run(["python3", "-I", str(SCRIPT), "--env-file", str(path), "--today", "2027-01-02"],
                                 capture_output=True, text=True)
        self.assertEqual(expired.returncode, 1)
        self.assertIn("FAIL  vulnerability-scan exceptions expired on 2026-12-31", expired.stdout)

    def test_no_settings_file(self):
        r = subprocess.run(["python3", "-I", str(SCRIPT), "--env-file", str(self.tmp / "absent.env")],
                           capture_output=True, text=True)
        self.assertEqual(r.returncode, 1)
        self.assertIn("FAIL  no settings file at", r.stdout)

    @unittest.skipUnless(shutil.which("docker") and subprocess.run(["docker", "compose", "version"], capture_output=True).returncode == 0,
                         "needs the docker CLI with compose")
    def test_compose_names_what_it_refuses(self):
        values = good()
        del values["REDIS_PASSWORD"]
        r = self.run_with(values, "--version", "05c60db")
        self.assertIn("FAIL  docker compose refuses the configuration: REDIS_PASSWORD missing", r.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
