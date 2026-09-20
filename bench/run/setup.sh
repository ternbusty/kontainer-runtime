#!/usr/bin/env bash
# bench/run/setup.sh — Fetch the shared rootfs for the runtime benchmark bundles.
#
# Bundle configs (config.json) are checked into the repo under bundles/.
# This script only downloads the busybox rootfs they reference.
#
# Requires: curl.  No root needed.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUNDLES="$SCRIPT_DIR/bundles"
ROOTFS_URL="https://github.com/docker-library/busybox/raw/71dbea6bedb4bb497581d61bb025f8558cc7833a/latest/glibc/amd64/rootfs.tar.gz"

if [ -x "$BUNDLES/rootfs/bin/busybox" ]; then
  echo "rootfs already exists: $BUNDLES/rootfs"
else
  echo ">>> fetching busybox rootfs"
  curl -fsSL --retry 5 -o "$BUNDLES/rootfs.tar.gz" "$ROOTFS_URL"
  mkdir -p "$BUNDLES/rootfs"
  tar -C "$BUNDLES/rootfs" -xzf "$BUNDLES/rootfs.tar.gz"
  rm -f "$BUNDLES/rootfs.tar.gz"
fi

echo "rootfs: $BUNDLES/rootfs ($("$BUNDLES/rootfs/bin/busybox" 2>&1 | head -1))"
echo "bundles ready: $BUNDLES"
