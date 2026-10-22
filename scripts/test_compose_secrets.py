#!/usr/bin/env python3
"""
Tests that no secret from .env reaches a command line in docker-compose.yml.

    python3 -I scripts/test_compose_secrets.py

Rendered configuration: `docker compose config` (no daemon, nothing started) with a marker value
for every secret, for the development file and with the production overlay; no service's command,
entrypoint or health check may contain one. Secrets may only be in a service's environment, which
`docker inspect` still shows to root and the docker group - documented, not tested away.

Redis at run time: a throwaway redis:7.2-alpine started exactly as the rendered configuration
says, with a password holding a quote and a backslash; the password must not be in its process
list or `docker inspect`'s command, unauthenticated commands must be refused, and the rendered
health check must pass, and fail with a wrong password. Skipped without Docker, or with
CPINTEL_NO_DOCKER=1. Standard library only.
"""
import json
import os
import shutil
import subprocess
import tempfile
import time
import unittest
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SECRETS = ["POSTGRES_PASSWORD", "MONGO_PASSWORD", "MONGO_APP_PASSWORD", "REDIS_PASSWORD", "JWT_SECRET",
           "CPINTEL_SESSION_KEY", "CPINTEL_DOMJUDGE_CREDENTIAL_KEY", "CPINTEL_EXAM_PASSWORD_KEY",
           "CPINTEL_RUNNER_TOKEN", "CPINTEL_ADMIN_PASSWORD", "SMTP_PASSWORD", "GRAFANA_PASSWORD",
           "CPINTEL_DESKTOP_GITHUB_TOKEN"]
TRICKY = 'q"uo\\te-'


def _docker_cli():
    return shutil.which("docker") is not None and \
        subprocess.run(["docker", "compose", "version"], capture_output=True).returncode == 0


def _docker_ok():
    if os.environ.get("CPINTEL_NO_DOCKER") == "1" or not _docker_cli():
        return False
    return subprocess.run(["docker", "info"], capture_output=True).returncode == 0


def render(markers, prod=False, profiles=()):
    """The resolved configuration, with `markers` as the secrets; never printed."""
    with tempfile.TemporaryDirectory(prefix="cpintel-compose-test-") as tmp:
        env_file = Path(tmp) / "test.env"
        lines = [f"{k}={v}" for k, v in markers.items()]
        lines += ["POSTGRES_USER=cpintel", "MONGO_USER=cpintel_root", "MONGO_APP_USER=cpintel_app"]
        env_file.write_text("\n".join(lines) + "\n")
        files = ["-f", str(ROOT / "docker-compose.yml")]
        if prod:
            files += ["-f", str(ROOT / "docker-compose.prod.yml")]
        args = ["docker", "compose", "-p", "cpintel-secrets-test", "--env-file", str(env_file), *files]
        for p in profiles:
            args += ["--profile", p]
        env = {k: v for k, v in os.environ.items() if k not in markers and not k.startswith(("CPINTEL_", "MONGO_", "POSTGRES_"))}
        env["CPINTEL_VERSION"] = "test"
        r = subprocess.run([*args, "config", "--format", "json"], capture_output=True, text=True, env=env)
        if r.returncode != 0:
            raise AssertionError("compose config failed: " + r.stderr[-300:].replace(markers["REDIS_PASSWORD"], "<redacted>"))
        return json.loads(r.stdout)


def unescape(value):
    """compose config writes a literal $ as $$; the container gets one."""
    if isinstance(value, list):
        return [unescape(v) for v in value]
    return value.replace("$$", "$") if isinstance(value, str) else value


@unittest.skipUnless(_docker_cli(), "needs the docker CLI with compose")
class RenderedConfigTest(unittest.TestCase):

    def setUp(self):
        self.markers = {k: f"MARKER{k.lower()}{uuid.uuid4().hex[:8]}" for k in SECRETS}
        self.markers["REDIS_PASSWORD"] += TRICKY

    def assert_no_secret_on_a_command_line(self, config):
        for name, service in config["services"].items():
            places = {"command": service.get("command"), "entrypoint": service.get("entrypoint"),
                      "healthcheck": (service.get("healthcheck") or {}).get("test")}
            for place, value in places.items():
                text = json.dumps(value)
                for key, marker in self.markers.items():
                    self.assertNotIn(marker.split('"')[0], text, f"{key} is in {name}'s {place}")

    def test_development_file(self):
        self.assert_no_secret_on_a_command_line(render(self.markers, profiles=("monitoring", "logs", "letsencrypt")))

    def test_with_the_production_overlay(self):
        self.assert_no_secret_on_a_command_line(render(self.markers, prod=True, profiles=("monitoring", "logs", "letsencrypt")))

    def test_redis_gets_its_password_from_its_environment(self):
        redis = render(self.markers)["services"]["redis"]
        self.assertEqual(redis["environment"]["REDIS_PASSWORD"], self.markers["REDIS_PASSWORD"])
        self.assertNotIn("--requirepass", json.dumps(redis["command"]))
        self.assertNotIn("-a", redis["healthcheck"]["test"])


@unittest.skipUnless(_docker_ok(), "needs docker (or CPINTEL_NO_DOCKER=1 was set)")
class RedisRuntimeTest(unittest.TestCase):

    def setUp(self):
        self.password = f"redis-{uuid.uuid4().hex}" + TRICKY
        markers = {k: f"x{uuid.uuid4().hex}" for k in SECRETS}
        markers["REDIS_PASSWORD"] = self.password
        self.redis = render(markers)["services"]["redis"]
        self.name = f"cpintel-test-redis-{uuid.uuid4().hex[:10]}"
        env = dict(os.environ, REDIS_PASSWORD=self.password)
        # As Compose starts it: the rendered command, the password only in the environment.
        subprocess.run(["docker", "run", "-d", "--rm", "--name", self.name, "-e", "REDIS_PASSWORD",
                        self.redis["image"], *unescape(self.redis["command"])],
                       env=env, capture_output=True, check=True)
        # -v: the image declares /data a volume; without it the anonymous volume is left behind.
        self.addCleanup(subprocess.run, ["docker", "rm", "-f", "-v", self.name], capture_output=True)
        for _ in range(50):
            if "Ready to accept connections" in self.logs():
                return
            time.sleep(0.2)
        self.fail("redis did not start")

    def logs(self):
        r = subprocess.run(["docker", "logs", self.name], capture_output=True, text=True)
        return r.stdout + r.stderr

    def exec_sh(self, script, password=None):
        env = dict(os.environ, REDIS_PASSWORD=password) if password is not None else None
        args = ["docker", "exec"] + (["-e", "REDIS_PASSWORD"] if password is not None else []) + [self.name, "sh", "-c", script]
        return subprocess.run(args, capture_output=True, text=True, env=env)

    def test_the_password_is_on_no_command_line(self):
        top = subprocess.run(["docker", "top", self.name], capture_output=True, text=True).stdout
        self.assertIn("redis-server", top)
        self.assertNotIn(self.password.split('"')[0], top)
        cmd = subprocess.run(["docker", "inspect", "-f", "{{json .Config.Cmd}}", self.name], capture_output=True, text=True).stdout
        self.assertNotIn(self.password.split('"')[0], cmd)
        self.assertNotIn(self.password.split('"')[0], self.logs())

    def test_authentication_is_required_and_the_health_check_passes(self):
        self.assertIn("NOAUTH", self.exec_sh("redis-cli ping").stdout)
        health = unescape(self.redis["healthcheck"]["test"])
        self.assertEqual(health[0], "CMD-SHELL")
        self.assertEqual(self.exec_sh(health[1]).returncode, 0, "the rendered health check failed")
        self.assertNotEqual(self.exec_sh(health[1], password="wrong").returncode, 0,
                            "the health check passed with a wrong password")
        # The settings that came after the password on the command line still apply.
        policy = self.exec_sh('REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli config get maxmemory-policy').stdout
        self.assertIn("noeviction", policy)


if __name__ == "__main__":
    unittest.main(verbosity=2)
