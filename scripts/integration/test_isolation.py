#!/usr/bin/env python3
"""
Tests for isolation.py, the check integration-test.sh runs on the resolved Compose configuration
before it starts anything. No Docker: the configurations are written out here.

    python3 -I scripts/integration/test_isolation.py

Standard library only.
"""
import copy
import io
import json
import sys
import unittest
from contextlib import redirect_stdout
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import isolation  # noqa: E402

PROJECT = "cpintel-it-20261022-abc123"
ROOT = "/repo/cpintel"


def isolated():
    """A configuration shaped like docker-compose.yml + docker-compose.it.yml, resolved."""
    return {
        "name": PROJECT,
        "services": {
            "mongodb": {
                "container_name": f"{PROJECT}-mongo",
                "volumes": [
                    {"type": "volume", "source": "mongo_data", "target": "/data/db"},
                    {"type": "bind", "source": f"{ROOT}/mongo-init.js", "target": "/docker-entrypoint-initdb.d/init.js", "read_only": True},
                ],
            },
            "backend": {"container_name": f"{PROJECT}-backend",
                        "volumes": [{"type": "bind", "source": f"{ROOT}/desktop", "target": "/desktop", "read_only": True}]},
            "nginx": {"container_name": f"{PROJECT}-nginx",
                      "ports": [{"host_ip": "127.0.0.1", "published": "18443", "target": 443}],
                      "volumes": [{"type": "volume", "source": "letsencrypt", "target": "/etc/letsencrypt"}]},
        },
        "volumes": {"mongo_data": {"name": f"{PROJECT}_mongo_data"}, "letsencrypt": {"name": f"{PROJECT}_letsencrypt"}},
        "networks": {"cpintel-net": {"name": f"{PROJECT}_cpintel-net", "ipam": {}}},
    }


def run(config, project=PROJECT):
    out = io.StringIO()
    old = sys.stdin
    sys.stdin = io.StringIO(json.dumps(config))
    try:
        with redirect_stdout(out):
            code = isolation.main(["isolation.py", project, ROOT])
    finally:
        sys.stdin = old
    return code, out.getvalue()


class IsolationTest(unittest.TestCase):

    def test_the_test_projects_configuration_passes(self):
        code, out = run(isolated())
        self.assertEqual(code, 0, out)
        self.assertIn("isolated: 3 services", out)

    def test_the_development_project_is_refused_by_name(self):
        config = isolated()
        config["name"] = "cpintel"
        code, out = run(config, project="cpintel")
        self.assertEqual(code, 1)
        self.assertIn("cpintel-it-<run id>", out)

    def test_a_development_volume_is_refused(self):
        config = isolated()
        config["volumes"]["mongo_data"]["name"] = "cpintel_mongo_data"
        code, out = run(config)
        self.assertEqual(code, 1)
        self.assertIn("mongodb: uses a volume that is not", out)

    def test_an_external_volume_is_refused(self):
        config = isolated()
        config["volumes"]["mongo_data"]["external"] = True
        self.assertEqual(run(config)[0], 1)

    def test_a_writable_or_outside_bind_is_refused(self):
        writable = isolated()
        writable["services"]["backend"]["volumes"][0]["read_only"] = False
        code, out = run(writable)
        self.assertEqual(code, 1)
        self.assertIn("binds a path writable", out)

        outside = isolated()
        outside["services"]["backend"]["volumes"][0]["source"] = "/var/lib/docker/volumes"
        code, out = run(outside)
        self.assertEqual(code, 1)
        self.assertIn("outside the checkout", out)

    def test_published_ports_beyond_nginx_on_loopback_are_refused(self):
        config = isolated()
        config["services"]["mongodb"]["ports"] = [{"host_ip": "127.0.0.1", "published": "27017"}]
        self.assertIn("mongodb: publishes a port", run(config)[1])

        config = isolated()
        config["services"]["nginx"]["ports"][0]["host_ip"] = "0.0.0.0"
        self.assertIn("beyond 127.0.0.1", run(config)[1])

    def test_container_names_and_networks_must_carry_the_project(self):
        config = isolated()
        config["services"]["mongodb"]["container_name"] = "cpintel-mongo"
        self.assertIn("container name is not", run(config)[1])

        config = isolated()
        config["networks"]["cpintel-net"]["ipam"] = {"config": [{"subnet": "172.20.0.0/16"}]}
        self.assertIn("pins an address range", run(config)[1])

    def test_a_configuration_for_another_project_is_refused(self):
        code, out = run(isolated(), project="cpintel-it-other")
        self.assertEqual(code, 1)
        self.assertIn("another project", out)

    def test_nothing_from_the_configuration_is_printed(self):
        config = copy.deepcopy(isolated())
        config["services"]["backend"]["environment"] = {"JWT_SECRET": "a-secret-that-must-not-print"}
        config["services"]["backend"]["container_name"] = "wrong"
        code, out = run(config)
        self.assertEqual(code, 1)
        self.assertNotIn("a-secret-that-must-not-print", out)
        self.assertNotIn("wrong", out.replace("container name is not", ""))


if __name__ == "__main__":
    unittest.main(verbosity=2)
