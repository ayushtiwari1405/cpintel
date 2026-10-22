#!/usr/bin/env python3
"""
Tests for scripts/mongo-index-upgrade.sh: duplicate checks, data-preserving renames, exports,
exact-definition index changes, refusals and reruns, against throwaway MongoDB.

    python3 -I scripts/test_mongo_index_upgrade.py

One throwaway mongo:7.0 container (--rm, no named volume, nothing published) per test class,
its cpintel database dropped before each test; removed afterwards. Skipped without Docker, or
with CPINTEL_NO_DOCKER=1. Standard library only.
"""
import json
import os
import subprocess
import sys
import tempfile
import unittest
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "mongo-index-upgrade.sh"
sys.path.insert(0, str(ROOT / "scripts"))
import test_mongo_app_user as base  # noqa: E402  (its Mongo helper; its tests are not collected here)

LEGACY_8B5B506 = """
db.cf_submissions.createIndex({ userId: 1, submittedAt: -1 });
db.cf_submissions.createIndex({ userId: 1, cfSubmissionId: 1 }, { unique: true });
db.cf_submissions.createIndex({ userId: 1, verdict: 1 });
db.cf_submissions.createIndex({ userId: 1, tags: 1 });
db.code_submissions.createIndex({ userId: 1, platform: 1, contestId: 1, problemIndex: 1, submittedAt: -1 });
db.code_submissions.createIndex({ userId: 1, platform: 1, externalId: 1 });
db.code_submissions.createIndex({ userId: 1, submittedAt: -1 });
"""

DESIRED = {
    "cf_submissions": {"idx_cf_user_submission": ([["userId", 1], ["cfSubmissionId", 1]], True),
                       "idx_cf_user_verdict": ([["userId", 1], ["verdict", 1]], False)},
    "code_submissions": {"idx_code_user_problem": ([["userId", 1], ["platform", 1], ["contestId", 1],
                                                    ["problemIndex", 1], ["submittedAt", -1]], False),
                         "idx_code_user_external": ([["userId", 1], ["platform", 1], ["externalId", 1]], False)},
    "personal_files": {"idx_files_user_name": ([["userId", 1], ["name", 1]], True)},
    "contest_file_rules": {"idx_file_rule_contest": ([["platform", 1], ["contestId", 1]], True)},
}

LONG = "x" * 116 + ".cpp"   # 120 characters, PersonalFileService's limit


class MongoCase(unittest.TestCase):
    """A throwaway MongoDB for the class, its cpintel database dropped before each test."""

    @classmethod
    def setUpClass(cls):
        cls.mongo = base.Mongo()

    @classmethod
    def tearDownClass(cls):
        cls.mongo.stop()

    def setUp(self):
        self.root("db.dropDatabase()")
        self.work = Path(tempfile.mkdtemp(prefix="cpintel-index-test-"))
        self.addCleanup(lambda: subprocess.run(["rm", "-rf", str(self.work)]))

    # -- helpers ------------------------------------------------------------------------------

    def root(self, js):
        return self.mongo.root(js)

    def run_script(self, *args, env=None):
        e = {k: v for k, v in os.environ.items() if not k.startswith(("MONGO_", "CPINTEL_"))}
        e.update(env or {})
        return subprocess.run(["bash", str(SCRIPT), "--container", self.mongo.name, *args],
                              capture_output=True, text=True, env=e)

    def backup(self):
        path = self.work / "mongo-20261022T000000Z-test.archive.gz"
        with open(path, "wb") as f:
            subprocess.run(["docker", "exec", self.mongo.name, "sh", "-c",
                            'mongodump --quiet --archive --gzip --db cpintel -u "$MONGO_INITDB_ROOT_USERNAME" '
                            '-p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin'],
                           stdout=f, check=True)
        return str(path)

    def snapshot(self):
        """Every document (EJSON) and every index of the database, to prove nothing changed."""
        return self.root("""
const out = {};
for (const c of db.getCollectionNames().sort()) {
  out[c] = { docs: db[c].find().sort({ _id: 1 }).toArray(), indexes: db[c].getIndexes().map(i => [i.name, i.key, !!i.unique]) };
}
print(EJSON.stringify(out));""")

    def indexes(self, coll):
        return json.loads(self.root(
            f"print(JSON.stringify(db.{coll}.getIndexes().map(i => [i.name, Object.entries(i.key), !!i.unique])))"))

    def assert_desired(self):
        for coll, wanted in DESIRED.items():
            found = {name: (key, unique) for name, key, unique in self.indexes(coll)}
            for name, (key, unique) in wanted.items():
                self.assertIn(name, found, f"{coll}.{name} missing")
                self.assertEqual(found[name], (key, unique), f"{coll}.{name} has the wrong definition")

    def seed_legacy(self, with_duplicates=True):
        self.root(LEGACY_8B5B506)
        self.root("""
const t = i => new Date(Date.UTC(2026, 8, 1, 0, i));
db.cf_submissions.insertMany([
  { userId: NumberLong("7"), cfSubmissionId: NumberLong("1"), verdict: 'OK' },
  { userId: NumberLong("8"), cfSubmissionId: NumberLong("1"), verdict: 'OK' }]);
db.code_submissions.insertOne({ userId: NumberLong("7"), platform: 'CODEFORCES', contestId: '1900', problemIndex: 'A',
  externalId: 'x1', source: 'int main(){}', submittedAt: t(0) });""")
        if not with_duplicates:
            return
        self.root(f"""
const t = i => new Date(Date.UTC(2026, 8, 1, 0, i));
const file = (u, name, i, text) => ({{ userId: NumberLong(String(u)), name: name, label: 'L' + i, contentType: 'text/plain',
  content: BinData(0, btoa(text)), sizeBytes: text.length, sourceHash: 'h' + i, textual: true, uploadedAt: t(i), updatedAt: t(i + 1) }});
db.personal_files.insertMany([
  file(1, 'a.cpp', 1, 'first a'), file(1, 'a.cpp', 2, 'second a'), file(1, 'a.cpp', 3, 'third a'),
  file(1, 'a (2).cpp', 4, 'already a (2)'),
  file(1, 'notes (2).txt', 5, 'n1'), file(1, 'notes (2).txt', 6, 'n2'),
  file(1, 'Makefile', 7, 'm1'), file(1, 'Makefile', 8, 'm2'),
  file(1, 'archive.tar.gz', 9, 'g1'), file(1, 'archive.tar.gz', 10, 'g2'),
  file(1, '{LONG}', 11, 'l1'), file(1, '{LONG}', 12, 'l2'),
  file(2, 'a.cpp', 13, 'another user')]);
db.contest_file_rules.insertMany([
  {{ platform: 'DOMJUDGE', contestId: 'mid', enabled: false, updatedBy: NumberLong("1"), updatedAt: t(1) }},
  {{ platform: 'DOMJUDGE', contestId: 'mid', enabled: true, updatedBy: NumberLong("2"), updatedAt: t(2) }},
  {{ platform: 'GLOBAL', contestId: null, enabled: true, updatedBy: NumberLong("1"), updatedAt: t(3) }},
  {{ platform: 'GLOBAL', enabled: false, updatedBy: NumberLong("2"), updatedAt: t(4) }}]);""")


@unittest.skipUnless(base._docker_ok(), "needs docker (or CPINTEL_NO_DOCKER=1 was set)")
class IndexUpgradeTest(MongoCase):

    # -- the read-only check --------------------------------------------------------------------

    def test_check_finds_every_conflict_and_changes_nothing(self):
        self.seed_legacy()
        before = self.snapshot()
        r = self.run_script("--check")
        self.assertEqual(r.returncode, 3, r.stdout + r.stderr)
        self.assertIn("personal_files (userId, name; idx_files_user_name): 5 duplicated key(s), 11 documents", r.stdout)
        # A missing contestId and a null one are the same key to a unique index.
        self.assertIn("contest_file_rules (platform, contestId; idx_file_rule_contest): 2 duplicated key(s), 4 documents", r.stdout)
        self.assertIn("cf_submissions (userId, cfSubmissionId; idx_cf_user_submission): no duplicates", r.stdout)
        self.assertIn("held as legacy userId_1_cfSubmissionId_1", r.stdout)
        self.assertIn("Duplicates remain", r.stdout)
        self.assertNotIn("a.cpp", r.stdout, "the check shows file names")
        self.assertEqual(self.snapshot(), before, "--check changed something")

    def test_export_records_conflicts_without_file_content(self):
        self.seed_legacy()
        before = self.snapshot()
        r = self.run_script("--export", str(self.work))
        self.assertEqual(r.returncode, 3, r.stdout)
        run = next(self.work.glob("mongo-index-upgrade-*"))
        self.assertEqual(oct(run.stat().st_mode & 0o777), "0o700")
        files = [json.loads(l) for l in (run / "personal_files.conflicts.jsonl").read_text().splitlines()]
        rules = [json.loads(l) for l in (run / "contest_file_rules.conflicts.jsonl").read_text().splitlines()]
        self.assertEqual(len(files), 11)
        self.assertTrue(all("content" not in f and "sourceHash" in f for f in files))
        self.assertEqual(len(rules), 4)
        self.assertEqual(self.snapshot(), before)

    def test_duplicate_codeforces_submissions_are_found_when_no_unique_index_held_them(self):
        # Under every earlier mongo-init.js a unique index forbade these; a database that never got
        # those indexes could still have them.
        self.root("""db.cf_submissions.insertMany([
  { userId: NumberLong("7"), cfSubmissionId: NumberLong("5") }, { userId: NumberLong("7"), cfSubmissionId: NumberLong("5") }])""")
        r = self.run_script("--check")
        self.assertEqual(r.returncode, 3)
        self.assertIn("cf_submissions (userId, cfSubmissionId; idx_cf_user_submission): 1 duplicated key(s), 2 documents", r.stdout)
        r = self.run_script("--apply", "--backup", self.backup())
        self.assertEqual(r.returncode, 3, r.stdout)
        self.assertEqual(self.root("print(db.cf_submissions.countDocuments())"), "2")

    # -- refusals -------------------------------------------------------------------------------

    def test_apply_refuses_while_duplicates_remain(self):
        self.seed_legacy()
        before = self.snapshot()
        r = self.run_script("--apply", "--backup", self.backup())
        self.assertEqual(r.returncode, 3, r.stdout + r.stderr)
        self.assertEqual(self.snapshot(), before)

    def test_changes_need_a_readable_recent_backup(self):
        self.seed_legacy(with_duplicates=False)
        self.assertEqual(self.run_script("--apply").returncode, 4)
        fake = self.work / "mongo-fake.archive.gz"
        fake.write_bytes(b"not a dump")
        r = self.run_script("--apply", "--backup", str(fake))
        self.assertEqual(r.returncode, 4)
        self.assertIn("not a readable gzip", r.stdout)
        old = self.backup()
        os.utime(old, (0, 0))
        r = self.run_script("--apply", "--backup", old)
        self.assertEqual(r.returncode, 4)
        self.assertIn("h old", r.stdout)
        self.assertEqual(self.run_script("--rename-duplicate-files", str(self.work), "--confirm").returncode, 4)
        self.assertEqual(self.run_script("--check").returncode, 0, "the read-only check needs no backup")

    def test_an_index_of_a_wanted_name_with_another_definition_stops_everything(self):
        self.seed_legacy(with_duplicates=False)
        self.root("db.personal_files.createIndex({ userId: 1 }, { name: 'idx_files_user_name' })")
        before = self.snapshot()
        r = self.run_script("--apply", "--backup", self.backup())
        self.assertEqual(r.returncode, 1, r.stdout)
        self.assertIn("an index of that name exists with another definition", r.stdout)
        self.assertEqual(self.snapshot(), before)

    def test_an_unknown_index_on_the_same_key_is_not_dropped(self):
        self.root(LEGACY_8B5B506.replace("db.cf_submissions.createIndex({ userId: 1, verdict: 1 });",
                                         "db.cf_submissions.createIndex({ userId: 1, verdict: 1 }, { name: 'ops_custom' });"))
        before = self.snapshot()
        r = self.run_script("--apply", "--backup", self.backup())
        self.assertEqual(r.returncode, 1, r.stdout)
        self.assertIn("held by ops_custom, which is not a known earlier definition", r.stdout)
        self.assertEqual(self.snapshot(), before)

    def test_a_legacy_name_with_another_definition_is_not_dropped(self):
        self.root(LEGACY_8B5B506.replace("db.cf_submissions.createIndex({ userId: 1, verdict: 1 });",
                                         "db.cf_submissions.createIndex({ userId: 1, verdict: 1 }, { sparse: true });"))
        before = self.snapshot()
        r = self.run_script("--apply", "--backup", self.backup())
        self.assertEqual(r.returncode, 1, r.stdout)
        self.assertIn("userId_1_verdict_1, which is not a known earlier definition", r.stdout)
        self.assertEqual(self.snapshot(), before)

    # -- resolving and applying -----------------------------------------------------------------

    def test_renames_keep_every_file_and_its_data(self):
        self.seed_legacy()
        files_before = json.loads(self.root(
            "print(EJSON.stringify(db.personal_files.find().sort({ _id: 1 }).toArray(), { relaxed: false }))"))

        plan_only = self.run_script("--rename-duplicate-files", str(self.work))
        self.assertEqual(plan_only.returncode, 0, plan_only.stdout)
        self.assertIn("Nothing was changed", plan_only.stdout)
        self.assertIn("Renames planned: 6", plan_only.stdout)
        self.assertEqual(json.loads(self.root(
            "print(EJSON.stringify(db.personal_files.find().sort({ _id: 1 }).toArray(), { relaxed: false }))")), files_before)

        r = self.run_script("--rename-duplicate-files", str(self.work), "--confirm", "--backup", self.backup())
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("renamed 6 of 6 file(s)", r.stdout)
        plans = sorted(self.work.glob("mongo-index-upgrade-*/personal_files.renames.jsonl"))
        self.assertEqual(len(plans), 2)
        record = [json.loads(l) for l in plans[-1].read_text().splitlines()]
        self.assertEqual(len(record), 6)

        files_after = json.loads(self.root(
            "print(EJSON.stringify(db.personal_files.find().sort({ _id: 1 }).toArray(), { relaxed: false }))"))
        self.assertEqual(len(files_after), len(files_before), "a file was lost")
        for old, new in zip(files_before, files_after):
            self.assertEqual(old["_id"], new["_id"])
            for field in ("userId", "content", "sizeBytes", "sourceHash", "label", "contentType", "uploadedAt", "updatedAt", "textual"):
                self.assertEqual(old[field], new[field], f"{field} changed")
        names = [(f["userId"]["$numberLong"], f["name"]) for f in files_after]
        self.assertEqual(len(names), len(set(names)), "a user still has two files of one name")
        by_label = {f["label"]: f["name"] for f in files_after}
        self.assertEqual([by_label[f"L{i}"] for i in (1, 2, 3, 4)], ["a.cpp", "a (3).cpp", "a (4).cpp", "a (2).cpp"])
        self.assertEqual([by_label["L5"], by_label["L6"]], ["notes (2).txt", "notes (3).txt"])
        self.assertEqual([by_label["L7"], by_label["L8"]], ["Makefile", "Makefile (2)"])
        self.assertEqual(by_label["L10"], "archive.tar (2).gz")
        self.assertEqual(by_label["L11"], LONG)
        self.assertTrue(by_label["L12"].endswith(" (2).cpp") and len(by_label["L12"]) <= 120, by_label["L12"])
        self.assertEqual(by_label["L13"], "a.cpp", "another user's file was renamed")

        again = self.run_script("--rename-duplicate-files", str(self.work), "--confirm", "--backup", self.backup())
        self.assertEqual(again.returncode, 0)
        self.assertIn("No duplicate file names to rename", again.stdout)

    def test_full_upgrade_then_a_second_run_changes_nothing(self):
        self.seed_legacy()
        backup = self.backup()
        self.assertEqual(self.run_script("--rename-duplicate-files", str(self.work), "--confirm", "--backup", backup).returncode, 0)
        r = self.run_script("--export", str(self.work))
        self.assertEqual(r.returncode, 3, "contest rules are still duplicated")
        rules = [json.loads(l) for l in next(self.work.glob("mongo-index-upgrade-*/contest_file_rules.conflicts.jsonl")).read_text().splitlines()]
        self.assertEqual(len(rules), 4, "every conflicting rule is on record before any is removed")
        # The operator's decision, by _id from the export: keep the later rule of each pair.
        for oid in (rules[0]["_id"]["$oid"], rules[2]["_id"]["$oid"]):
            self.root(f"db.contest_file_rules.deleteOne({{ _id: ObjectId('{oid}') }})")
        files_before = self.root("print(db.personal_files.countDocuments() + ',' + db.cf_submissions.countDocuments() + ',' + db.code_submissions.countDocuments())")

        r = self.run_script("--apply", "--backup", backup)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("every managed index is in place", r.stdout)
        self.assert_desired()
        for coll, legacy in (("cf_submissions", "userId_1_cfSubmissionId_1"), ("cf_submissions", "userId_1_verdict_1"),
                             ("code_submissions", "userId_1_platform_1_externalId_1"),
                             ("cf_submissions", "p7_guard_cf_user_submission")):
            self.assertNotIn(legacy, [i[0] for i in self.indexes(coll)])
        self.assertIn("userId_1_submittedAt_-1", [i[0] for i in self.indexes("code_submissions")],
                      "an index nothing replaces was dropped")
        self.assertEqual(self.root("print(db.personal_files.countDocuments() + ',' + db.cf_submissions.countDocuments() + ',' + db.code_submissions.countDocuments())"),
                         files_before, "documents were lost")

        snap = self.snapshot()
        again = self.run_script("--apply", "--backup", backup)
        self.assertEqual(again.returncode, 0, again.stdout)
        self.assertNotIn("created", again.stdout)
        self.assertNotIn("dropped", again.stdout)
        self.assertEqual(self.snapshot(), snap, "a second run changed something")

    def test_a_database_without_any_legacy_index_is_simply_given_the_indexes(self):
        r = self.run_script("--apply", "--backup", self.backup())
        self.assertEqual(r.returncode, 0, r.stdout)
        self.assert_desired()

    def test_an_interrupted_swap_resumes(self):
        # As if a run stopped after dropping the legacy unique index: only the guard holds the key.
        self.root(LEGACY_8B5B506)
        self.root("""db.cf_submissions.createIndex({ cfSubmissionId: 1, userId: 1 }, { unique: true, name: 'p7_guard_cf_user_submission' });
db.cf_submissions.dropIndex('userId_1_cfSubmissionId_1');""")
        check = self.run_script("--check")
        self.assertIn("left by an interrupted run", check.stdout)
        r = self.run_script("--apply", "--backup", self.backup())
        self.assertEqual(r.returncode, 0, r.stdout)
        self.assert_desired()
        self.assertNotIn("p7_guard_cf_user_submission", [i[0] for i in self.indexes("cf_submissions")])

    def test_the_first_releases_global_key_is_retired_after_the_per_user_one_exists(self):
        self.root("""db.cf_submissions.createIndex({ cfSubmissionId: 1 }, { unique: true });
db.cf_submissions.insertMany([{ userId: NumberLong("7"), cfSubmissionId: NumberLong("1") }, { userId: NumberLong("8"), cfSubmissionId: NumberLong("2") }]);""")
        r = self.run_script("--check")
        self.assertIn("an earlier release's global key", r.stdout)
        r = self.run_script("--apply", "--backup", self.backup())
        self.assertEqual(r.returncode, 0, r.stdout)
        names = [i[0] for i in self.indexes("cf_submissions")]
        self.assertIn("idx_cf_user_submission", names)
        self.assertNotIn("cfSubmissionId_1", names)

    def test_needs_an_explicit_container(self):
        r = subprocess.run(["bash", str(SCRIPT), "--check"], capture_output=True, text=True)
        self.assertEqual(r.returncode, 2)
        self.assertIn("--container is required", r.stdout)


@unittest.skipUnless(base._docker_ok(), "needs docker (or CPINTEL_NO_DOCKER=1 was set)")
class WriterTest(MongoCase):
    """In a container of its own: the connection it holds open cannot be closed from outside
    mongosh, and would count as a writer in every test after it."""

    def test_a_connected_backend_account_blocks_changes(self):
        self.seed_legacy(with_duplicates=False)
        password = base._secret()
        self.root(f"db.createUser({{ user: 'cpintel_app', pwd: '{password}', roles: [{{ role: 'readWrite', db: 'cpintel' }}] }})")
        holder = f"cpintel-test-holder-{uuid.uuid4().hex[:6]}"
        subprocess.run(["docker", "exec", "-d", self.mongo.name, "mongosh", "--quiet", "-u", "cpintel_app", "-p", password,
                        "--authenticationDatabase", "cpintel", "cpintel", "--eval", f"/* {holder} */ sleep(30000)"], check=True)
        backup = self.backup()
        for _ in range(20):
            check = self.run_script("--check")
            if "Connected writers: none" not in check.stdout:
                break
        self.assertIn("as cpintel_app", check.stdout)
        before = self.snapshot()
        r = self.run_script("--apply", "--backup", backup)
        self.assertEqual(r.returncode, 4, r.stdout)
        self.assertIn("Stop the backend first", r.stdout)
        self.assertEqual(self.snapshot(), before)


if __name__ == "__main__":
    unittest.main(verbosity=2)
