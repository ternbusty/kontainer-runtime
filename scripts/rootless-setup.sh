#!/usr/bin/env bash
# scripts/rootless-setup.sh — Prepare a host for
# `scripts/runc-compat-test.sh --rootless`, mirroring runc's
# script/setup_rootless.sh and the enable_idmap / enable_cgroup hooks of its
# tests/rootless.sh. Meant for throwaway CI runners: it adds a user, edits
# /etc/subuid and /etc/subgid, and delegates a cgroup.
#
# Usage:
#   sudo ./scripts/rootless-setup.sh [--user NAME] [--features FEATURES]
#
# FEATURES is "" or a '+'-separated subset of:
#   idmap   map a range of subordinate ids (needs newuidmap/newgidmap from
#           the uidmap package) instead of just the user's own uid/gid
#   cgroup  delegate /sys/fs/cgroup/runc-cgroups-integration-test to the user
#
# Prints the KEY=VALUE environment that runc-compat-test.sh --rootless needs,
# one per line (append it to $GITHUB_ENV in CI).

set -euo pipefail

ROOTLESS_USER=rootless
ROOTLESS_FEATURES=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --user)     ROOTLESS_USER="$2"; shift 2 ;;
    --features) ROOTLESS_FEATURES="$2"; shift 2 ;;
    *)          echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

if [[ $EUID -ne 0 ]]; then
  echo "ERROR: must run as root" >&2
  exit 1
fi

has_feature() {
  [[ "+$ROOTLESS_FEATURES+" == *"+$1+"* ]]
}

if ! id "$ROOTLESS_USER" >/dev/null 2>&1; then
  useradd -u 2000 -m -d "/home/$ROOTLESS_USER" -s /bin/bash "$ROOTLESS_USER" >&2
fi

echo "ROOTLESS_USER=$ROOTLESS_USER"
echo "ROOTLESS_FEATURES=$ROOTLESS_FEATURES"

# FEATURE idmap: subordinate id ranges for newuidmap/newgidmap.
if has_feature idmap; then
  ROOTLESS_UIDMAP_START=100000 ROOTLESS_UIDMAP_LENGTH=65536
  ROOTLESS_GIDMAP_START=200000 ROOTLESS_GIDMAP_LENGTH=65536
  for f in subuid subgid; do
    touch "/etc/$f"
    grep -v "^$ROOTLESS_USER:" "/etc/$f" >"/etc/$f.tmp" || true
    if [[ $f == subuid ]]; then
      echo "$ROOTLESS_USER:$ROOTLESS_UIDMAP_START:$ROOTLESS_UIDMAP_LENGTH" >>"/etc/$f.tmp"
    else
      echo "$ROOTLESS_USER:$ROOTLESS_GIDMAP_START:$ROOTLESS_GIDMAP_LENGTH" >>"/etc/$f.tmp"
    fi
    mv "/etc/$f.tmp" "/etc/$f"
  done
  if ! command -v newuidmap >/dev/null || ! command -v newgidmap >/dev/null; then
    echo "ERROR: idmap needs newuidmap/newgidmap (install the uidmap package)" >&2
    exit 1
  fi

  # A directory owned by uid $ROOTLESS_AUX_UID inside the container, used by
  # a test case in cwd.bats (1000 is the containerID of the uid mapping that
  # runc's helpers.bash sets up for idmap).
  ROOTLESS_AUX_UID=1024
  ROOTLESS_AUX_DIR="$(mktemp -d)"
  chown "$((ROOTLESS_UIDMAP_START - 1000 + ROOTLESS_AUX_UID))" "$ROOTLESS_AUX_DIR"

  echo "ROOTLESS_UIDMAP_START=$ROOTLESS_UIDMAP_START"
  echo "ROOTLESS_UIDMAP_LENGTH=$ROOTLESS_UIDMAP_LENGTH"
  echo "ROOTLESS_GIDMAP_START=$ROOTLESS_GIDMAP_START"
  echo "ROOTLESS_GIDMAP_LENGTH=$ROOTLESS_GIDMAP_LENGTH"
  echo "ROOTLESS_AUX_UID=$ROOTLESS_AUX_UID"
  echo "ROOTLESS_AUX_DIR=$ROOTLESS_AUX_DIR"
fi

# FEATURE cgroup: delegate a cgroup v2 subtree to the user. See
# https://www.kernel.org/doc/html/latest/admin-guide/cgroup-v2.html#delegation-containment
if has_feature cgroup; then
  CGROUP_MOUNT=/sys/fs/cgroup
  CGROUP_PATH="$CGROUP_MOUNT/runc-cgroups-integration-test"
  if [[ ! -e "$CGROUP_MOUNT/cgroup.controllers" ]]; then
    echo "ERROR: cgroup feature needs cgroup v2" >&2
    exit 1
  fi
  # Some controllers (e.g. memory) may fail to enable; that is fine.
  for c in $(cat "$CGROUP_MOUNT/cgroup.controllers"); do
    echo "+$c" >"$CGROUP_MOUNT/cgroup.subtree_control" 2>/dev/null || true
  done
  mkdir -p "$CGROUP_PATH"
  chown "root:$ROOTLESS_USER" "$CGROUP_PATH" "$CGROUP_PATH/cgroup.subtree_control" \
    "$CGROUP_PATH/cgroup.procs" "$CGROUP_MOUNT/cgroup.procs"
  chmod g+rwx "$CGROUP_PATH"
  chmod g+rw "$CGROUP_PATH/cgroup.subtree_control" "$CGROUP_PATH/cgroup.procs" \
    "$CGROUP_MOUNT/cgroup.procs"
fi
