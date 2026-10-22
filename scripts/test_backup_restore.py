#!/usr/bin/env python3
"""
Tests for scripts/backup.sh and scripts/restore.sh against throwaway databases only.

    python3 -I scripts/test_backup_restore.py

Throwaway postgres:16-alpine and mongo:7.0 containers (--rm, no named volume, nothing
published) stand in for a server: a source pair with data, and target pairs to restore into. The
scripts run from copies in a temporary directory, so no .env of this checkout - which could name
a real backup directory or a remote - is read, and every container is named explicitly
(CPINTEL_*_CONTAINER). Everything is removed afterwards. Skipped without Docker, or with
CPINTEL_NO_DOCKER=1. Standard library only.
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
import sys  # noqa: E402
sys.path.insert(0, str(ROOT / "scripts"))
import test_mongo_app_user as base  # noqa: E402  (its Mongo helper)

FIXTURE = ROOT / "scripts" / "integration" / "fixtures" / "mongo-init-before-p6.js"


class Postgres:
    def __init__(self):
        self.name = f"cpintel-test-pg-{uuid.uuid4().hex[:10]}"
        self.password = base._secret()
        env = dict(os.environ, POSTGRES_PASSWORD=self.password)
        subprocess.run(["docker", "run", "-d", "--rm", "--name", self.name, "-e", "POSTGRES_USER=cpintel",
                        "-e", "POSTGRES_PASSWORD", "-e", "POSTGRES_DB=cpintel", "postgres:16-alpine"],
                       env=env, capture_output=True, check=True)
        for _ in range(60):
            if subprocess.run(["docker", "exec", self.name, "pg_isready", "-U", "cpintel", "-d", "cpintel"],
                              capture_output=True).returncode == 0:
                break
            time.sleep(1)
        time.sleep(1)

    def sql(self, statement, db="cpintel"):
        r = subprocess.run(["docker", "exec", "-i", self.name, "psql", "-U", "cpintel", "-d", db, "-At", "-v", "ON_ERROR_STOP=1"],
                           input=statement, capture_output=True, text=True)
        if r.returncode:
            raise AssertionError(r.stderr)
        return r.stdout.strip()

    def stop(self):
        subprocess.run(["docker", "rm", "-f", "-v", self.name], capture_output=True)


def seed(pg, mongo):
    pg.sql("""create table users (id serial primary key, email text);
insert into users (email) values ('a@x.test'), ('b@x.test'), ('c@x.test');
create table group_contests (id serial primary key);
insert into group_contests default values; insert into group_contests default values;
create table flyway_schema_history (installed_rank int, version text, success boolean);
insert into flyway_schema_history values (1, '26', true);""")
    mongo.root("""db.personal_files.createIndex({ userId: 1, name: 1 }, { unique: true, name: 'idx_files_user_name' });
db.personal_files.insertMany([
  { userId: NumberLong('1'), name: 'a.cpp', content: BinData(0, btoa('int main(){}')), uploadedAt: new Date(0) },
  { userId: NumberLong('2'), name: 'b.cpp', content: BinData(0, btoa('int main(){return 1;}')), uploadedAt: new Date(0) }]);
db.code_submissions.insertOne({ userId: NumberLong('1'), platform: 'CODEFORCES', contestId: '1900', problemIndex: 'A', externalId: 'x', source: 's', submittedAt: new Date(0) });""")


@unittest.skipUnless(base._docker_ok(), "needs docker (or CPINTEL_NO_DOCKER=1 was set)")
class BackupRestoreTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.app_password = base._secret()
        cls.src_pg, cls.src_mongo = Postgres(), base.Mongo(init_script=True, app_password=cls.app_password)
        seed(cls.src_pg, cls.src_mongo)
        cls.work = Path(tempfile.mkdtemp(prefix="cpintel-backup-test-"))
        (cls.work / "scripts").mkdir()
        for f in ("backup.sh", "restore.sh", "mongo-app-user.sh", "mongo-index-upgrade.sh", "mongo-index-upgrade.js"):
            shutil.copy(ROOT / "scripts" / f, cls.work / "scripts" / f)
        cls.dir = cls.work / "backups"
        cls.outputs = []
        cls.stamp = cls.backup(cls.src_pg, cls.src_mongo, "source").stdout.strip().splitlines()[-1]

    @classmethod
    def tearDownClass(cls):
        cls.src_pg.stop()
        cls.src_mongo.stop()
        shutil.rmtree(cls.work, True)

    @classmethod
    def run_script(cls, script, *args, pg=None, mongo=None, stdin=None, extra=None):
        env = {k: v for k, v in os.environ.items() if not k.startswith(("CPINTEL_", "MONGO_"))}
        env.update({"CPINTEL_BACKUP_DIR": str(cls.dir)})
        if pg:
            env["CPINTEL_PG_CONTAINER"] = pg.name
        if mongo:
            env["CPINTEL_MONGO_CONTAINER"] = mongo.name
        env.update(extra or {})
        r = subprocess.run(["bash", str(cls.work / "scripts" / script), *args], capture_output=True, text=True,
                           env=env, input=stdin)
        cls.outputs.append(r.stdout + r.stderr)
        return r

    @classmethod
    def backup(cls, pg, mongo, tag):
        r = cls.run_script("backup.sh", tag, pg=pg, mongo=mongo)
        if r.returncode:
            raise AssertionError("backup failed: " + r.stdout + r.stderr)
        return r

    def target(self, init_script=False, fixture=None):
        pg = Postgres()
        if fixture:
            mongo = base.Mongo.__new__(base.Mongo)
            mongo.name = f"cpintel-test-mongo-{uuid.uuid4().hex[:10]}"
            mongo.root_password = base._secret()
            env = dict(os.environ, MONGO_INITDB_ROOT_PASSWORD=mongo.root_password)
            subprocess.run(["docker", "run", "-d", "--rm", "--name", mongo.name, "-e", "MONGO_INITDB_ROOT_USERNAME=root",
                            "-e", "MONGO_INITDB_ROOT_PASSWORD", "-e", "MONGO_INITDB_DATABASE=cpintel",
                            "-v", f"{fixture}:/docker-entrypoint-initdb.d/init.js:ro", "mongo:7.0"],
                           env=env, capture_output=True, check=True)
            for _ in range(90):
                logs = subprocess.run(["docker", "logs", mongo.name], capture_output=True, text=True)
                if (logs.stdout + logs.stderr).count("Waiting for connections") >= 2:
                    break
                time.sleep(1)
        else:
            mongo = base.Mongo(init_script=init_script, app_password=base._secret() if init_script else None)
        self.addCleanup(pg.stop)
        self.addCleanup(mongo.stop)
        return pg, mongo

    def restore(self, pg, mongo, stamp=None, answer="restore\n", backend="cpintel-test-no-such-backend"):
        return self.run_script("restore.sh", stamp or self.stamp, pg=pg, mongo=mongo, stdin=answer,
                               extra={"CPINTEL_BACKEND_CONTAINER": backend})

    # -- backup -------------------------------------------------------------------------------

    def test_a_backup_is_a_readable_private_pair(self):
        pg_file = self.dir / f"postgres-{self.stamp}.dump"
        mongo_file = self.dir / f"mongo-{self.stamp}.archive.gz"
        self.assertTrue(self.stamp.endswith("-source"), self.stamp)
        for f in (pg_file, mongo_file):
            self.assertTrue(f.is_file() and f.stat().st_size > 0, f)
            self.assertEqual(oct(f.stat().st_mode & 0o777), "0o600")
        self.assertEqual(oct(self.dir.stat().st_mode & 0o777), "0o700")
        self.assertEqual(subprocess.run(["gzip", "-t", str(mongo_file)]).returncode, 0)
        self.assertEqual(list(self.dir.glob("*.partial")), [])

    def test_a_failed_dump_keeps_nothing(self):
        before = sorted(p.name for p in self.dir.iterdir())
        r = self.run_script("backup.sh", "broken", pg=self.src_pg, mongo=type("M", (), {"name": "cpintel-test-no-such-mongo"})())
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(sorted(p.name for p in self.dir.iterdir()), before,
                         "a failed run left a dump or a partial file")

    # -- restore --test -------------------------------------------------------------------------

    def test_the_restore_drill_counts_and_cleans_up(self):
        r = self.run_script("restore.sh", "--test", self.stamp, pg=self.src_pg, mongo=self.src_mongo)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        for line in ("postgres users: 3", "postgres examinations: 2", "postgres schema version: 26",
                     "mongo personal_files: 2", "mongo code_submissions: 1"):
            self.assertIn(line, r.stdout)
        self.assertEqual(self.src_pg.sql("select count(*) from pg_database where datname = 'cpintel_restore_test'"), "0")
        self.assertEqual(self.src_mongo.root("print(db.adminCommand({ listDatabases: 1 }).databases.some(d => d.name === 'cpintel_restore_test'))"), "false")

    def test_a_damaged_or_missing_archive_changes_nothing(self):
        stamp = "20261022T000000Z-damaged"
        shutil.copy(self.dir / f"postgres-{self.stamp}.dump", self.dir / f"postgres-{stamp}.dump")
        data = (self.dir / f"mongo-{self.stamp}.archive.gz").read_bytes()
        (self.dir / f"mongo-{stamp}.archive.gz").write_bytes(data[: len(data) // 2])
        r = self.run_script("restore.sh", "--test", stamp, pg=self.src_pg, mongo=self.src_mongo)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("is not a readable archive. Nothing was changed.", r.stdout)
        self.assertEqual(self.src_pg.sql("select count(*) from pg_database where datname = 'cpintel_restore_test'"), "0")

        (self.dir / f"mongo-{stamp}.archive.gz").unlink()
        r = self.run_script("restore.sh", "--test", stamp, pg=self.src_pg, mongo=self.src_mongo)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("Missing or empty", r.stdout)

    # -- restore into another pair --------------------------------------------------------------

    def test_restore_into_another_pair_then_give_it_the_account(self):
        pg, mongo = self.target()
        r = self.restore(pg, mongo)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn(f"Postgres: {pg.name}", r.stdout)
        self.assertIn(f"MongoDB:  {mongo.name}", r.stdout)
        self.assertIn("mongo-app-user.sh --check", r.stdout)
        self.assertEqual(pg.sql("select count(*) from users"), "3")
        self.assertEqual(mongo.root("print(db.personal_files.countDocuments() + ',' + db.code_submissions.countDocuments())"), "2,1")
        self.assertEqual(mongo.root("print(db.personal_files.findOne({ name: 'b.cpp' }).content.toString('base64'))"),
                         self.src_mongo.root("print(db.personal_files.findOne({ name: 'b.cpp' }).content.toString('base64'))"))
        self.assertEqual(mongo.root("print(db.personal_files.getIndexes().some(i => i.name === 'idx_files_user_name' && i.unique))"), "true")

        # Accounts are not in a backup: the new pair has none until mongo-app-user.sh makes it.
        env = {"CPINTEL_MONGO_CONTAINER": mongo.name, "MONGO_APP_USER": "cpintel_app", "MONGO_APP_PASSWORD": self.app_password}
        self.assertEqual(self.run_script("mongo-app-user.sh", "--check", extra=env).returncode, 1)
        made = self.run_script("mongo-app-user.sh", extra=env)
        self.assertEqual(made.returncode, 0, made.stdout)
        app = mongo.as_user("cpintel_app", self.app_password, "db.personal_files.countDocuments()")
        self.assertEqual(app.stdout.strip(), "2")

    def test_restore_is_refused_unconfirmed_or_while_the_backend_runs(self):
        pg, mongo = self.target()
        pg.sql("create table marker (x int); insert into marker values (1);")
        r = self.restore(pg, mongo, answer="no\n")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("Cancelled", r.stdout)
        self.assertEqual(pg.sql("select count(*) from marker"), "1")

        backend = f"cpintel-test-backend-{uuid.uuid4().hex[:6]}"
        subprocess.run(["docker", "run", "-d", "--rm", "--name", backend, "postgres:16-alpine", "sleep", "60"],
                       capture_output=True, check=True)
        self.addCleanup(subprocess.run, ["docker", "rm", "-f", "-v", backend], capture_output=True)
        r = self.restore(pg, mongo, backend=backend)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("is running. Stop it first", r.stdout)
        self.assertEqual(pg.sql("select count(*) from marker"), "1")

    def test_a_backup_from_before_the_index_upgrade_needs_it_again(self):
        # A server installed before P6: default-named indexes. Its backup brings them back on restore.
        old_pg, old_mongo = self.target(fixture=FIXTURE)
        seed(old_pg, old_mongo)
        stamp = self.backup(old_pg, old_mongo, "before-p6").stdout.strip().splitlines()[-1]

        pg, mongo = self.target(init_script=True)
        self.assertEqual(self.restore(pg, mongo, stamp=stamp).returncode, 0)
        names = mongo.root("print(db.cf_submissions.getIndexes().map(i => i.name).join(','))")
        self.assertIn("userId_1_cfSubmissionId_1", names, "the restore did not bring back the old indexes")

        upgrade = ["--container", mongo.name]
        check = self.run_script("mongo-index-upgrade.sh", *upgrade, "--check")
        self.assertEqual(check.returncode, 0, check.stdout)
        self.assertIn("held as legacy userId_1_cfSubmissionId_1", check.stdout)
        apply = self.run_script("mongo-index-upgrade.sh", *upgrade, "--apply", "--backup",
                                str(self.dir / f"mongo-{stamp}.archive.gz"))
        self.assertEqual(apply.returncode, 0, apply.stdout)
        self.assertIn("idx_cf_user_submission", mongo.root("print(db.cf_submissions.getIndexes().map(i => i.name).join(','))"))

    # -- across all of the above ------------------------------------------------------------------

    def test_zz_no_secret_was_printed(self):
        secrets = [self.src_pg.password, self.src_mongo.root_password, self.app_password]
        for out in self.outputs:
            for s in secrets:
                self.assertNotIn(s, out, "a password was printed")


if __name__ == "__main__":
    unittest.main(verbosity=2)
