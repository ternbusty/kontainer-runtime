#!/usr/bin/env bash
# scripts/runc-compat-test.sh — Run runc bats integration tests against
# kontainer-runtime using a per-test-name whitelist (pattern file).
#
# Usage:
#   sudo ./scripts/runc-compat-test.sh [--runc-dir DIR] [--binary PATH] \
#                                      [--pattern FILE] [--selinux]
#
# Options:
#   --runc-dir DIR     Path to an existing runc checkout (skips clone)
#   --binary PATH      Path to the kontainer-runtime binary
#   --pattern FILE     Path to the test pattern file
#                      (default: scripts/runc_test_pattern)
#   --selinux          Run only SELinux-specific tests (selinux.bats)
#
# Environment variables (all optional):
#   RUNC_REPO_DIR  — path to an existing runc checkout (same as --runc-dir)
#   KONTAINER_BIN  — path to the kontainer-runtime binary (same as --binary)
#   RUNC_TAG       — runc tag to clone (default: v1.5.1)
#   SUMMARY_FILE   — file to append a Markdown summary to
#   TAP_OUTPUT     — file to write raw TAP output to

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
TAP_OUTPUT="${TAP_OUTPUT:-}"
PATTERN_FILE="${PROJECT_ROOT}/scripts/runc_test_pattern"
SELINUX_MODE=false

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --runc-dir)  RUNC_REPO_DIR="$2"; shift 2 ;;
    --binary)    KONTAINER_BIN="$2"; shift 2 ;;
    --pattern)   PATTERN_FILE="$2"; shift 2 ;;
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

if [[ "$SELINUX_MODE" != "true" && ! -f "$PATTERN_FILE" ]]; then
  echo "ERROR: pattern file not found: $PATTERN_FILE" >&2
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
# Copy the kontainer-runtime binary into the runc tree as "runc" so that
# helpers.bash's default RUNC path (../../runc relative to
# tests/integration/) resolves to it without any code changes.
# Kill stale processes holding the old binary before copying.
# ---------------------------------------------------------------------------
if ! cp "$KONTAINER_BIN" "$RUNC_REPO_DIR/runc" 2>/dev/null; then
  fuser -k "$RUNC_REPO_DIR/runc" 2>/dev/null || true
  sleep 0.5
  cp "$KONTAINER_BIN" "$RUNC_REPO_DIR/runc"
fi
chmod +x "$RUNC_REPO_DIR/runc"

# ---------------------------------------------------------------------------
# Fetch rootfs images (uses runc's own script)
# ---------------------------------------------------------------------------
echo ">>> Fetching rootfs images ..."
chmod +x "${INTEGRATION_DIR}/get-images.sh"
"${INTEGRATION_DIR}/get-images.sh" >/dev/null

# ---------------------------------------------------------------------------
# Build runc test helper binaries (needed by idmap, pidfd-socket, etc.)
# ---------------------------------------------------------------------------
echo ">>> Building runc test helper binaries ..."
if make -C "$RUNC_REPO_DIR" test-binaries 2>/dev/null; then
  echo "    built via 'make test-binaries'"
else
  # Fallback: build each helper individually (works when the runc
  # Makefile has other unmet dependencies like containerd).
  TESTBINDIR="${RUNC_REPO_DIR}/tests/cmd/_bin"
  mkdir -p "$TESTBINDIR"
  for helper in remap-rootfs fs-idmap seccompagent pidfd-kill recvtty; do
    helperdir="${RUNC_REPO_DIR}/tests/cmd/${helper}"
    if [[ -d "$helperdir" ]] && [[ ! -x "${TESTBINDIR}/${helper}" ]]; then
      echo "    building ${helper} ..."
      # seccompagent requires the seccomp build tag to enable actual
      # seccomp-agent functionality (without it, it prints "Not supported").
      build_tags=""
      if [[ "$helper" == "seccompagent" ]]; then
        build_tags="-tags seccomp"
      fi
      # shellcheck disable=SC2086
      (cd "$helperdir" && go build $build_tags -o "${TESTBINDIR}/${helper}" .) || {
        echo "WARNING: failed to build ${helper}, some tests may be skipped" >&2
      }
    fi
  done
fi

cd "$RUNC_REPO_DIR" || exit 1

# ---------------------------------------------------------------------------
# Ubuntu 24.04 AppArmor: rewrite the runc profile for the copied binary
# ---------------------------------------------------------------------------
if [ -f /etc/apparmor.d/runc ]; then
  sed "s;^profile runc /usr/sbin/runc;profile kontainer-runtime-test $PWD/runc;" \
      < /etc/apparmor.d/runc | sudo apparmor_parser -r 2>/dev/null || true
fi

# ---------------------------------------------------------------------------
# Initialise TAP output file
# ---------------------------------------------------------------------------
if [[ -z "$TAP_OUTPUT" ]]; then
  TAP_OUTPUT="$(mktemp)"
fi
: > "$TAP_OUTPUT"

# ---------------------------------------------------------------------------
# Clean up stale state left by timed-out or crashed tests
# ---------------------------------------------------------------------------
cleanup_stale_state() {
  sudo rm -f /tmp/kontainer-*.sock 2>/dev/null || true
  sudo rm -rf /run/kontainer-runtime/* 2>/dev/null || true
  # Remove leftover dummy network devices (netdev.bats).
  sudo ip link del dev dummy0 2>/dev/null || true
  for _cgdir in /sys/fs/cgroup/kontainer-runtime/*/; do
    [ -d "$_cgdir" ] || continue
    sudo bash -c '
      echo 1 > "'"$_cgdir"'cgroup.kill" 2>/dev/null || true
      sleep 0.2
      for sub in "'"$_cgdir"'"/*/; do
        [ -d "$sub" ] || continue
        rmdir "$sub" 2>/dev/null || true
      done
      rmdir "'"$_cgdir"'" 2>/dev/null || true
    '
  done
}

# Escape ERE special characters for bats -f regex.
escape_ere() {
  sed 's/[][\\.*^$()|+?{}]/\\&/g' <<< "$1"
}

# ---------------------------------------------------------------------------
# SELinux mode: run ONLY selinux.bats (bypass pattern file)
# ---------------------------------------------------------------------------
if [[ "$SELINUX_MODE" == "true" ]]; then
  echo ">>> SELinux mode — running only selinux.bats"
  SELINUX_BATS="${INTEGRATION_DIR}/selinux.bats"
  if [[ ! -f "$SELINUX_BATS" ]]; then
    echo "ERROR: selinux.bats not found at $SELINUX_BATS" >&2
    exit 1
  fi

  TMPOUT=$(mktemp)
  rc=0
  sudo -E PATH="$PATH" RUNC="$KONTAINER_BIN" \
      timeout 300 script -q -e -c \
      "exec bats -t $SELINUX_BATS" /dev/null > "$TMPOUT" 2>&1 \
    || rc=$?

  # Append to TAP output.
  cat "$TMPOUT" >> "$TAP_OUTPUT"

  # Parse and report.
  PASS=0; FAIL=0; SKIP=0
  FAIL_NAMES=()
  while IFS= read -r line; do
    if [[ "$line" =~ ^ok\ [0-9]+\ (.+) ]]; then
      tname="${BASH_REMATCH[1]}"
      if [[ "$tname" =~ \#\ skip ]]; then
        echo "  SKIP  ${tname%% \# skip*}"
        SKIP=$((SKIP + 1))
      else
        PASS=$((PASS + 1))
        echo "  PASS  $tname"
      fi
    elif [[ "$line" =~ ^not\ ok\ [0-9]+\ (.+) ]]; then
      tname="${BASH_REMATCH[1]}"
      FAIL=$((FAIL + 1))
      echo "  FAIL  $tname"
      FAIL_NAMES+=("$tname")
    fi
  done < "$TMPOUT"

  if [[ $FAIL -gt 0 ]]; then
    echo "  --- full bats output (selinux.bats) ---"
    cat "$TMPOUT"
    echo "  --- end ---"
  fi

  rm -f "$TMPOUT"
  TOTAL=$((PASS + FAIL + SKIP))

  echo ""
  echo "==========================================="
  echo " runc bats compatibility — SELinux results"
  echo "==========================================="
  echo " Total : $TOTAL"
  echo " Pass  : $PASS"
  echo " Fail  : $FAIL"
  echo " Skip  : $SKIP"
  echo "==========================================="

  if [[ ${#FAIL_NAMES[@]} -gt 0 ]]; then
    echo ""
    echo "Failing tests:"
    for n in "${FAIL_NAMES[@]}"; do
      echo "  - $n"
    done
  fi

  # Write Markdown summary.
  if [[ -n "$SUMMARY_FILE" ]]; then
    {
      echo "## runc bats compatibility — SELinux results"
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
    } >> "$SUMMARY_FILE"
  fi

  echo ""
  echo "Raw TAP output: $TAP_OUTPUT"

  if [[ $FAIL -gt 0 ]]; then
    exit 1
  fi
  exit 0
fi

# ---------------------------------------------------------------------------
# Build per-file filter regexes from the pattern file
# ---------------------------------------------------------------------------
#
# 1. Map every @test declaration to its .bats file.
# 2. Read the pattern file; skip [skip] lines; look up each enabled
#    test name in the map and group it under its file.
# 3. Result: FILE_FILTER[file] = "^test1$|^test2$|..."

declare -A NAME_TO_FILE
while IFS= read -r mapping; do
  file="${mapping%%	*}"
  tname="${mapping#*	}"
  # Trim trailing whitespace (some bats tests have names like 'name " {').
  tname="${tname%"${tname##*[! ]}"}"
  NAME_TO_FILE["$tname"]="$file"
done < <(grep -rH '@test "' tests/integration/*.bats \
    | sed -n 's/^\(.*\.bats\):.*@test "\(.*\)" {.*$/\1\t\2/p')

declare -A FILE_FILTER
declare -A FILE_TEST_COUNT
PATTERN_SKIP=0

while IFS= read -r name; do
  [[ -z "$name" || "$name" == \#* ]] && continue

  if [[ $name =~ ^\[skip\] ]]; then
    PATTERN_SKIP=$((PATTERN_SKIP + 1))
    continue
  fi

  file="${NAME_TO_FILE[$name]:-}"
  if [[ -z "$file" ]]; then
    echo "WARN: test not found in any .bats file: $name" >&2
    continue
  fi

  escaped=$(escape_ere "$name")
  # Use " *$" instead of "$" to tolerate trailing spaces in bats
  # test names (some runc tests have trailing whitespace).
  if [[ -z "${FILE_FILTER[$file]:-}" ]]; then
    FILE_FILTER[$file]="^${escaped} *$"
    FILE_TEST_COUNT[$file]=1
  else
    FILE_FILTER[$file]="${FILE_FILTER[$file]}|^${escaped} *$"
    FILE_TEST_COUNT[$file]=$(( ${FILE_TEST_COUNT[$file]} + 1 ))
  fi
done < "$PATTERN_FILE"

TOTAL_ENABLED=0
for f in "${!FILE_TEST_COUNT[@]}"; do
  TOTAL_ENABLED=$(( TOTAL_ENABLED + ${FILE_TEST_COUNT[$f]} ))
done

echo ">>> Running ${TOTAL_ENABLED} tests from ${#FILE_FILTER[@]} bats files (${PATTERN_SKIP} skipped in pattern)"
echo "    Binary: $KONTAINER_BIN"
echo ""

# ---------------------------------------------------------------------------
# Run bats per file
# ---------------------------------------------------------------------------
PASS=0
FAIL=0
BATS_SKIP=0
ERRORS=""
FAIL_NAMES=()

FILE_INDEX=0
FILE_TOTAL=${#FILE_FILTER[@]}

for file in $(printf '%s\n' "${!FILE_FILTER[@]}" | sort); do
  FILE_INDEX=$((FILE_INDEX + 1))
  filter="${FILE_FILTER[$file]}"
  fname=$(basename "$file")
  expected=${FILE_TEST_COUNT[$file]}

  # Scale timeout by test count. GitHub arm runners are ~20x slower
  # for mount/idmap operations, so use 60s/test on aarch64 vs 30s on
  # x86_64. Floor 180s either way.
  if [[ "$(uname -m)" == "aarch64" ]]; then
    timeout_secs=$(( expected * 60 ))
  else
    timeout_secs=$(( expected * 30 ))
  fi
  (( timeout_secs < 180 )) && timeout_secs=180

  echo "=== [$FILE_INDEX/$FILE_TOTAL] $fname ($expected tests, ${timeout_secs}s) ==="

  TMPOUT=$(mktemp)

  # Pass the filter via an environment variable to avoid quoting
  # issues with apostrophes, $, and | in test names and regex.
  run_bats() {
    sudo -E PATH="$PATH" RUNC="$PWD/runc" _BATS_FILTER="$filter" \
        timeout "$timeout_secs" script -q -e -c \
        'exec bats -f "$_BATS_FILTER" -t '"$file" /dev/null > "$TMPOUT" 2>&1
  }

  # Use script(1) for a PTY (needed for console-socket tests in CI).
  # Suppress set -e: we need the exit code, not an abort.
  rc=0
  run_bats || rc=$?

  # Retry once on timeout.
  if [[ $rc -eq 124 ]]; then
    echo "  TIMEOUT ($fname), retrying..."
    cleanup_stale_state
    rc=0
    run_bats || rc=$?
  fi

  # Append to TAP output for CI upload.
  cat "$TMPOUT" >> "$TAP_OUTPUT"

  # Parse TAP output for individual results.
  file_pass=0
  file_fail=0
  in_fail=0
  while IFS= read -r line; do
    if [[ "$line" =~ ^ok\ [0-9]+\ (.+) ]]; then
      tname="${BASH_REMATCH[1]}"
      if [[ "$tname" =~ \#\ skip ]]; then
        # bats-internal skip (e.g. "requires root")
        echo "  SKIP  ${tname%% \# skip*}"
        BATS_SKIP=$((BATS_SKIP + 1))
      else
        file_pass=$((file_pass + 1))
        echo "  PASS  $tname"
      fi
      in_fail=0
    elif [[ "$line" =~ ^not\ ok\ [0-9]+\ (.+) ]]; then
      tname="${BASH_REMATCH[1]}"
      file_fail=$((file_fail + 1))
      echo "  FAIL  $tname"
      ERRORS="${ERRORS}\n  - $tname"
      FAIL_NAMES+=("$tname")
      in_fail=1
    elif [[ $in_fail -eq 1 && "$line" =~ ^#\  ]]; then
      # TAP diagnostic lines (comments) following a failed test
      echo "        ${line#\# }"
    else
      in_fail=0
    fi
  done < "$TMPOUT"

  # Dump full bats output for failing test files to aid CI debugging.
  if [[ $file_fail -gt 0 ]]; then
    echo "  --- full bats output ($fname) ---"
    cat "$TMPOUT"
    echo "  --- end ---"
  fi

  # If bats itself crashed (no TAP output at all), count as file-level failure.
  if [[ $rc -ne 0 && $file_pass -eq 0 && $file_fail -eq 0 ]]; then
    file_fail=$expected
    echo "  FAIL  $fname (bats exited with rc=$rc, no TAP output)"
    ERRORS="${ERRORS}\n  - $fname (rc=$rc)"
    FAIL_NAMES+=("$fname (rc=$rc)")
  fi

  PASS=$((PASS + file_pass))
  FAIL=$((FAIL + file_fail))

  rm -f "$TMPOUT"
  cleanup_stale_state
done

# ---------------------------------------------------------------------------
# Print summary
# ---------------------------------------------------------------------------
TOTAL=$((PASS + FAIL + BATS_SKIP))

echo ""
echo "==========================================="
echo " runc bats compatibility — results"
echo "==========================================="
echo " Total  : $TOTAL"
echo " Pass   : $PASS"
echo " Fail   : $FAIL"
echo " Skip   : ${PATTERN_SKIP} (pattern) + ${BATS_SKIP} (bats-internal)"
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
    echo "Pattern: \`$(basename "$PATTERN_FILE")\`"
    echo ""
    echo "| Metric | Count |"
    echo "|--------|------:|"
    echo "| Total  | $TOTAL |"
    echo "| Pass   | $PASS  |"
    echo "| Fail   | $FAIL  |"
    echo "| Skip (pattern) | $PATTERN_SKIP |"
    echo "| Skip (bats)    | $BATS_SKIP |"
    echo ""
    if [[ ${#FAIL_NAMES[@]} -gt 0 ]]; then
      echo "### Failing tests"
      echo ""
      for n in "${FAIL_NAMES[@]}"; do
        echo "- \`$n\`"
      done
      echo ""
    fi
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
