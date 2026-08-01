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
elif grep -q "EXEC VERIFICATION" "$LOG"; then
    echo "Checking exec-side log: $LOG"
    assert_match         "exec'd process runs as spec uid 1000"  '^EXEC-UID: 1000$'
    assert_match         "exec'd process runs as spec gid 1000"  '^EXEC-GID: 1000$'
    assert_match         "seccomp filter mode active"            '^EXEC-STATUS Seccomp:[[:space:]]+2'
    assert_match         "no_new_privs is set"                   '^EXEC-STATUS NoNewPrivs:[[:space:]]+1'
    assert_match         "capabilities match init process"       '^EXEC-CAPEFF-MATCH: YES$'
    assert_match         "host env did not leak"                 '^EXEC-CANARY: absent$'
    assert_match         "spec env is present"                   '^EXEC-ENVVAR PATH='
    assert_match         "cwd is the spec cwd"                   '^EXEC-CWD: /$'
    # ctr run's default spec has no cgroup namespace, so the path inside the
    # container is host-relative; asserting the concrete container cgroup
    # path also proves membership directly.
    assert_match         "runs inside the container cgroup"      '^EXEC-CGROUP: 0::/default/test-verify$'
    assert_match         "joined the container cgroup"           '^EXEC-CGROUP-PROCS-OK: YES$'
    assert_match         "exit code propagates"                  '^EXEC-EXITCODE: 42$'
    assert_match         "nonexistent container rejected"        '^EXEC-MISSING-CONTAINER: rejected$'
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
