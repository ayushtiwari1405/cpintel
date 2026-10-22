#!/usr/bin/env python3
"""
Tests for the backend's least-privilege MongoDB account: scripts/mongo-app-user.sh, which gives an
existing database the account, and mongo-init.js, which creates it on a fresh one.

    python3 -I scripts/test_mongo_app_user.py

Every test starts throwaway mongo:7.0 containers (--rm, no named volume, nothing published) and
removes them afterwards. The script is copied to a temporary directory and run from there, so no
.env in this checkout is read. Skipped without Docker, or with CPINTEL_NO_DOCKER=1. Standard
library only.
"""
import os
import shutil
import subprocess
import tempfile
import time
import unittest
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "mongo-app-user.sh"
INIT = ROOT / "mongo-init.js"
IMAGE = "mongo:7.0"   # what docker-compose.yml runs


def _docker_ok():
    if os.environ.get("CPINTEL_NO_DOCKER") == "1" or not shutil.which("docker"):
        return False
    return subprocess.run(["docker", "info"], capture_output=True).returncode == 0


def _docker(*args, check=True, stdin=None):
    return subprocess.run(["docker", *args], capture_output=True, text=True, check=check, input=stdin)


def _secret():
    return uuid.uuid4().hex


class Mongo:
    """A throwaway mongo:7.0 with a root account, optionally initialised by mongo-init.js."""

    def __init__(self, init_script=False, app_password=None):
        self.name = f"cpintel-test-mongo-{uuid.uuid4().hex[:10]}"
        self.root_password = _secret()
        args = ["run", "-d", "--rm", "--name", self.name,
                "-e", "MONGO_INITDB_ROOT_USERNAME=root", "-e", f"MONGO_INITDB_ROOT_PASSWORD={self.root_password}",
                "-e", "MONGO_INITDB_DATABASE=cpintel"]
        if init_script:
            args += ["-e", "MONGO_APP_USER=cpintel_app", "-e", f"MONGO_APP_PASSWORD={app_password}",
                     "-v", f"{INIT}:/docker-entrypoint-initdb.d/init.js:ro"]
        _docker(*args, IMAGE)
        # With a root account to create, the entrypoint always runs mongod once to initialise
        # (creating root and running any init scripts), then again for real.
        for _ in range(90):
            logs = _docker("logs", self.name, check=False)
            if (logs.stdout + logs.stderr).count("Waiting for connections") >= 2:
                break
            time.sleep(1)
        else:
            raise RuntimeError("mongo did not start: " + logs.stderr[-500:])

    def root(self, js):
        out = _docker("exec", self.name, "mongosh", "--quiet", "-u", "root", "-p", self.root_password,
                      "--authenticationDatabase", "admin", "cpintel", "--eval", js)
        return out.stdout.strip()

    def as_user(self, user, password, js):
        return _docker("exec", self.name, "mongosh", "--quiet", "-u", user, "-p", password,
                       "--authenticationDatabase", "cpintel", "cpintel", "--eval", js, check=False)

    def stop(self):
        _docker("rm", "-f", "-v", self.name, check=False)


@unittest.skipUnless(_docker_ok(), "needs docker (or CPINTEL_NO_DOCKER=1 was set)")
class MongoAppUserTest(unittest.TestCase):

    def setUp(self):
        self.work = Path(tempfile.mkdtemp(prefix="cpintel-mongo-test-"))
        (self.work / "scripts").mkdir()
        shutil.copy(SCRIPT, self.work / "scripts" / SCRIPT.name)
        self.addCleanup(shutil.rmtree, self.work, True)

    def mongo(self, **kw):
        m = Mongo(**kw)
        self.addCleanup(m.stop)
        return m

    def run_script(self, container, password, *args, user="cpintel_app"):
        env = {k: v for k, v in os.environ.items() if not k.startswith(("MONGO_", "CPINTEL_MONGO"))}
        env["CPINTEL_MONGO_CONTAINER"] = container
        env["MONGO_APP_USER"] = user
        if password is not None:
            env["MONGO_APP_PASSWORD"] = password
        return subprocess.run(["bash", str(self.work / "scripts" / SCRIPT.name), *args],
                              capture_output=True, text=True, env=env)

    def assertQuiet(self, result, *secrets):
        for s in secrets:
            self.assertNotIn(s, result.stdout + result.stderr, "a password reached the output")

    # -- an existing database, created before the account ---------------------------------

    def test_upgrade_creates_the_account_and_keeps_the_data(self):
        m = self.mongo()
        m.root("db.code_submissions.insertMany([{ userId: 1, source: 'a' }, { userId: 2, source: 'b' }])")
        password = _secret()

        check = self.run_script(m.name, password, "--check")
        self.assertEqual(check.returncode, 1)
        self.assertIn("does not exist", check.stdout)
        self.assertEqual(m.root("db.getUser('cpintel_app') === null"), "true", "--check created something")

        made = self.run_script(m.name, password)
        self.assertEqual(made.returncode, 0, made.stdout + made.stderr)
        self.assertIn("Created cpintel_app with readWrite on cpintel.", made.stdout)
        self.assertIn("roles readWrite@cpintel only, refused admin data", made.stdout)
        self.assertQuiet(made, password, m.root_password)

        roles = m.root("JSON.stringify(db.getUser('cpintel_app').roles)")
        self.assertEqual(roles, '[{"role":"readWrite","db":"cpintel"}]')
        read = m.as_user("cpintel_app", password, "db.code_submissions.countDocuments()")
        self.assertEqual(read.stdout.strip(), "2", read.stderr)
        denied = m.as_user("cpintel_app", password, "db.getSiblingDB('admin').system.users.findOne()")
        self.assertIn("not authorized", denied.stdout + denied.stderr)

        again = self.run_script(m.name, password)
        self.assertEqual(again.returncode, 0, again.stdout)
        self.assertIn("already exists with readWrite on cpintel", again.stdout)
        self.assertEqual(self.run_script(m.name, password, "--check").returncode, 0)

    def test_a_different_password_is_reported_and_only_reset_on_request(self):
        m = self.mongo()
        old, new = _secret(), _secret()
        self.assertEqual(self.run_script(m.name, old).returncode, 0)

        wrong = self.run_script(m.name, new)
        self.assertEqual(wrong.returncode, 1)
        self.assertIn("password differs", wrong.stdout)
        self.assertEqual(m.as_user("cpintel_app", old, "1").returncode, 0, "the old password still works")
        self.assertQuiet(wrong, old, new)

        reset = self.run_script(m.name, new, "--reset-password")
        self.assertEqual(reset.returncode, 0, reset.stdout)
        self.assertIn("Set the password of cpintel_app.", reset.stdout)
        self.assertEqual(m.as_user("cpintel_app", new, "1").returncode, 0)
        self.assertNotEqual(m.as_user("cpintel_app", old, "1").returncode, 0)

    def test_an_account_with_other_roles_is_left_alone(self):
        m = self.mongo()
        password = _secret()
        m.root(f"db.createUser({{ user: 'cpintel_app', pwd: '{password}', roles: ['dbOwner'] }})")

        result = self.run_script(m.name, password)
        self.assertEqual(result.returncode, 1)
        self.assertIn("not readWrite on cpintel", result.stdout)
        self.assertEqual(m.root("db.getUser('cpintel_app').roles[0].role"), "dbOwner", "roles were changed")

    def test_refuses_without_a_password_or_a_running_container(self):
        no_password = self.run_script("cpintel-test-mongo-absent", None)
        self.assertEqual(no_password.returncode, 1)
        self.assertIn("MONGO_APP_PASSWORD is not set", no_password.stdout)

        absent = self.run_script(f"cpintel-test-mongo-absent-{uuid.uuid4().hex[:6]}", _secret())
        self.assertEqual(absent.returncode, 1)
        self.assertIn("is not running", absent.stdout)

    # -- a fresh database --------------------------------------------------------------------

    def test_fresh_install_has_the_account_from_mongo_init(self):
        password = _secret()
        m = self.mongo(init_script=True, app_password=password)

        check = self.run_script(m.name, password, "--check")
        self.assertEqual(check.returncode, 0, check.stdout + check.stderr)
        self.assertIn("already exists with readWrite on cpintel", check.stdout)
        # The indexes the entities also declare carry the entities' names, so the backend's own
        # index creation at start does not collide with them.
        names = m.root("JSON.stringify(db.code_submissions.getIndexes().map(i => i.name).sort())")
        self.assertIn("idx_code_user_problem", names)
        self.assertIn("idx_code_user_external", names)
        self.assertIn("idx_cf_user_verdict", m.root("JSON.stringify(db.cf_submissions.getIndexes().map(i => i.name))"))

    def test_fresh_install_without_an_app_password_fails_loudly(self):
        m = Mongo.__new__(Mongo)
        m.name = f"cpintel-test-mongo-{uuid.uuid4().hex[:10]}"
        self.addCleanup(lambda: _docker("rm", "-f", "-v", m.name, check=False))
        _docker("run", "-d", "--name", m.name, "-e", "MONGO_INITDB_ROOT_USERNAME=root",
                "-e", f"MONGO_INITDB_ROOT_PASSWORD={_secret()}", "-e", "MONGO_INITDB_DATABASE=cpintel",
                "-v", f"{INIT}:/docker-entrypoint-initdb.d/init.js:ro", IMAGE)
        for _ in range(60):
            state = _docker("inspect", "-f", "{{.State.Running}}", m.name, check=False).stdout.strip()
            logs = _docker("logs", m.name, check=False)
            if state == "false" or "MONGO_APP_PASSWORD is not set" in logs.stdout + logs.stderr:
                break
            time.sleep(1)
        self.assertIn("MONGO_APP_PASSWORD is not set", logs.stdout + logs.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
