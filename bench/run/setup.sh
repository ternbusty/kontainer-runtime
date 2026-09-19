#!/usr/bin/env bash
# bench/run/setup.sh — Prepare OCI bundles for the runtime benchmark.
#
# Creates under bench/run/bundles/ (gitignored):
#   rootfs/            busybox (glibc, amd64) — the same image runc's integration tests use
#   minimal/           config.json: pid+mount+uts+ipc namespaces, /proc + tmpfs /dev only,
#                      no caps, no seccomp, no cgroup resources, process = /bin/true
#                      -> runtime fixed cost. (tmpfs /dev is required: runtimes create
#                      default /dev symlinks and would otherwise write into the shared rootfs)
#   default/           config.json: `runc spec` output as-is except process = /bin/true
#                      (pid/net/ipc/uts/mount namespaces, capability set, rlimits,
#                      maskedPaths, readonlyPaths, cgroup-less; no seccomp, no user ns)
#   sleep/             like default/ but process = sleep infinity  -> target for `exec`
#
# Requires: a built runc (for `runc spec`), curl, python3.  No root needed.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WS="${WS:-$HOME/workspace}"
RUNC="${RUNC:-$WS/runc/runc}"
BUNDLES="$SCRIPT_DIR/bundles"
ROOTFS_URL="https://github.com/docker-library/busybox/raw/71dbea6bedb4bb497581d61bb025f8558cc7833a/latest/glibc/amd64/rootfs.tar.gz"

mkdir -p "$BUNDLES"

# --- rootfs -----------------------------------------------------------------
if [ ! -x "$BUNDLES/rootfs/bin/busybox" ]; then
  echo ">>> fetching busybox rootfs"
  curl -fsSL --retry 5 -o "$BUNDLES/rootfs.tar.gz" "$ROOTFS_URL"
  mkdir -p "$BUNDLES/rootfs"
  tar -C "$BUNDLES/rootfs" -xzf "$BUNDLES/rootfs.tar.gz"
fi
echo "rootfs: $BUNDLES/rootfs ($("$BUNDLES/rootfs/bin/busybox" 2>&1 | head -1))"

# --- minimal ----------------------------------------------------------------
mkdir -p "$BUNDLES/minimal"
cat > "$BUNDLES/minimal/config.json" <<JSON
{
  "ociVersion": "1.0.2",
  "process": {
    "terminal": false,
    "user": { "uid": 0, "gid": 0 },
    "args": [ "/bin/true" ],
    "env": [ "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" ],
    "cwd": "/",
    "noNewPrivileges": true
  },
  "root": { "path": "../rootfs", "readonly": true },
  "hostname": "bench",
  "mounts": [
    { "destination": "/proc", "type": "proc", "source": "proc" },
    { "destination": "/dev", "type": "tmpfs", "source": "tmpfs",
      "options": [ "nosuid", "strictatime", "mode=755", "size=65536k" ] }
  ],
  "linux": {
    "namespaces": [
      { "type": "pid" }, { "type": "mount" }, { "type": "uts" }, { "type": "ipc" }
    ]
  }
}
JSON

# --- default (runc spec) ------------------------------------------------------
[ -x "$RUNC" ] || { echo "runc binary not found at $RUNC (build it first)"; exit 1; }
mkdir -p "$BUNDLES/default" "$BUNDLES/sleep"
( cd "$BUNDLES/default" && rm -f config.json && "$RUNC" spec )
python3 - "$BUNDLES" <<'PY'
import json, sys, pathlib
b = pathlib.Path(sys.argv[1])
c = json.loads((b / "default/config.json").read_text())
c["root"]["path"] = "../rootfs"
c["process"]["terminal"] = False
c["process"]["args"] = ["/bin/true"]
(b / "default/config.json").write_text(json.dumps(c, indent=2) + "\n")
c["process"]["args"] = ["/bin/sleep", "infinity"]
(b / "sleep/config.json").write_text(json.dumps(c, indent=2) + "\n")
PY

for d in minimal default sleep; do
  python3 -c "
import json,sys; c=json.load(open('$BUNDLES/$d/config.json'))
print('%-8s args=%s ns=%s caps=%s masked=%d seccomp=%s' % ('$d', c['process']['args'], [n['type'] for n in c['linux']['namespaces']],
  len(c['process'].get('capabilities',{}).get('bounding',[])), len(c['linux'].get('maskedPaths',[])), 'seccomp' in c['linux']))"
done
echo "bundles ready: $BUNDLES"
