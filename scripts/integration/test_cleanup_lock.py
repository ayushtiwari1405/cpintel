#!/usr/bin/env python3
"""
Tests that `integration-test.sh --cleanup --yes` leaves alone a project whose run still holds its
lock, and removes it once the run has ended.

    python3 -I scripts/integration/test_cleanup_lock.py

The "project" is a single Docker network carrying the Compose project label of a made-up run
(cpintel-it-locktest-<random>[-upgrade]); --project keeps cleanup to it, and the test removes it
whatever happens. The lock directory is a temporary one. Skipped without Docker or flock, or with
CPINTEL_NO_DOCKER=1. Standard library only.
"""
import os
import shutil
import signal
import subprocess
import tempfile
import unittest
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "integration-test.sh"


def _usable():
    if os.environ.get("CPINTEL_NO_DOCKER") == "1" or not shutil.which("docker") or not shutil.which("flock"):
        return False
    return subprocess.run(["docker", "info"], capture_output=True).returncode == 0


@unittest.skipUnless(_usable(), "needs docker and flock (or CPINTEL_NO_DOCKER=1 was set)")
class CleanupLockTest(unittest.TestCase):

    def setUp(self):
        self.locks = Path(tempfile.mkdtemp(prefix="cpintel-it-locks-"))
        self.addCleanup(shutil.rmtree, self.locks, True)
        self.run_id = f"locktest-{uuid.uuid4().hex[:8]}"

    def project(self, suffix=""):
        name = f"cpintel-it-{self.run_id}{suffix}"
        subprocess.run(["docker", "network", "create", "--label", f"com.docker.compose.project={name}", f"{name}_net"],
                       capture_output=True, check=True)
        self.addCleanup(subprocess.run, ["docker", "network", "rm", f"{name}_net"], capture_output=True)
        return name

    def exists(self, name):
        return subprocess.run(["docker", "network", "inspect", f"{name}_net"], capture_output=True).returncode == 0

    def cleanup(self, name):
        env = dict(os.environ, CPINTEL_IT_LOCK_DIR=str(self.locks))
        return subprocess.run(["bash", str(SCRIPT), "--cleanup", "--yes", "--project", name],
                              capture_output=True, text=True, env=env)

    def hold_lock(self):
        """A stand-in for a running test: holds its lock until killed."""
        lock = self.locks / f"cpintel-it-{self.run_id}.lock"
        # Its own process group: flock hands the lock to sleep, which must go too.
        holder = subprocess.Popen(["flock", str(lock), "sleep", "60"], start_new_session=True)
        self.addCleanup(self.end, holder)
        for _ in range(50):
            if lock.exists() and subprocess.run(["flock", "-n", str(lock), "true"]).returncode != 0:
                return holder, lock
            subprocess.run(["sleep", "0.1"])
        self.fail("the lock was not taken")

    @staticmethod
    def end(holder):
        try:
            os.killpg(holder.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        holder.wait()

    def test_a_running_project_is_skipped_and_an_ended_one_removed(self):
        name = self.project()
        holder, lock = self.hold_lock()

        r = self.cleanup(name)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn(f"Skipped {name}: its run is still going.", r.stdout)
        self.assertTrue(self.exists(name), "a running project was removed")

        self.end(holder)
        r = self.cleanup(name)
        self.assertIn(f"Removed {name}.", r.stdout)
        self.assertFalse(self.exists(name))
        self.assertFalse(lock.exists(), "the ended run's lock file was left")

    def test_the_upgrade_phase_shares_its_runs_lock(self):
        name = self.project("-upgrade")
        holder, _ = self.hold_lock()
        self.assertIn("still going", self.cleanup(name).stdout)
        self.assertTrue(self.exists(name))
        self.end(holder)
        self.assertIn(f"Removed {name}.", self.cleanup(name).stdout)

    def test_a_project_without_a_lock_file_is_a_leftover(self):
        name = self.project()
        self.assertIn(f"Removed {name}.", self.cleanup(name).stdout)
        self.assertFalse(self.exists(name))

    def test_project_names_outside_the_pattern_are_never_listed(self):
        r = self.cleanup("cpintel")
        self.assertIn("Nothing left behind", r.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
