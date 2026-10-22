#!/usr/bin/env python3
"""
Refuses a resolved Compose configuration that could touch anything outside one test project.

    docker compose -p <project> ... config --format json | python3 -I isolation.py <project> <root>

Reads the configuration on stdin (it holds the run's generated secrets, so nothing of it is
printed: only a one-line summary, or which rule failed and for which service). Exits 1 if:

  - the project is not cpintel-it-<lowercase letters, digits and dashes> - the development
    stack's project is "cpintel", whose volumes are cpintel_*;
  - a container is not named <project>-<something>;
  - a named volume is external, or not called <project>_<something> (so never cpintel_*);
  - a bind mount is writable, or comes from outside <root> (the repository checkout);
  - a port is published anywhere but 127.0.0.1, or by anything but nginx;
  - a network is external, not called <project>_<something>, or pins an address range.

Standard library only.
"""
import json
import re
import sys
from pathlib import Path


def problems(config, project, root):
    """Every rule the configuration breaks, as readable lines (empty if none)."""
    found = []
    prefix = project + "_"
    root = Path(root).resolve()
    for name, service in sorted(config.get("services", {}).items()):
        container = service.get("container_name", "")
        if not container.startswith(project + "-"):
            found.append(f"{name}: container name is not {project}-*")
        for port in service.get("ports", []) or []:
            if name != "nginx":
                found.append(f"{name}: publishes a port")
            elif port.get("host_ip") != "127.0.0.1":
                found.append(f"{name}: publishes a port beyond 127.0.0.1")
        for mount in service.get("volumes", []) or []:
            kind = mount.get("type")
            if kind == "bind":
                source = Path(mount.get("source", "")).resolve()
                if root != source and root not in source.parents:
                    found.append(f"{name}: binds a path outside the checkout")
                if not mount.get("read_only"):
                    found.append(f"{name}: binds a path writable")
            elif kind == "volume":
                if not mount.get("source"):
                    continue    # an anonymous volume: created for, and removed with, this container
                volume = config.get("volumes", {}).get(mount["source"], {})
                if volume.get("external") or not str(volume.get("name", "")).startswith(prefix):
                    found.append(f"{name}: uses a volume that is not {prefix}*")
            elif kind != "tmpfs":
                found.append(f"{name}: mounts a {kind}")
    for key, volume in sorted(config.get("volumes", {}).items()):
        if volume.get("external") or not str(volume.get("name", "")).startswith(prefix):
            found.append(f"volume {key}: not {prefix}*")
    for key, network in sorted(config.get("networks", {}).items()):
        if network.get("external") or not str(network.get("name", "")).startswith(prefix):
            found.append(f"network {key}: not {prefix}*")
        if (network.get("ipam") or {}).get("config"):
            found.append(f"network {key}: pins an address range")
    return found


def main(argv):
    if len(argv) != 3:
        print(__doc__.strip().splitlines()[2].strip())
        return 2
    project, root = argv[1], argv[2]
    if not re.fullmatch(r"cpintel-it-[a-z0-9][a-z0-9-]*", project):
        print("!!! The project must be called cpintel-it-<run id>.")
        return 1
    try:
        config = json.load(sys.stdin)
    except ValueError:
        print("!!! The resolved configuration is not JSON.")
        return 1
    if config.get("name") != project:
        print("!!! The configuration is for another project.")
        return 1
    found = problems(config, project, root)
    for line in found:
        print("!!! " + line)
    if found:
        return 1
    services = sorted(config.get("services", {}))
    print(f"  isolated: {len(services)} services, volumes and networks all {project}_*, "
          "nothing published but nginx on 127.0.0.1")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
