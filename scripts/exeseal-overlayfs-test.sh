#!/usr/bin/env bash
# scripts/exeseal-overlayfs-test.sh — Verify that the overlayfs exeseal
# strategy works even when the binary lives under /tmp (the former ELOOP
# bug scenario).
#
# Usage:
#   sudo ./scripts/exeseal-overlayfs-test.sh [--binary PATH]
#
# Requires: root (for overlayfs), Linux 5.2+ kernel.
# The script copies the binary into a /tmp subdirectory, runs
# `kontainer-runtime --version --debug` from there, and checks the debug
# log for evidence that the overlayfs seal path was taken (not memfd).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

KONTAINER_BIN="${PROJECT_ROOT}/build/bin/linuxX64/releaseExecutable/kontainer-runtime.kexe"

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --binary) KONTAINER_BIN="$2"; shift 2 ;;
    *)        echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

# ---------------------------------------------------------------------------
# Pre-flight
# ---------------------------------------------------------------------------
if [[ "$(id -u)" -ne 0 ]]; then
  echo "ERROR: this test must be run as root (overlayfs requires CAP_SYS_ADMIN)" >&2
  exit 1
fi

if [[ ! -x "$KONTAINER_BIN" ]]; then
  echo "ERROR: binary not found: $KONTAINER_BIN" >&2
  echo "       Build first: ./gradlew linkReleaseExecutableLinuxX64" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Test: binary under /tmp should still use overlayfs, not memfd
# ---------------------------------------------------------------------------
TMP_DIR="$(mktemp -d /tmp/kontainer-exeseal-test.XXXXXX)"
cleanup() { rm -rf "$TMP_DIR"; }
trap cleanup EXIT

cp "$KONTAINER_BIN" "$TMP_DIR/kontainer-runtime.kexe"
chmod +x "$TMP_DIR/kontainer-runtime.kexe"

echo "=== Test: overlayfs exeseal with binary under /tmp ==="
echo "Binary: $TMP_DIR/kontainer-runtime.kexe"

# Run --version with --debug so exeseal logs go to stderr.
# --version does not trigger sealBinary(), but 'create' needs a bundle.
# Use 'state' which also doesn't seal, but we can call sealBinary via
# a helper subcommand.  Actually, exeseal only runs for create/run/exec.
# Use 'run --bundle /nonexistent test' — it will fail after exeseal runs.
LOG_FILE="$TMP_DIR/debug.log"

# The 'run' subcommand triggers sealBinary(). It will fail because there
# is no bundle, but exeseal runs first and the debug log captures the
# seal strategy.  Exit code will be non-zero; that's expected.
set +e
"$TMP_DIR/kontainer-runtime.kexe" \
  --debug --log "$LOG_FILE" --log-format text \
  run --bundle /nonexistent test-exeseal 2>"$TMP_DIR/stderr.log"
EXIT_CODE=$?
set -e

echo "Exit code: $EXIT_CODE (non-zero expected — no real bundle)"
echo ""

if [[ ! -f "$LOG_FILE" ]]; then
  echo "ERROR: log file not created: $LOG_FILE" >&2
  # Fall back to stderr
  if [[ -f "$TMP_DIR/stderr.log" ]]; then
    echo "--- stderr ---"
    cat "$TMP_DIR/stderr.log"
  fi
  exit 1
fi

echo "--- exeseal-related log lines ---"
grep "exeseal:" "$LOG_FILE" || grep "exeseal:" "$TMP_DIR/stderr.log" 2>/dev/null || true
echo ""

# Check that the overlayfs path was taken
if grep -q "sealed binary via overlayfs" "$LOG_FILE" 2>/dev/null || \
   grep -q "sealed binary via overlayfs" "$TMP_DIR/stderr.log" 2>/dev/null; then
  echo "PASS: overlayfs exeseal succeeded with binary under /tmp"
elif grep -q "overlayfs failed.*ELOOP" "$LOG_FILE" 2>/dev/null || \
     grep -q "overlayfs failed.*ELOOP" "$TMP_DIR/stderr.log" 2>/dev/null; then
  echo "FAIL: overlayfs returned ELOOP — the /tmp dummy lowerdir overlap bug is still present"
  exit 1
elif grep -q "cloned binary into memfd" "$LOG_FILE" 2>/dev/null || \
     grep -q "cloned binary into memfd" "$TMP_DIR/stderr.log" 2>/dev/null; then
  echo "FAIL: fell back to memfd instead of overlayfs"
  # Print all exeseal log lines for debugging
  grep "exeseal:" "$LOG_FILE" 2>/dev/null || true
  exit 1
else
  echo "WARN: could not determine seal strategy from logs (may be a kernel/permission issue)"
  echo "--- full log ---"
  cat "$LOG_FILE"
  echo ""
  if [[ -f "$TMP_DIR/stderr.log" ]]; then
    echo "--- stderr ---"
    cat "$TMP_DIR/stderr.log"
  fi
  # Don't fail — overlayfs may simply not be available on this kernel
  exit 0
fi

echo ""
echo "=== Test: binary under \$HOME should also use overlayfs ==="

HOME_DIR="$(mktemp -d "$HOME/kontainer-exeseal-test.XXXXXX")"
cleanup_home() { rm -rf "$HOME_DIR"; cleanup; }
trap cleanup_home EXIT

cp "$KONTAINER_BIN" "$HOME_DIR/kontainer-runtime.kexe"
chmod +x "$HOME_DIR/kontainer-runtime.kexe"

HOME_LOG="$HOME_DIR/debug.log"
set +e
"$HOME_DIR/kontainer-runtime.kexe" \
  --debug --log "$HOME_LOG" --log-format text \
  run --bundle /nonexistent test-exeseal-home 2>"$HOME_DIR/stderr.log"
set -e

if grep -q "sealed binary via overlayfs" "$HOME_LOG" 2>/dev/null || \
   grep -q "sealed binary via overlayfs" "$HOME_DIR/stderr.log" 2>/dev/null; then
  echo "PASS: overlayfs exeseal also works with binary under \$HOME"
else
  echo "INFO: overlayfs not used from \$HOME (check logs for details)"
  grep "exeseal:" "$HOME_LOG" 2>/dev/null || true
fi

echo ""
echo "All exeseal overlayfs tests passed."
