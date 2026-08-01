#!/usr/bin/env bash
# Exec verification script: runs `kontainer-runtime exec` against a running
# container and prints labeled facts about the exec'd process for
# check-expectations.sh to assert on.
#
# Usage: sudo ./verify-exec.sh <container-id> <runtime-binary>
# Must run while the container is alive (verify-from-container.sh's trailing
# sleep window).
#
# Container commands are piped to `sh` over stdin rather than `sh -c '...'`
# so no dash-prefixed argument reaches the CLI parser.

set -uo pipefail

CONTAINER_ID="${1:?usage: $0 <container-id> <runtime-binary>}"
BIN="${2:?usage: $0 <container-id> <runtime-binary>}"

echo "================================"
echo "EXEC VERIFICATION"
echo "================================"
echo "Container ID: $CONTAINER_ID"
echo "Runtime: $BIN"
echo ""

# Locate the state file the same way verify-from-host.sh does, and derive the
# --root directory from it ($ROOT/$CONTAINER_ID/state.json).
STATE_LOCATIONS=(
    "/run/kontainer/$CONTAINER_ID/state.json"
    "/run/containerd/runc/default/$CONTAINER_ID/state.json"
    "/run/containerd/io.containerd.runtime.v2.task/default/$CONTAINER_ID/state.json"
)

# Accept a candidate only if it is kontainer-runtime's own format (a runc
# libcontainer state.json can sit at the same containerd path and must not
# be used to derive --root).
STATE_FILE=""
for CANDIDATE in "${STATE_LOCATIONS[@]}"; do
    if sudo test -f "$CANDIDATE" 2>/dev/null; then
        PID_CANDIDATE="$(sudo jq -r 'select(has("ociVersion")) | .pid' "$CANDIDATE" 2>/dev/null)"
        if [ -n "$PID_CANDIDATE" ] && [ "$PID_CANDIDATE" != "null" ]; then
            STATE_FILE="$CANDIDATE"
            break
        fi
    fi
done

if [ -z "$STATE_FILE" ]; then
    echo "ERROR: kontainer-format state.json not found for $CONTAINER_ID"
    exit 1
fi

ROOT="$(dirname "$(dirname "$STATE_FILE")")"
CONTAINER_PID="$(sudo jq -r '.pid' "$STATE_FILE")"
echo "State file: $STATE_FILE"
echo "Root: $ROOT"
echo "Container init PID (host view): $CONTAINER_PID"
echo ""

INIT_CAPEFF="$(grep '^CapEff:' "/proc/$CONTAINER_PID/status" | awk '{print $2}')"
echo "Init CapEff (host view): $INIT_CAPEFF"
echo ""

# Canary: this host env var must NOT leak into the exec'd process.
export KONTAINER_HOST_CANARY=1

echo "=== 1. EXEC'D PROCESS IDENTITY / SANDBOX ==="
EXEC_OUT="$("$BIN" --root "$ROOT" exec "$CONTAINER_ID" sh <<'EOS'
echo "EXEC-UID: $(id -u)"
echo "EXEC-GID: $(id -g)"
grep -E '^(Uid|Gid|CapEff|CapBnd|NoNewPrivs|Seccomp):' /proc/self/status | sed 's/^/EXEC-STATUS /'
echo "EXEC-CWD: $(pwd)"
if env | grep -q KONTAINER_HOST_CANARY; then
    echo "EXEC-CANARY: leaked"
else
    echo "EXEC-CANARY: absent"
fi
env | sed 's/^/EXEC-ENVVAR /'
echo "EXEC-CGROUP: $(cat /proc/self/cgroup)"
EOS
)"
RC=$?
echo "$EXEC_OUT"
echo "exec exit code: $RC"
echo ""

EXEC_CAPEFF="$(echo "$EXEC_OUT" | sed -n 's/^EXEC-STATUS CapEff:[[:space:]]*//p')"
echo "Exec'd process CapEff: $EXEC_CAPEFF"
if [ -n "$EXEC_CAPEFF" ] && [ "$EXEC_CAPEFF" = "$INIT_CAPEFF" ]; then
    echo "EXEC-CAPEFF-MATCH: YES"
else
    echo "EXEC-CAPEFF-MATCH: NO (init=$INIT_CAPEFF exec=$EXEC_CAPEFF)"
fi
echo ""

echo "=== 2. CGROUP MEMBERSHIP (HOST VIEW) ==="
CGROUP_PATH="$(grep '^0::' "/proc/$CONTAINER_PID/cgroup" | cut -d: -f3)"
echo "Container cgroup path: $CGROUP_PATH"
if [ -z "$CGROUP_PATH" ] || [ "$CGROUP_PATH" = "/" ]; then
    # Without a real container cgroup the count below would tally the root
    # cgroup and pass vacuously.
    echo "EXEC-CGROUP-PROCS-OK: NO (container cgroup path not found)"
else
    "$BIN" --root "$ROOT" exec "$CONTAINER_ID" sleep 3 &
    EXEC_BG_PID=$!
    sleep 1
    PROCS_COUNT="$(wc -l < "/sys/fs/cgroup$CGROUP_PATH/cgroup.procs")"
    echo "cgroup.procs entries during exec: $PROCS_COUNT"
    if [ "$PROCS_COUNT" -ge 2 ]; then
        echo "EXEC-CGROUP-PROCS-OK: YES"
    else
        echo "EXEC-CGROUP-PROCS-OK: NO"
    fi
    wait $EXEC_BG_PID
fi
echo ""

echo "=== 3. EXIT CODE PROPAGATION ==="
echo "exit 42" | "$BIN" --root "$ROOT" exec "$CONTAINER_ID" sh
echo "EXEC-EXITCODE: $?"
echo ""

echo "=== 4. NONEXISTENT CONTAINER IS REJECTED ==="
if "$BIN" --root "$ROOT" exec no-such-container true 2>/dev/null; then
    echo "EXEC-MISSING-CONTAINER: accepted"
else
    echo "EXEC-MISSING-CONTAINER: rejected"
fi
echo ""

echo "================================"
echo "EXEC VERIFICATION COMPLETED"
echo "================================"
