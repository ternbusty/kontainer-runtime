#!/bin/bash
# Host-side container verification script
#
# Usage: sudo ./verify-from-host.sh <container-id>
# Example: sudo ./verify-from-host.sh test-alpine-comprehensive
#
# Note: This script requires sudo privileges to read container process information

CONTAINER_ID="${1:-test-alpine-comprehensive}"

echo "================================"
echo "HOST-SIDE CONTAINER VERIFICATION"
echo "================================"
echo "Container ID: $CONTAINER_ID"
echo "Timestamp: $(date)"
echo ""

# Find container's init process (PID 1 in container namespace)
echo "=== 1. CONTAINER PROCESS DISCOVERY ==="

# Try to find PID from state.json files
STATE_LOCATIONS=(
    "/run/kontainer/$CONTAINER_ID/state.json"
    "/run/containerd/runc/default/$CONTAINER_ID/state.json"
    "/run/containerd/io.containerd.runtime.v2.task/default/$CONTAINER_ID/state.json"
)

CONTAINER_PID=""
STATE_FILE_FOUND=""
for STATE_FILE in "${STATE_LOCATIONS[@]}"; do
    if sudo test -f "$STATE_FILE" 2>/dev/null; then
        CONTAINER_PID=$(sudo cat "$STATE_FILE" 2>/dev/null | jq -r '.pid' 2>/dev/null)
        if [ -n "$CONTAINER_PID" ] && [ "$CONTAINER_PID" != "null" ]; then
            STATE_FILE_FOUND="$STATE_FILE"
            echo "Found state file: $STATE_FILE"
            break
        fi
    fi
done

# Fallback: try ctr tasks ls
if [ -z "$CONTAINER_PID" ] || [ "$CONTAINER_PID" = "null" ]; then
    echo "State file not found, trying 'ctr tasks ls'..."
    CONTAINER_PID=$(sudo ctr tasks ls 2>/dev/null | grep "$CONTAINER_ID" | awk '{print $2}')
fi

if [ -z "$CONTAINER_PID" ] || [ "$CONTAINER_PID" = "-" ]; then
    echo "ERROR: Could not find container PID"
    echo "Tried state.json locations:"
    for loc in "${STATE_LOCATIONS[@]}"; do
        echo "  - $loc"
    done
    echo "Also tried 'ctr tasks ls'"
    exit 1
fi

echo "Container init process PID (host view): $CONTAINER_PID"
echo "Process command: $(ps -p $CONTAINER_PID -o comm=)"
echo "Process cmdline: $(cat /proc/$CONTAINER_PID/cmdline | tr '\0' ' ' | head -c 100)..."
echo ""

# Namespace information
echo "=== 2. NAMESPACE VERIFICATION ==="
echo "Container namespaces (from host):"
sudo ls -l /proc/$CONTAINER_PID/ns/
echo ""
echo "Comparison with host namespaces:"
echo "Host PID namespace:      $(readlink /proc/self/ns/pid)"
echo "Container PID namespace: $(sudo readlink /proc/$CONTAINER_PID/ns/pid)"
echo "Different? $([ "$(readlink /proc/self/ns/pid)" != "$(sudo readlink /proc/$CONTAINER_PID/ns/pid)" ] && echo 'YES ✓' || echo 'NO ✗')"
echo ""
echo "Host User namespace:      $(readlink /proc/self/ns/user)"
echo "Container User namespace: $(sudo readlink /proc/$CONTAINER_PID/ns/user)"
echo "Different? $([ "$(readlink /proc/self/ns/user)" != "$(sudo readlink /proc/$CONTAINER_PID/ns/user)" ] && echo 'YES ✓' || echo 'NO ✗')"
echo ""

# UID/GID mapping
echo "=== 3. UID/GID MAPPING ==="
echo "UID mapping (host view):"
cat /proc/$CONTAINER_PID/uid_map
echo "Expected: 0     100000      65536"
echo ""
echo "GID mapping (host view):"
cat /proc/$CONTAINER_PID/gid_map
echo "Expected: 0     100000      65536"
echo ""
echo "Setgroups status:"
cat /proc/$CONTAINER_PID/setgroups
echo ""

# Process credentials
echo "=== 4. PROCESS CREDENTIALS ==="
echo "Container process status (credentials):"
grep -E "^(Uid|Gid|Groups):" /proc/$CONTAINER_PID/status
echo "Expected: Uid should show 101000 (host UID) for container UID 1000"
echo "          Gid should show 101000 (host GID) for container GID 1000"
echo ""

# Cgroup membership
echo "=== 5. CGROUP MEMBERSHIP ==="
echo "Container process cgroup:"
cat /proc/$CONTAINER_PID/cgroup
CGROUP_PATH=$(cat /proc/$CONTAINER_PID/cgroup | grep "^0::" | cut -d: -f3)
echo ""
echo "Extracted cgroup path: $CGROUP_PATH"
echo ""

# Cgroup limits (host filesystem)
echo "=== 6. CGROUP LIMITS (HOST FILESYSTEM) ==="
if [ -n "$CGROUP_PATH" ]; then
    CGROUP_FULL_PATH="/sys/fs/cgroup$CGROUP_PATH"
    echo "Full cgroup path: $CGROUP_FULL_PATH"
    echo ""

    if [ -d "$CGROUP_FULL_PATH" ]; then
        echo "Memory limit (memory.max):"
        cat "$CGROUP_FULL_PATH/memory.max" 2>/dev/null || echo "Not available"
        echo "Expected: 134217728 (128MB)"
        echo ""

        echo "Memory current usage (memory.current):"
        cat "$CGROUP_FULL_PATH/memory.current" 2>/dev/null || echo "Not available"
        echo ""

        echo "CPU quota (cpu.max):"
        cat "$CGROUP_FULL_PATH/cpu.max" 2>/dev/null || echo "Not available"
        echo "Expected: 50000 100000 (50% of 1 CPU)"
        echo ""

        echo "CPU weight (cpu.weight):"
        cat "$CGROUP_FULL_PATH/cpu.weight" 2>/dev/null || echo "Not available"
        echo ""

        echo "PIDs limit (pids.max):"
        cat "$CGROUP_FULL_PATH/pids.max" 2>/dev/null || echo "Not available"
        echo ""

        echo "PIDs current (pids.current):"
        cat "$CGROUP_FULL_PATH/pids.current" 2>/dev/null || echo "Not available"
        echo ""
    else
        echo "ERROR: Cgroup directory not found at $CGROUP_FULL_PATH"
    fi
else
    echo "ERROR: Could not extract cgroup path"
fi

# Capabilities
echo "=== 7. CAPABILITIES ==="
echo "Container process capabilities:"
grep -E "^Cap" /proc/$CONTAINER_PID/status
echo ""
echo "Capability bounding set (CapBnd):"
CAPBND=$(grep "^CapBnd:" /proc/$CONTAINER_PID/status | awk '{print $2}')
echo "Raw value: $CAPBND"
if command -v capsh &> /dev/null; then
    echo "Decoded: $(capsh --decode=$CAPBND 2>/dev/null)"
else
    echo "capsh not available for decoding"
fi
echo ""

# Seccomp
echo "=== 8. SECCOMP STATUS ==="
grep -E "^Seccomp" /proc/$CONTAINER_PID/status
echo "Expected: Seccomp: 2 (filter mode)"
echo "          Seccomp_filters: 1 or more"
echo ""

# NoNewPrivs
echo "=== 9. NO_NEW_PRIVS ==="
grep -E "^NoNewPrivs:" /proc/$CONTAINER_PID/status
echo "Expected: NoNewPrivs: 1"
echo ""

# Mount information
echo "=== 10. MOUNT NAMESPACE CONTENT ==="
echo "Container's mount points (first 15):"
head -15 /proc/$CONTAINER_PID/mountinfo
echo ""

# kontainer-runtime state file
echo "=== 11. RUNTIME STATE FILE ==="
if [ -n "$STATE_FILE_FOUND" ]; then
    echo "State file: $STATE_FILE_FOUND"
    echo "Content:"
    sudo cat "$STATE_FILE_FOUND" | jq . 2>/dev/null || sudo cat "$STATE_FILE_FOUND"
else
    echo "State file not found in standard locations"
    echo "Checking /run/kontainer/..."
    find /run/kontainer -name "state.json" 2>/dev/null | head -5
fi
echo ""

# IPC namespace verification
echo "=== 12. IPC NAMESPACE VERIFICATION ==="
echo "Host IPC resources (for comparison with container):"
echo ""
echo "Message Queues:"
ipcs -q 2>/dev/null || echo "No message queues or ipcs not available"
echo ""
echo "Shared Memory Segments:"
ipcs -m 2>/dev/null || echo "No shared memory segments or ipcs not available"
echo ""
echo "Semaphore Arrays:"
ipcs -s 2>/dev/null || echo "No semaphore arrays or ipcs not available"
echo ""
echo "All IPC resources summary:"
ipcs -a 2>/dev/null || echo "ipcs not available on host"
echo ""
echo "Expected: Container's ipcs output (from container test) should be DIFFERENT"
echo "          If they show the same resources, IPC namespace is NOT isolated"
echo "          Container may create its own IPC resources invisible to host"
echo ""

echo "================================"
echo "VERIFICATION COMPLETED"
echo "================================"
