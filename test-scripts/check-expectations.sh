#!/usr/bin/env bash
# Verify that critical container security and isolation features are configured
# as expected by parsing the output of verify-from-container.sh or verify-from-host.sh.
#
# Usage: check-expectations.sh <log-file>
# Auto-detects whether the log is container-side or host-side from a marker line.
# Exits 0 if every assertion passes, 1 if any fail, 2 on usage error.

set -uo pipefail

LOG="${1:?usage: $0 <log-file>}"

if [ ! -f "$LOG" ]; then
    echo "ERROR: log file not found: $LOG" >&2
    exit 2
fi

FAIL=0

assert_match() {
    local name="$1"
    local pattern="$2"
    if grep -qE "$pattern" "$LOG"; then
        echo "  PASS  $name"
    else
        echo "  FAIL  $name"
        echo "        pattern not found: $pattern"
        FAIL=1
    fi
}

assert_count_at_least() {
    local name="$1"
    local pattern="$2"
    local min="$3"
    local got
    got=$(grep -cE "$pattern" "$LOG" || true)
    if [ "$got" -ge "$min" ]; then
        echo "  PASS  $name ($got >= $min)"
    else
        echo "  FAIL  $name ($got < $min)"
        echo "        pattern: $pattern"
        FAIL=1
    fi
}

if grep -q "KONTAINER-RUNTIME COMPREHENSIVE TEST" "$LOG"; then
    echo "Checking container-side log: $LOG"
    assert_match         "PID 1 inside container"        'Container sees itself as PID: 1'
    assert_match         "UTS namespace hostname"        'my-test-container'
    assert_match         "uid_map 0 -> 100000 (size 65536)" '^[[:space:]]*0[[:space:]]+100000[[:space:]]+65536[[:space:]]*$'
    assert_match         "seccomp filter mode active"    '^Seccomp:[[:space:]]+2'
    assert_match         "no_new_privs is set"           '^NoNewPrivs:[[:space:]]+1'
    assert_match         "old root not visible (pivot_root)" 'OK: Old root not visible'
    assert_match         "rootfs is readonly"            'OK: Root is readonly'
    assert_match         "memory.max = 134217728 (128MB)" '^134217728$'
    assert_match         "cpu.max = 50000 100000"        '^50000 100000$'
elif grep -q "HOST-SIDE CONTAINER VERIFICATION" "$LOG"; then
    echo "Checking host-side log: $LOG"
    # PID and user namespaces should differ from host (verify script prints "Different? YES" twice)
    assert_count_at_least "PID and user namespaces differ from host" 'Different\? YES' 2
    assert_match         "uid_map 0 -> 100000 (size 65536)" '^[[:space:]]*0[[:space:]]+100000[[:space:]]+65536[[:space:]]*$'
    assert_match         "seccomp filter mode active"    '^Seccomp:[[:space:]]+2'
    assert_match         "no_new_privs is set"           '^NoNewPrivs:[[:space:]]+1'
    assert_match         "memory.max = 134217728 (128MB)" '^134217728$'
    assert_match         "cpu.max = 50000 100000"        '^50000 100000$'
else
    echo "ERROR: $LOG does not look like a verify-from-container.sh or verify-from-host.sh output" >&2
    exit 2
fi

if [ "$FAIL" -ne 0 ]; then
    echo ""
    echo "FAILED: one or more expectations not met."
    exit 1
fi

echo ""
echo "OK: all expectations met."
exit 0
