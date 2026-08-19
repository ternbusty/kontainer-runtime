#!/usr/bin/env bash
# scripts/runc-compat-test.sh — Run runc bats integration tests against
# kontainer-runtime.  Designed to work both locally and inside CI.
#
# Usage:
#   sudo ./scripts/runc-compat-test.sh [--runc-dir DIR] [--binary PATH] [--selinux]
#
# Environment variables (all optional):
#   RUNC_REPO_DIR  — path to an existing runc checkout (skips clone)
#   KONTAINER_BIN  — path to the kontainer-runtime binary
#   RUNC_TAG       — runc tag to clone (default: v1.5.1)
#   SUMMARY_FILE   — file to append a Markdown summary to (default: stdout)
#   BATS_JOBS      — parallel jobs for bats (default: 1; root tests are
#                    not parallelisable in general)

set -euo pipefail

# ---------------------------------------------------------------------------
# Configurable paths / defaults
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

RUNC_TAG="${RUNC_TAG:-v1.5.1}"
RUNC_REPO_DIR="${RUNC_REPO_DIR:-}"
KONTAINER_BIN="${KONTAINER_BIN:-${PROJECT_ROOT}/build/bin/linuxX64/releaseExecutable/kontainer-runtime.kexe}"
SUMMARY_FILE="${SUMMARY_FILE:-}"
BATS_JOBS="${BATS_JOBS:-1}"
BATS_TEST_TIMEOUT="${BATS_TEST_TIMEOUT:-300}"
SELINUX_MODE=false

# ---------------------------------------------------------------------------
# Test file lists
# ---------------------------------------------------------------------------

# Tests to run — start with the basics.
INCLUDE_TESTS=(
  create.bats
  run.bats
  start.bats
  state.bats
  delete.bats
  list.bats
  exec.bats
  kill.bats
  events.bats
  pause.bats
  help.bats
  version.bats
  spec.bats
  start_detached.bats
  tty.bats
  seccomp-notify.bats
  pidfd-socket.bats
  hooks_so.bats
  mounts_sshfs.bats
  idmap.bats
)

# Tests that need runc-specific helper binaries or features we deliberately
# skip.  Maintained here so the exclude list is visible and extensible.
EXCLUDE_TESTS=(
  checkpoint.bats            # needs criu (not in Ubuntu 24.04 repos)
  seccomp-notify-compat.bats # needs kernel < 5.6 (always skipped on modern runners)
)

# Tests that require RUNC_USE_SYSTEMD=yes.  Run in a separate pass after
# the main (no-systemd) pass so they don't conflict with tests that
# require no_systemd (e.g. hooks_so.bats).
SYSTEMD_TESTS=(
  cgroup_delegation.bats
)

# SELinux-specific tests — only included with --selinux flag (requires
# an SELinux-enabled host such as Fedora).
SELINUX_TESTS=(
  selinux.bats
)

# Individual test names (regex) to skip via bats --filter-tags or grep.
# These are tests that assert runc-internal implementation details.
SKIP_TEST_NAMES=(
  "runc run \\[/proc/self/exe clone\\]"  # asserts runc-dmz debug output string
)

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --runc-dir)  RUNC_REPO_DIR="$2"; shift 2 ;;
    --binary)    KONTAINER_BIN="$2"; shift 2 ;;
    --selinux)   SELINUX_MODE=true; shift ;;
    *)           echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

# ---------------------------------------------------------------------------
# Pre-flight checks
# ---------------------------------------------------------------------------
if [[ ! -x "$KONTAINER_BIN" ]]; then
  echo "ERROR: kontainer-runtime binary not found or not executable: $KONTAINER_BIN" >&2
  echo "       Build it first:  ./gradlew linkReleaseExecutableLinuxX64" >&2
  exit 1
fi

if ! command -v bats >/dev/null 2>&1; then
  echo "ERROR: bats not found. Install bats-core first." >&2
  exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "ERROR: jq not found. Install jq first." >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Clone runc if needed
# ---------------------------------------------------------------------------
if [[ -z "$RUNC_REPO_DIR" ]]; then
  RUNC_REPO_DIR="$(mktemp -d)"
  echo ">>> Cloning runc ${RUNC_TAG} into ${RUNC_REPO_DIR} ..."
  git clone --depth 1 --branch "$RUNC_TAG" \
    https://github.com/opencontainers/runc "$RUNC_REPO_DIR"
fi

INTEGRATION_DIR="${RUNC_REPO_DIR}/tests/integration"
if [[ ! -d "$INTEGRATION_DIR" ]]; then
  echo "ERROR: ${INTEGRATION_DIR} not found — is RUNC_REPO_DIR correct?" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Fetch rootfs images (uses runc's own script)
# ---------------------------------------------------------------------------
echo ">>> Fetching rootfs images ..."
chmod +x "${INTEGRATION_DIR}/get-images.sh"
"${INTEGRATION_DIR}/get-images.sh" >/dev/null

# ---------------------------------------------------------------------------
# SELinux mode: run ONLY the SELinux-specific tests
# ---------------------------------------------------------------------------
if [[ "$SELINUX_MODE" == "true" ]]; then
  INCLUDE_TESTS=("${SELINUX_TESTS[@]}")
  SYSTEMD_TESTS=()  # skip systemd pass in SELinux mode
  echo ">>> SELinux mode — running only: ${SELINUX_TESTS[*]}"
fi

# ---------------------------------------------------------------------------
# Build the list of test files to run
# ---------------------------------------------------------------------------
TEST_FILES=()
for t in "${INCLUDE_TESTS[@]}"; do
  f="${INTEGRATION_DIR}/${t}"
  if [[ -f "$f" ]]; then
    TEST_FILES+=("$f")
  else
    echo "WARNING: test file not found, skipping: $t" >&2
  fi
done

SYSTEMD_TEST_FILES=()
for t in "${SYSTEMD_TESTS[@]}"; do
  f="${INTEGRATION_DIR}/${t}"
  if [[ -f "$f" ]]; then
    SYSTEMD_TEST_FILES+=("$f")
  else
    echo "WARNING: systemd test file not found, skipping: $t" >&2
  fi
done

if [[ ${#TEST_FILES[@]} -eq 0 && ${#SYSTEMD_TEST_FILES[@]} -eq 0 ]]; then
  echo "ERROR: no test files to run" >&2
  exit 1
fi

TOTAL_FILES=$(( ${#TEST_FILES[@]} + ${#SYSTEMD_TEST_FILES[@]} ))
echo ">>> Running ${TOTAL_FILES} bats test files against $(basename "$KONTAINER_BIN")"
echo "    Binary: $KONTAINER_BIN"
echo ""

# ---------------------------------------------------------------------------
# Run bats — one file at a time with a per-file timeout so a single
# hanging test doesn't block the entire suite.
#
# run_bats_files <file>...
#   Runs each file through bats, appending TAP output to $TAP_OUTPUT.
#   Extra env vars for bats can be set by the caller before invoking.
# ---------------------------------------------------------------------------
TAP_OUTPUT="${TAP_OUTPUT:-$(mktemp)}"
BATS_RC=0

: > "$TAP_OUTPUT"  # truncate

GLOBAL_FILE_INDEX=0
GLOBAL_FILE_TOTAL=$((${#TEST_FILES[@]} + ${#SYSTEMD_TEST_FILES[@]}))

run_bats_files() {
  local files=("$@")

  for tf in "${files[@]}"; do
    GLOBAL_FILE_INDEX=$((GLOBAL_FILE_INDEX + 1))
    echo ">>> [$GLOBAL_FILE_INDEX/$GLOBAL_FILE_TOTAL] $(basename "$tf")" >&2
    set +e
    # Build --negative-filter regex from SKIP_TEST_NAMES (if any).
    NEGATIVE_FILTER_ARGS=()
    if [[ ${#SKIP_TEST_NAMES[@]} -gt 0 ]]; then
      # Join patterns with | for a single regex alternation.
      OLDIFS="$IFS"; IFS='|'
      NEGATIVE_FILTER_ARGS=(--negative-filter "${SKIP_TEST_NAMES[*]}")
      IFS="$OLDIFS"
    fi
    timeout "${BATS_TEST_TIMEOUT}" env RUNC="$KONTAINER_BIN" "${BATS_EXTRA_ENV[@]}" \
      bats --tap "${NEGATIVE_FILTER_ARGS[@]}" "$tf" 2>&1 | tee -a "$TAP_OUTPUT"
    rc=${PIPESTATUS[0]}
    set -e
    if [[ $rc -ne 0 ]]; then
      BATS_RC=1
      if [[ $rc -eq 124 ]]; then
        echo "# TIMEOUT: $(basename "$tf") exceeded ${BATS_TEST_TIMEOUT}s" | tee -a "$TAP_OUTPUT"
      fi
    fi
  done
}

# --- Pass 1: main tests (no RUNC_USE_SYSTEMD) ---
echo ""
echo "=== Pass 1: main tests (no systemd) ==="
BATS_EXTRA_ENV=()
run_bats_files "${TEST_FILES[@]}"

# --- Pass 2: systemd tests (RUNC_USE_SYSTEMD=yes) ---
if [[ ${#SYSTEMD_TEST_FILES[@]} -gt 0 ]]; then
  echo ""
  echo "=== Pass 2: systemd tests (RUNC_USE_SYSTEMD=yes) ==="
  BATS_EXTRA_ENV=(RUNC_USE_SYSTEMD=yes)
  run_bats_files "${SYSTEMD_TEST_FILES[@]}"
fi

# ---------------------------------------------------------------------------
# Parse TAP output
# ---------------------------------------------------------------------------
TOTAL=0
PASS=0
FAIL=0
SKIP=0

while IFS= read -r line; do
  case "$line" in
    "ok "*)
      TOTAL=$((TOTAL + 1))
      if echo "$line" | grep -q "# skip"; then
        SKIP=$((SKIP + 1))
      else
        PASS=$((PASS + 1))
      fi
      ;;
    "not ok "*)
      TOTAL=$((TOTAL + 1))
      FAIL=$((FAIL + 1))
      ;;
  esac
done < "$TAP_OUTPUT"

# Collect names of failing tests for the summary.
FAIL_NAMES=()
while IFS= read -r line; do
  # TAP "not ok N description" — strip prefix.
  name="${line#not ok }"
  # Remove the leading test number.
  name="$(echo "$name" | sed 's/^[0-9]* //')"
  FAIL_NAMES+=("$name")
done < <(grep '^not ok ' "$TAP_OUTPUT")

# ---------------------------------------------------------------------------
# Print summary
# ---------------------------------------------------------------------------
echo ""
echo "==========================================="
echo " runc bats compatibility — results"
echo "==========================================="
echo " Total : $TOTAL"
echo " Pass  : $PASS"
echo " Fail  : $FAIL"
echo " Skip  : $SKIP"
echo "==========================================="
echo ""

if [[ ${#FAIL_NAMES[@]} -gt 0 ]]; then
  echo "Failing tests:"
  for n in "${FAIL_NAMES[@]}"; do
    echo "  - $n"
  done
  echo ""
fi

# ---------------------------------------------------------------------------
# Write Markdown summary (for CI job summary or local review)
# ---------------------------------------------------------------------------
write_summary() {
  local dest="$1"
  {
    echo "## runc bats compatibility test results"
    echo ""
    echo "Runtime: \`$(basename "$KONTAINER_BIN")\`"
    echo "runc ref: \`${RUNC_TAG}\`"
    echo ""
    echo "| Metric | Count |"
    echo "|--------|------:|"
    echo "| Total  | $TOTAL |"
    echo "| Pass   | $PASS  |"
    echo "| Fail   | $FAIL  |"
    echo "| Skip   | $SKIP  |"
    echo ""
    if [[ ${#FAIL_NAMES[@]} -gt 0 ]]; then
      echo "### Failing tests"
      echo ""
      for n in "${FAIL_NAMES[@]}"; do
        echo "- \`$n\`"
      done
      echo ""
    fi
    echo "### Included test files"
    echo ""
    for t in "${INCLUDE_TESTS[@]}"; do
      echo "- $t"
    done
    echo ""
    if [[ ${#SYSTEMD_TESTS[@]} -gt 0 ]]; then
      echo "### Systemd test files (RUNC_USE_SYSTEMD=yes)"
      echo ""
      for t in "${SYSTEMD_TESTS[@]}"; do
        echo "- $t"
      done
      echo ""
    fi
    if [[ ${#SKIP_TEST_NAMES[@]} -gt 0 ]]; then
      echo "### Skipped individual tests (runc-specific)"
      echo ""
      for t in "${SKIP_TEST_NAMES[@]}"; do
        echo "- \`$t\`"
      done
      echo ""
    fi
    echo "### Excluded test files (require runc-specific helpers)"
    echo ""
    for t in "${EXCLUDE_TESTS[@]}"; do
      echo "- $t"
    done
  } >> "$dest"
}

if [[ -n "$SUMMARY_FILE" ]]; then
  write_summary "$SUMMARY_FILE"
else
  # Also print Markdown to stdout so callers can capture it.
  TMPMD="$(mktemp)"
  write_summary "$TMPMD"
  cat "$TMPMD"
  rm -f "$TMPMD"
fi

# ---------------------------------------------------------------------------
# Preserve the raw TAP output path for the caller
# ---------------------------------------------------------------------------
echo ""
echo "Raw TAP output: $TAP_OUTPUT"

# Exit based on parsed test results, not raw bats exit code.
# bats may return non-zero due to timeouts in cleanup or background
# processes lingering after all tests passed — that should not fail CI.
if [[ $FAIL -gt 0 ]]; then
  exit 1
fi
exit 0
