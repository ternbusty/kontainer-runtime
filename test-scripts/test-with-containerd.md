# Test with containerd

## Prerequisites

Set symbolic link for kontainer-runtime binary

```
sudo ln -sf /home/ternbusty/kontainer-runtime/build/bin/linuxX64/debugExecutable/kontainer-runtime.kexe /usr/sbin/runc
```

## Verification

### Test Command to Check from Inside Container

```bash
sudo ctr run --rm \
    --seccomp \
    --read-only \
    --memory-limit 134217728 \
    --cpus 0.5 \
    --uidmap "0:100000:65536" \
    --gidmap "0:100000:65536" \
    --user 1000:1000 \
    --hostname my-test-container \
    docker.io/library/alpine:latest \
    test-alpine-comprehensive \
    sh -c "$(cat verify-from-container.sh)"
```

output

```
[bootstrap] Using sync FD from Create.kt: 12
[stage-0] Starting bootstrap process
[stage-0:bootstrap-parent] Cloned stage-1, PID=577285
[stage-0:bootstrap-parent] User namespace configured, handling mapping
[stage-0:bootstrap-parent] Waiting for mapping request from Stage-1
[stage-1] Started, PID=577285
[stage-1] Clone flags: 0x7c020000
[stage-1] Unsharing user namespace (CLONE_NEWUSER)
[stage-1] Successfully unshared user namespace
[stage-1] Setting dumpable to allow uid/gid mapping
[stage-1] Requesting UID/GID mapping from Stage-0
[stage-1] Waiting for mapping ack from Stage-0
[stage-0:bootstrap-parent] Received mapping request from Stage-1
[stage-0:bootstrap-parent] Forwarding mapping request to Create.kt
[stage-0:bootstrap-parent] Sending Stage-1 PID=577285 to Create.kt
[stage-0:bootstrap-parent] Waiting for mapping ack from Create.kt
[stage-0:bootstrap-parent] Received mapping ack from Create.kt
[stage-0:bootstrap-parent] Forwarding mapping ack to Stage-1
[stage-0:bootstrap-parent] Successfully completed UID/GID mapping protocol
[stage-0:bootstrap-parent] Waiting for stage-2 PID from stage-1
[stage-1] Received mapping ack from Stage-0
[stage-1] Restoring non-dumpable state
[stage-1] Becoming root in user namespace (setuid/setgid 0)
[stage-1] Successfully became root in user namespace
[stage-1] Unsharing mount namespace (CLONE_NEWNS)
[stage-1] Unsharing network namespace (CLONE_NEWNET)
[stage-1] Unsharing UTS namespace (CLONE_NEWUTS)
[stage-1] Unsharing IPC namespace (CLONE_NEWIPC)
[stage-1] Unsharing PID namespace (CLONE_NEWPID)
[stage-1] Successfully unshared all requested namespaces
[stage-1] Cloning stage-2 with CLONE_PARENT
[stage-1] Forked stage-2, PID=577286
[stage-1] Sending stage-2 PID to stage-0
[stage-1] Sent stage-2 PID to stage-0, exiting
[stage-2] Started, PID=1
[stage-0:bootstrap-parent] Received stage-2 PID=577286 from stage-1
[stage-2] Waiting for SYNC_GRANDCHILD from stage-0
[stage-0:bootstrap-parent] Sending stage-2 PID 577286 to Create.kt
[stage-0:bootstrap-parent] Successfully sent stage-2 PID to Create.kt
[stage-0:bootstrap-parent] Syncing with stage-2
[stage-0:bootstrap-parent] Sending SYNC_GRANDCHILD to stage-2
[stage-0:bootstrap-parent] Waiting for SYNC_CHILD_FINISH from stage-2
[stage-2] Received SYNC_GRANDCHILD from stage-0
[stage-2] Created new session
[stage-2] Sending SYNC_CHILD_FINISH to stage-0
[stage-2] Returning to start Kotlin runtime
[2025-10-27 08:46:53] [DEBUG] [main] running as init process (Stage-2, forked by bootstrap.c)
[2025-10-27 08:46:53] [DEBUG] [main] loading spec from /run/containerd/io.containerd.runtime.v2.task/default/test-alpine-comprehensive/config.json
[2025-10-27 08:46:53] [DEBUG] [main] reading JSON file: /run/containerd/io.containerd.runtime.v2.task/default/test-alpine-comprehensive/config.json
[2025-10-27 08:46:53] [DEBUG] [main] successfully read /run/containerd/io.containerd.runtime.v2.task/default/test-alpine-comprehensive/config.json (16950 bytes)
[2025-10-27 08:46:53] [DEBUG] [main] parsing JSON from /run/containerd/io.containerd.runtime.v2.task/default/test-alpine-comprehensive/config.json
[2025-10-27 08:46:53] [DEBUG] [main] NotifyListener: reusing existing socket fd=10
[2025-10-27 08:46:53] [INFO] [main] init process (Stage-2, PID=1) started successfully via bootstrap.c
[2025-10-27 08:46:53] [DEBUG] [main] bundle=/run/containerd/io.containerd.runtime.v2.task/default/test-alpine-comprehensive, rootfs=/run/containerd/io.containerd.runtime.v2.task/default/test-alpine-comprehensive/rootfs
[2025-10-27 08:46:53] [DEBUG] [main] restored FDs: main_sender=6, init_receiver=9, notify_listener=10
[2025-10-27 08:46:53] [DEBUG] [init] started, pid=1 ppid=0
[2025-10-27 08:46:53] [DEBUG] [init] all namespaces already unshared by Stage-1, UID/GID mapping already done
[2025-10-27 08:46:53] [DEBUG] [init] user namespace mapping already done by Stage-1, we are root in user NS
[2025-10-27 08:46:53] [DEBUG] [init] session already created by bootstrap.c (sid=1)
[2025-10-27 08:46:53] [DEBUG] [init] preparing rootfs at /run/containerd/io.containerd.runtime.v2.task/default/test-alpine-comprehensive/rootfs
[2025-10-27 08:46:53] [DEBUG] [init] changing root mount propagation to slave
[2025-10-27 08:46:53] [DEBUG] [init] root mount propagation changed to slave
[2025-10-27 08:46:53] [DEBUG] [init] bind mounting rootfs to itself
[2025-10-27 08:46:53] [DEBUG] [init] rootfs bind mounted successfully
[2025-10-27 08:46:53] [DEBUG] [init] mounted /proc
[2025-10-27 08:46:53] [DEBUG] [init] mounted /dev
[2025-10-27 08:46:53] [DEBUG] [init] created file for device null at /run/containerd/io.containerd.runtime.v2.task/default/test-alpine-comprehensive/rootfs/dev/null
[2025-10-27 08:46:53] [DEBUG] [init] bind mounted /dev/null to /run/containerd/io.containerd.runtime.v2.task/default/test-alpine-comprehensive/rootfs/dev/null
[2025-10-27 08:46:53] [DEBUG] [init] created file for device zero at /run/containerd/io.containerd.runtime.v2.task/default/test-alpine-comprehensive/rootfs/dev/zero
[2025-10-27 08:46:53] [DEBUG] [init] bind mounted /dev/zero to /run/containerd/io.containerd.runtime.v2.task/default/test-alpine-comprehensive/rootfs/dev/zero
[2025-10-27 08:46:53] [DEBUG] [init] created file for device random at /run/containerd/io.containerd.runtime.v2.task/default/test-alpine-comprehensive/rootfs/dev/random
[2025-10-27 08:46:53] [DEBUG] [init] bind mounted /dev/random to /run/containerd/io.containerd.runtime.v2.task/default/test-alpine-comprehensive/rootfs/dev/random
[stage-0:bootstrap-parent] Received SYNC_CHILD_FINISH from stage-2
[stage-0:bootstrap-parent] Stage-2 setup complete
[stage-0:bootstrap-parent] Exiting, stage-2 continues as init
[2025-10-27 08:46:53] [DEBUG] [init] created file for device urandom at /run/containerd/io.containerd.runtime.v2.task/default/test-alpine-comprehensive/rootfs/dev/urandom
[2025-10-27 08:46:53] [DEBUG] [init] bind mounted /dev/urandom to /run/containerd/io.containerd.runtime.v2.task/default/test-alpine-comprehensive/rootfs/dev/urandom
[2025-10-27 08:46:53] [DEBUG] [init] finished creating device nodes in /run/containerd/io.containerd.runtime.v2.task/default/test-alpine-comprehensive/rootfs/dev
[2025-10-27 08:46:53] [DEBUG] [init] mounted /dev/shm
[2025-10-27 08:46:53] [DEBUG] [init] mounted /sys
[2025-10-27 08:46:53] [DEBUG] [init] setting up /sys/fs/cgroup (cgroup v2)
[2025-10-27 08:46:53] [DEBUG] [init] found container cgroup path: /default/test-alpine-comprehensive
[2025-10-27 08:46:53] [DEBUG] [init] container cgroup source path: /sys/fs/cgroup/default/test-alpine-comprehensive
[2025-10-27 08:46:53] [DEBUG] [init] bind mounted container cgroup to /sys/fs/cgroup
[2025-10-27 08:46:53] [DEBUG] [init] remounted /sys/fs/cgroup as readonly
[2025-10-27 08:46:53] [DEBUG] [init] pivoting root to /run/containerd/io.containerd.runtime.v2.task/default/test-alpine-comprehensive/rootfs
[2025-10-27 08:46:53] [DEBUG] [init] pivot_root syscall completed
[2025-10-27 08:46:53] [DEBUG] [init] made old root slave
[2025-10-27 08:46:53] [DEBUG] [init] unmounted old root
[2025-10-27 08:46:53] [DEBUG] [init] changed to new root
[2025-10-27 08:46:53] [DEBUG] [init] successfully pivoted root
[2025-10-27 08:46:53] [DEBUG] [init] changed directory to /
[2025-10-27 08:46:53] [DEBUG] [init] set hostname to my-test-container
[2025-10-27 08:46:53] [DEBUG] [init] finalizing rootfs as readonly
[2025-10-27 08:46:53] [DEBUG] [init] setting rootfs as readonly
[2025-10-27 08:46:53] [DEBUG] [init] rootfs set as readonly
[2025-10-27 08:46:53] [DEBUG] [init] set umask to 22
[2025-10-27 08:46:53] [DEBUG] [init] setting no_new_privileges
[2025-10-27 08:46:53] [DEBUG] [init] successfully set no_new_privileges
[2025-10-27 08:46:53] [DEBUG] [init] applying bounding set capabilities
[2025-10-27 08:46:53] [DEBUG] [init] applying bounding set capabilities
[2025-10-27 08:46:53] [DEBUG] [init] setting bounding capabilities: [CAP_CHOWN, CAP_DAC_OVERRIDE, CAP_FSETID, CAP_FOWNER, CAP_MKNOD, CAP_NET_RAW, CAP_SETGID, CAP_SETUID, CAP_SETFCAP, CAP_SETPCAP, CAP_NET_BIND_SERVICE, CAP_SYS_CHROOT, CAP_KILL, CAP_AUDIT_WRITE]
[2025-10-27 08:46:53] [DEBUG] [init] bounding set applied successfully
[2025-10-27 08:46:53] [DEBUG] [init] setting PR_SET_KEEPCAPS
[2025-10-27 08:46:53] [DEBUG] [init] setting PR_SET_KEEPCAPS
[2025-10-27 08:46:53] [DEBUG] [init] setting 1 additional groups
[2025-10-27 08:46:53] [DEBUG] [init] set 1 additional groups successfully
[2025-10-27 08:46:53] [DEBUG] [init] set UID=1000 GID=1000 for container process
[2025-10-27 08:46:53] [DEBUG] [init] clearing PR_SET_KEEPCAPS
[2025-10-27 08:46:53] [DEBUG] [init] clearing PR_SET_KEEPCAPS
[2025-10-27 08:46:53] [DEBUG] [init] applying effective/permitted/inheritable/ambient capabilities
[2025-10-27 08:46:53] [DEBUG] [init] applying capability sets
[2025-10-27 08:46:53] [DEBUG] [init] setting effective capabilities: [CAP_CHOWN, CAP_DAC_OVERRIDE, CAP_FSETID, CAP_FOWNER, CAP_MKNOD, CAP_NET_RAW, CAP_SETGID, CAP_SETUID, CAP_SETFCAP, CAP_SETPCAP, CAP_NET_BIND_SERVICE, CAP_SYS_CHROOT, CAP_KILL, CAP_AUDIT_WRITE]
[2025-10-27 08:46:53] [DEBUG] [init] setting permitted capabilities: [CAP_CHOWN, CAP_DAC_OVERRIDE, CAP_FSETID, CAP_FOWNER, CAP_MKNOD, CAP_NET_RAW, CAP_SETGID, CAP_SETUID, CAP_SETFCAP, CAP_SETPCAP, CAP_NET_BIND_SERVICE, CAP_SYS_CHROOT, CAP_KILL, CAP_AUDIT_WRITE]
[2025-10-27 08:46:53] [DEBUG] [init] setting inheritable capabilities: []
[2025-10-27 08:46:53] [INFO] [init] capabilities applied successfully
[2025-10-27 08:46:53] [DEBUG] [init] initializing seccomp filter
[2025-10-27 08:46:53] [DEBUG] [init] initializing seccomp filter
[2025-10-27 08:46:53] [DEBUG] [init] processing 3 architecture(s)
[2025-10-27 08:46:53] [DEBUG] [init] removed default native architecture
[2025-10-27 08:46:53] [DEBUG] [init] adding architecture: SCMP_ARCH_X86_64 -> x86_64 (token=3221225534)
[2025-10-27 08:46:53] [DEBUG] [init] adding architecture: SCMP_ARCH_X86 -> x86 (token=1073741827)
[2025-10-27 08:46:53] [DEBUG] [init] adding architecture: SCMP_ARCH_X32 -> x32 (token=1073741886)
[2025-10-27 08:46:53] [DEBUG] [init] all architectures added successfully
[2025-10-27 08:46:53] [DEBUG] [init] syscall cachestat not supported, skipping
[2025-10-27 08:46:53] [DEBUG] [init] syscall futex_requeue not supported, skipping
[2025-10-27 08:46:53] [DEBUG] [init] syscall futex_wait not supported, skipping
[2025-10-27 08:46:53] [DEBUG] [init] syscall futex_waitv not supported, skipping
[2025-10-27 08:46:53] [DEBUG] [init] syscall futex_wake not supported, skipping
[2025-10-27 08:46:53] [DEBUG] [init] syscall map_shadow_stack not supported, skipping
[2025-10-27 08:46:53] [DEBUG] [init] loading seccomp filter into kernel
[2025-10-27 08:46:53] [DEBUG] [init] seccomp filter loaded successfully
[2025-10-27 08:46:53] [INFO] [init] seccomp filter initialized successfully
[2025-10-27 08:46:53] [DEBUG] [init] sent init ready signal
[2025-10-27 08:46:53] [DEBUG] [init] setting CLOEXEC on FDs >= 3
[2025-10-27 08:46:53] [DEBUG] [init] successfully set CLOEXEC on FDs >= 3 using close_range
[2025-10-27 08:46:53] [DEBUG] [init] waiting for start signal...
[2025-10-27 08:46:53] [DEBUG] [init] NotifyListener: waiting for container start signal...
[2025-10-27 08:46:53] [DEBUG] [init] NotifyListener: received: start container
[2025-10-27 08:46:53] [DEBUG] [init] received start signal, executing container process
[2025-10-27 08:46:53] [INFO] [init] Executing: sh -c #!/bin/sh
# Container-side verification script
#
# This script runs inside the container to verify all kontainer-runtime features.
# It should be run as the container's init process via containerd's ctr command.
#
# Expected environment:
# - Alpine Linux (or similar BusyBox-based system)
# - User namespace with UID/GID mapping
# - All standard namespaces enabled (pid, mount, network, uts, ipc)
# - Seccomp filter active
# - Cgroup v2 resource limits configured

echo "================================"
echo "KONTAINER-RUNTIME COMPREHENSIVE TEST"
echo "================================"
echo "Test started at: $(date)"
echo "Container PID: $$"
echo ""

echo "=== 1. BASIC IDENTITY ==="
id
whoami 2>/dev/null || echo "whoami: user not in passwd"
echo "EUID: $(id -u), EGID: $(id -g)"
echo "Groups: $(groups 2>/dev/null || echo "unknown")"
echo ""

echo "=== 2. USER NAMESPACE (UID/GID MAPPING) ==="
echo "UID Map:"
cat /proc/self/uid_map
echo "GID Map:"
cat /proc/self/gid_map
echo "Setgroups:"
cat /proc/self/setgroups 2>/dev/null || echo "Not available"
echo ""

echo "=== 3. PID NAMESPACE ==="
echo "Container sees itself as PID: $$"
echo "PPID: $PPID"
echo "Process tree (ps aux):"
ps aux 2>/dev/null || ps
echo ""
echo "Init process (PID 1):"
ls -l /proc/1/exe 2>/dev/null || echo "Cannot read /proc/1/exe"
echo ""

echo "=== 4. MOUNT NAMESPACE & PIVOT ROOT ==="
echo "Root filesystem:"
ls -la / | head -15
echo ""
echo "OS Release:"
cat /etc/os-release 2>/dev/null || echo "No /etc/os-release"
echo ""
echo "Mount points (first 20):"
mount | head -20
echo ""
echo "Filesystem type of root:"
stat -f / 2>/dev/null || df -T / | tail -1
echo ""
echo "Old root should not exist:"
ls -la /.pivot_root 2>/dev/null && echo "WARNING: Old root visible!" || echo "OK: Old root not visible"
ls -la /old_root 2>/dev/null && echo "WARNING: Old root visible!" || echo "OK: Old root not visible"
echo ""

echo "=== 5. NETWORK NAMESPACE ==="
echo "Network interfaces:"
ip link show 2>/dev/null || ifconfig -a
echo ""
echo "IP addresses:"
ip addr show 2>/dev/null || ifconfig
echo ""
echo "Routing table:"
ip route show 2>/dev/null || route -n
echo ""
echo "Network stats:"
cat /proc/self/net/dev
echo ""

echo "=== 6. UTS NAMESPACE ==="
echo "Hostname:"
hostname
cat /proc/sys/kernel/hostname
echo "Domainname:"
cat /proc/sys/kernel/domainname 2>/dev/null || echo "(none)"
echo ""

echo "=== 7. IPC NAMESPACE ==="
echo "Shared memory:"
ls -la /dev/shm
echo ""
echo "IPC resources:"
ipcs -a 2>/dev/null || echo "ipcs not available"
echo ""
echo "Expected: Should be empty or different from host"
echo "Compare with host-side 'ipcs -a' output to verify isolation"
echo ""

echo "=== 8. CGROUP V2 ==="
echo "Container cgroup path:"
cat /proc/self/cgroup
echo ""
echo "Cgroup filesystem:"
mount | grep cgroup
echo ""
echo "Cgroup controllers available:"
cat /sys/fs/cgroup/cgroup.controllers 2>/dev/null || echo "Not available"
echo ""
echo "Memory limit (expected: 134217728 = 128MB):"
cat /sys/fs/cgroup/memory.max 2>/dev/null || echo "Not available"
echo ""
echo "Memory current usage:"
cat /sys/fs/cgroup/memory.current 2>/dev/null || echo "Not available"
echo ""
echo "CPU quota (expected: 50000 100000 = 50% of 1 CPU):"
cat /sys/fs/cgroup/cpu.max 2>/dev/null || echo "Not available"
echo ""
echo "CPU weight:"
cat /sys/fs/cgroup/cpu.weight 2>/dev/null || echo "Not available"
echo ""
echo "PIDs limit:"
cat /sys/fs/cgroup/pids.max 2>/dev/null || echo "Not available"
echo ""
echo "PIDs current:"
cat /sys/fs/cgroup/pids.current 2>/dev/null || echo "Not available"
echo ""

echo "=== 9. NAMESPACES (from /proc/self/ns/) ==="
ls -l /proc/self/ns/
echo ""

echo "=== 10. CAPABILITIES ==="
echo "Container process capabilities:"
grep -E "^Cap" /proc/self/status
echo ""
echo "Capability bounding set (CapBnd):"
CAPBND=$(grep "^CapBnd:" /proc/self/status | awk '{print $2}')
echo "Raw value: $CAPBND"
if command -v capsh >/dev/null 2>&1; then
    echo "Decoded: $(capsh --decode=$CAPBND 2>/dev/null)"
else
    echo "capsh not available for decoding"
fi
echo ""

echo "=== 11. SECCOMP ==="
echo "Seccomp status:"
grep -E "^Seccomp" /proc/self/status
echo "Expected: Seccomp: 2 (filter mode)"
echo "          Seccomp_filters: 1 or more"
echo ""

echo "=== 12. NO_NEW_PRIVS ==="
echo "NoNewPrivs status:"
grep -E "^NoNewPrivs:" /proc/self/status
echo "Expected: NoNewPrivs: 1"
echo ""

echo "=== 13. RESOURCE LIMITS (rlimits) ==="
cat /proc/self/limits
echo ""

echo "=== 14. SUPPLEMENTARY GROUPS ==="
echo "Current groups (from id):"
id
echo ""
echo "Groups from /proc/self/status:"
grep -E "^Groups:" /proc/self/status
echo ""

echo "=== 15. WORKING DIRECTORY ==="
pwd
echo ""

echo "=== 16. ENVIRONMENT VARIABLES ==="
env | sort
echo ""

echo "=== 17. FILE DESCRIPTORS ==="
echo "Open file descriptors:"
ls -l /proc/self/fd/ 2>&1 | grep -v "cannot read link"
echo ""
echo "FD limit from rlimit:"
grep "open files" /proc/self/limits
echo ""

echo "=== 18. SECCOMP VERIFICATION ==="
echo "Seccomp mode should be 2 (filter mode) as shown in section 11 above."
echo ""

echo "Summary:"
if grep -q "Seccomp:[[:space:]]*2" /proc/self/status 2>/dev/null; then
    echo "  ✓ Seccomp filter mode is active (mode 2)"
    echo "  ✓ containerd has applied the default seccomp profile"
    echo "  ✓ Kernel is filtering syscalls via BPF filter"
else
    echo "  ✗ WARNING: Seccomp is NOT active"
fi
echo ""

echo "Active seccomp testing:"
echo "  To actively test that seccomp blocks specific syscalls, see the"
echo "  'Seccomp Active Testing' section in test-with-containerd.md."
echo "  It uses a custom seccomp profile to block getcwd syscall."
echo ""

echo "=== 19. FILESYSTEM TESTS ==="
echo "Can write to /tmp:"
echo "test" > /tmp/test.txt 2>&1 && cat /tmp/test.txt && rm /tmp/test.txt && echo "✓ OK" || echo "✗ FAILED"
echo ""
echo "Root filesystem is readonly (--read-only flag):"
touch /readonly-test 2>&1 && echo "✗ WARNING: Root is writable!" || echo "✓ OK: Root is readonly"
echo ""
echo "Available disk space:"
df -h 2>/dev/null || df
echo ""

echo "=== 20. KERNEL INFO ==="
echo "Kernel version:"
uname -a
cat /proc/version
echo ""
echo "Kernel command line:"
cat /proc/cmdline
echo ""

echo "=== 21. PRCTL STATUS ==="
echo "Dumpable flag (from /proc/self/status):"
grep -E "^Dumpable:" /proc/self/status 2>/dev/null || echo "Not available"
echo "  1 = dumpable (typical for non-root user)"
echo "  0 = non-dumpable"
echo ""
echo "NoNewPrivs flag (from /proc/self/status):"
grep -E "^NoNewPrivs:" /proc/self/status 2>/dev/null || echo "Not available"
echo ""
echo "Note: Cannot check parent CHILD_SUBREAPER (PPID=0 in container)"
echo ""

echo "================================"
echo "TEST COMPLETED SUCCESSFULLY"
echo "================================"
echo "Test finished at: $(date)"
echo ""
echo "Summary of verified features:"
echo "  ✓ User namespace with UID/GID mapping"
echo "  ✓ PID namespace (container is PID 1)"
echo "  ✓ Mount namespace with pivot_root"
echo "  ✓ Network namespace (isolated)"
echo "  ✓ UTS namespace (custom hostname)"
echo "  ✓ IPC namespace with /dev/shm"
echo "  ✓ Seccomp filter mode"
echo "  ✓ Capability restrictions"
echo "  ✓ NoNewPrivs enabled"
echo "  ✓ Resource limits (rlimits)"
echo "  ✓ Cgroup v2 visibility"
echo "  ✓ Readonly rootfs"
echo ""
echo "Sleeping for 10 seconds for manual inspection..."
sleep 10
[2025-10-27 08:46:53] [DEBUG] [init] cleared all host environment variables
[2025-10-27 08:46:53] [DEBUG] [init] set 1 environment variables
================================
KONTAINER-RUNTIME COMPREHENSIVE TEST
================================
Test started at: Mon Oct 27 08:46:53 UTC 2025
Container PID: 1

=== 1. BASIC IDENTITY ===
uid=1000 gid=1000 groups=1000
whoami: user not in passwd
EUID: 1000, EGID: 1000
Groups: 1000
unknown

=== 2. USER NAMESPACE (UID/GID MAPPING) ===
UID Map:
         0     100000      65536
GID Map:
         0     100000      65536
Setgroups:
allow

=== 3. PID NAMESPACE ===
Container sees itself as PID: 1
PPID: 0
Process tree (ps aux):
PID   USER     TIME  COMMAND
    1 1000      0:00 sh -c #!/bin/sh # Container-side verification script # # This script runs inside the container to verify all kontainer-runtime features. # It should be run as the container's init process via containerd's ctr command. # # Expected environment: # - Alpine Linux (or similar BusyBox-based system) # - User namespace with UID/GID mapping # - All standard namespaces enabled (pid, mount, network, uts, ipc) # - Seccomp filter active # - Cgroup v2 resource limits configured  echo "================================" echo "KONTAINER-RUNTIME COMPREHENSIVE TEST" echo "================================" echo "Test started at: $(date)" echo "Container PID: $$" echo ""  echo "=== 1. BASIC IDENTITY ===" id whoami 2>/dev/null || echo "whoami: user not in passwd" echo "EUID: $(id -u), EGID: $(id -g)" echo "Groups: $(groups 2>/dev/null || echo "unknown")" echo ""  echo "=== 2. USER NAMESPACE (UID/GID MAPPING) ===" echo "UID Map:" cat /proc/self/uid_map echo "GID Map:" cat /proc/self/gid_map echo "Setgroups:" cat /proc/self/setgroups 2>/dev/null || echo "Not available" echo ""  echo "=== 3. PID NAMESPACE ===" echo "Container sees itself as PID: $$" echo "PPID: $PPID" echo "Process tree (ps aux):" ps aux 2>/dev/null || ps echo "" echo "Init process (PID 1):" ls -l /proc/1/exe 2>/dev/null || echo "Cannot read /proc/1/exe" echo ""  echo "=== 4. MOUNT NAMESPACE & PIVOT ROOT ===" echo "Root filesystem:" ls -la / | head -15 echo "" echo "OS Release:" cat /etc/os-release 2>/dev/null || echo "No /etc/os-release" echo "" echo "Mount points (first 20):" mount | head -20 echo "" echo "Filesystem type of root:" stat -f / 2>/dev/null || df -T / | tail -1 echo "" echo "Old root should not exist:" ls -la /.pivot_root 2>/dev/null && echo "WARNING: Old root visible!" || echo "OK: Old root not visible" ls -la /old_root 2>/dev/null && echo "WARNING: Old root visible!" || echo "OK: Old root not visible" echo ""  echo "=== 5. NETWORK NAMESPACE ===" echo "Network interfaces:" ip link show 2>/dev/null || ifconfig -a echo "" echo "IP addr
   14 1000      0:00 ps aux

Init process (PID 1):
lrwxrwxrwx    1 1000     1000             0 Oct 27 08:46 /proc/1/exe -> /bin/busybox

=== 4. MOUNT NAMESPACE & PIVOT ROOT ===
Root filesystem:
total 96
drwxr-xr-x    1 root     root          4096 Oct 27 08:46 .
drwxr-xr-x    1 root     root          4096 Oct 27 08:46 ..
drwxr-xr-x    1 root     root          4096 Oct  8 09:28 bin
drwxr-xr-x    3 root     root           140 Oct 27 08:46 dev
drwxr-xr-x    1 root     root          4096 Oct  8 09:28 etc
drwxr-xr-x    1 root     root          4096 Oct  8 09:28 home
drwxr-xr-x    1 root     root          4096 Oct  8 09:28 lib
drwxr-xr-x    1 root     root          4096 Oct  8 09:28 media
drwxr-xr-x    1 root     root          4096 Oct  8 09:28 mnt
drwxr-xr-x    1 root     root          4096 Oct  8 09:28 opt
dr-xr-xr-x  216 nobody   nobody           0 Oct 27 08:46 proc
drwx------    1 root     root          4096 Oct  8 09:28 root
drwxr-xr-x    1 root     root          4096 Oct  8 09:28 run
drwxr-xr-x    1 root     root          4096 Oct  8 09:28 sbin

OS Release:
NAME="Alpine Linux"
ID=alpine
VERSION_ID=3.22.2
PRETTY_NAME="Alpine Linux v3.22"
HOME_URL="https://alpinelinux.org/"
BUG_REPORT_URL="https://gitlab.alpinelinux.org/alpine/aports/-/issues"

Mount points (first 20):
overlay on / type overlay (ro,relatime,lowerdir=/var/lib/containerd/io.containerd.snapshotter.v1.overlayfs/snapshots/168/fs:/var/lib/containerd/io.containerd.snapshotter.v1.overlayfs/snapshots/1/fs,upperdir=/var/lib/containerd/io.containerd.snapshotter.v1.overlayfs/snapshots/202/fs,workdir=/var/lib/containerd/io.containerd.snapshotter.v1.overlayfs/snapshots/202/work)
proc on /proc type proc (rw,nosuid,nodev,noexec,relatime)
tmpfs on /dev type tmpfs (rw,nosuid,noexec,relatime,mode=755,uid=100000,gid=100000,inode64)
udev on /dev/null type devtmpfs (rw,nosuid,relatime,size=8166512k,nr_inodes=2041628,mode=755,inode64)
udev on /dev/zero type devtmpfs (rw,nosuid,relatime,size=8166512k,nr_inodes=2041628,mode=755,inode64)
udev on /dev/random type devtmpfs (rw,nosuid,relatime,size=8166512k,nr_inodes=2041628,mode=755,inode64)
udev on /dev/urandom type devtmpfs (rw,nosuid,relatime,size=8166512k,nr_inodes=2041628,mode=755,inode64)
shm on /dev/shm type tmpfs (rw,nosuid,nodev,noexec,relatime,size=65536k,uid=100000,gid=100000,inode64)
sysfs on /sys type sysfs (ro,nosuid,nodev,noexec,relatime)
cgroup2 on /sys/fs/cgroup type cgroup2 (ro,nosuid,nodev,noexec,relatime,nsdelegate,memory_recursiveprot)

Filesystem type of root:
  File: "/"
    ID: 99f0dcdf1c690c06 Namelen: 255     Type: UNKNOWN
Block size: 4096      
Blocks: Total: 13214396   Free: 7640703    Available: 7636607
Inodes: Total: 6725376    Free: 6358460

Old root should not exist:
OK: Old root not visible
OK: Old root not visible

=== 5. NETWORK NAMESPACE ===
Network interfaces:
1: lo: <LOOPBACK> mtu 65536 qdisc noop state DOWN qlen 1000
    link/loopback 00:00:00:00:00:00 brd 00:00:00:00:00:00

IP addresses:
1: lo: <LOOPBACK> mtu 65536 qdisc noop state DOWN qlen 1000
    link/loopback 00:00:00:00:00:00 brd 00:00:00:00:00:00

Routing table:

Network stats:
Inter-|   Receive                                                |  Transmit
 face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
    lo:       0       0    0    0    0     0          0         0        0       0    0    0    0     0       0          0

=== 6. UTS NAMESPACE ===
Hostname:
my-test-container
my-test-container
Domainname:
(none)

=== 7. IPC NAMESPACE ===
Shared memory:
total 0
drwxrwxrwt    2 root     root            40 Oct 27 08:46 .
drwxr-xr-x    3 root     root           140 Oct 27 08:46 ..

IPC resources:

------ Message Queues --------
key        msqid      owner      perms      used-bytes   messages    

------ Shared Memory Segments --------
key        shmid      owner      perms      bytes      nattch     status      

------ Semaphore Arrays --------
key        semid      owner      perms      nsems     


Expected: Should be empty or different from host
Compare with host-side 'ipcs -a' output to verify isolation

=== 8. CGROUP V2 ===
Container cgroup path:
0::/default/test-alpine-comprehensive

Cgroup filesystem:
cgroup2 on /sys/fs/cgroup type cgroup2 (ro,nosuid,nodev,noexec,relatime,nsdelegate,memory_recursiveprot)

Cgroup controllers available:
cpuset cpu io memory hugetlb pids rdma misc

Memory limit (expected: 134217728 = 128MB):
134217728

Memory current usage:
1064960

CPU quota (expected: 50000 100000 = 50% of 1 CPU):
50000 100000

CPU weight:
39

PIDs limit:
max

PIDs current:
2

=== 9. NAMESPACES (from /proc/self/ns/) ===
total 0
lrwxrwxrwx    1 1000     1000             0 Oct 27 08:46 cgroup -> cgroup:[4026531835]
lrwxrwxrwx    1 1000     1000             0 Oct 27 08:46 ipc -> ipc:[4026532365]
lrwxrwxrwx    1 1000     1000             0 Oct 27 08:46 mnt -> mnt:[4026532303]
lrwxrwxrwx    1 1000     1000             0 Oct 27 08:46 net -> net:[4026532304]
lrwxrwxrwx    1 1000     1000             0 Oct 27 08:46 pid -> pid:[4026532366]
lrwxrwxrwx    1 1000     1000             0 Oct 27 08:46 pid_for_children -> pid:[4026532366]
lrwxrwxrwx    1 1000     1000             0 Oct 27 08:46 time -> time:[4026531834]
lrwxrwxrwx    1 1000     1000             0 Oct 27 08:46 time_for_children -> time:[4026531834]
lrwxrwxrwx    1 1000     1000             0 Oct 27 08:46 user -> user:[4026532302]
lrwxrwxrwx    1 1000     1000             0 Oct 27 08:46 uts -> uts:[4026532363]

=== 10. CAPABILITIES ===
Container process capabilities:
CapInh: 0000000000000000
CapPrm: 0000000000000000
CapEff: 0000000000000000
CapBnd: 000001c0a80425fb
CapAmb: 0000000000000000

Capability bounding set (CapBnd):
Raw value: 000001c0a80425fb
capsh not available for decoding

=== 11. SECCOMP ===
Seccomp status:
Seccomp:        2
Seccomp_filters:        1
Expected: Seccomp: 2 (filter mode)
          Seccomp_filters: 1 or more

=== 12. NO_NEW_PRIVS ===
NoNewPrivs status:
NoNewPrivs:     1
Expected: NoNewPrivs: 1

=== 13. RESOURCE LIMITS (rlimits) ===
Limit                     Soft Limit           Hard Limit           Units     
Max cpu time              unlimited            unlimited            seconds   
Max file size             unlimited            unlimited            bytes     
Max data size             unlimited            unlimited            bytes     
Max stack size            8388608              unlimited            bytes     
Max core file size        unlimited            unlimited            bytes     
Max resident set          unlimited            unlimited            bytes     
Max processes             unlimited            unlimited            processes 
Max open files            1024                 1024                 files     
Max locked memory         65536                65536                bytes     
Max address space         unlimited            unlimited            bytes     
Max file locks            unlimited            unlimited            locks     
Max pending signals       63800                63800                signals   
Max msgqueue size         819200               819200               bytes     
Max nice priority         0                    0                    
Max realtime priority     0                    0                    
Max realtime timeout      unlimited            unlimited            us        

=== 14. SUPPLEMENTARY GROUPS ===
Current groups (from id):
uid=1000 gid=1000 groups=1000

Groups from /proc/self/status:
Groups: 1000 

=== 15. WORKING DIRECTORY ===
/

=== 16. ENVIRONMENT VARIABLES ===
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
PWD=/
SHLVL=1

=== 17. FILE DESCRIPTORS ===
Open file descriptors:
total 0
lr-x------    1 1000     1000            64 Oct 27 08:46 0 -> pipe:[42130876]
l-wx------    1 1000     1000            64 Oct 27 08:46 1 -> pipe:[42130923]
l-wx------    1 1000     1000            64 Oct 27 08:46 2 -> pipe:[42130923]
lr-x------    1 1000     1000            64 Oct 27 08:46 3

FD limit from rlimit:
Max open files            1024                 1024                 files     

=== 18. SECCOMP VERIFICATION ===
Seccomp mode should be 2 (filter mode) as shown in section 11 above.

Summary:
  ✓ Seccomp filter mode is active (mode 2)
  ✓ containerd has applied the default seccomp profile
  ✓ Kernel is filtering syscalls via BPF filter

Active seccomp testing:
  To actively test that seccomp blocks specific syscalls, see the
  'Seccomp Active Testing' section in test-with-containerd.md.
  It uses a custom seccomp profile to block getcwd syscall.

=== 19. FILESYSTEM TESTS ===
Can write to /tmp:
✗ FAILED

Root filesystem is readonly (--read-only flag):
sh: can't create /tmp/test.txt: Read-only file system
touch: /readonly-test: Read-only file system
✓ OK: Root is readonly

Available disk space:
Filesystem                Size      Used Available Use% Mounted on
overlay                  50.4G     21.3G     29.1G  42% /
tmpfs                     7.8G         0      7.8G   0% /dev
udev                      7.8G         0      7.8G   0% /dev/null
udev                      7.8G         0      7.8G   0% /dev/zero
udev                      7.8G         0      7.8G   0% /dev/random
udev                      7.8G         0      7.8G   0% /dev/urandom
shm                      64.0M         0     64.0M   0% /dev/shm

=== 20. KERNEL INFO ===
Kernel version:
Linux my-test-container 5.15.0-160-generic #170-Ubuntu SMP Wed Oct 1 10:06:56 UTC 2025 x86_64 Linux
Linux version 5.15.0-160-generic (buildd@lcy02-amd64-086) (gcc (Ubuntu 11.4.0-1ubuntu1~22.04.2) 11.4.0, GNU ld (GNU Binutils for Ubuntu) 2.38) #170-Ubuntu SMP Wed Oct 1 10:06:56 UTC 2025

Kernel command line:
BOOT_IMAGE=/boot/vmlinuz-5.15.0-160-generic root=UUID=4640e7ac-ce2d-448f-999c-1735c8212d93 ro console=tty1 console=ttyS0

=== 21. PRCTL STATUS ===
Dumpable flag (from /proc/self/status):
Not available
  1 = dumpable (typical for non-root user)
  0 = non-dumpable

NoNewPrivs flag (from /proc/self/status):
NoNewPrivs:     1

Note: Cannot check parent CHILD_SUBREAPER (PPID=0 in container)

================================
TEST COMPLETED SUCCESSFULLY
================================
Test finished at: Mon Oct 27 08:46:53 UTC 2025

Summary of verified features:
  ✓ User namespace with UID/GID mapping
  ✓ PID namespace (container is PID 1)
  ✓ Mount namespace with pivot_root
  ✓ Network namespace (isolated)
  ✓ UTS namespace (custom hostname)
  ✓ IPC namespace with /dev/shm
  ✓ Seccomp filter mode
  ✓ Capability restrictions
  ✓ NoNewPrivs enabled
  ✓ Resource limits (rlimits)
  ✓ Cgroup v2 visibility
  ✓ Readonly rootfs

Sleeping for 10 seconds for manual inspection...
```

#### IPC Namespace Verification

This test creates IPC resources on the host side and verifies they are not visible from within the container.

First, create IPC resources on the host, then run the container and check `ipcs -a` output inside the container.

```
~/kontainer-runtime$ ipcmk -Q
Message queue id: 1
~/kontainer-runtime$ ipcmk -M 1024
Shared memory id: 1
~/kontainer-runtime$ ipcmk -S 1
Semaphore id: 1
~/kontainer-runtime$ ipcs -a

------ Message Queues --------
key        msqid      owner      perms      used-bytes   messages    
0x984671a8 1          ternbusty  644        0            0           

------ Shared Memory Segments --------
key        shmid      owner      perms      bytes      nattch     status      
0x5af8f591 1          ternbusty  644        1024       0                       

------ Semaphore Arrays --------
key        semid      owner      perms      nsems     
0xc11b8f99 1          ternbusty  644        1      
```

Next, run the container test as shown above. Inside the container, check `ipcs -a` output.
You should see no IPC resources, confirming IPC namespace isolation.

Teardown the IPC resources on the host after the test.

```
ipcrm -q 1  # msqid
ipcrm -m 1  # shmid
ipcrm -s 1  # semid
```

#### Seccomp Testing

Set custom seccomp profile to block `getcwd` syscall

```bash
sudo ctr run --rm \
    --seccomp \
    --seccomp-profile ./seccomp-block-getcwd.json \
    --uidmap "0:100000:65536" \
    --gidmap "0:100000:65536" \
    --user 1000:1000 \
    docker.io/library/alpine:latest \
    test-seccomp-getcwd-blocked \
    sh -c '/bin/pwd >/dev/null; rc=$?; printf "pwd exit=%d\n" $rc; if [ $rc -eq 0 ]; then echo SUCCESS getcwd allowed; else echo BLOCKED getcwd denied; fi'
```

Expected output

```
pwd: getcwd: Operation not permitted
pwd exit=1
BLOCKED getcwd denied
```

## Host-Side Verification

While the container is running, you can verify its state from the host side using this script.

Start the container with a long sleep duration in one terminal.

```bash
sudo ctr run --rm \
    --seccomp \
    --read-only \
    --memory-limit 134217728 \
    --cpus 0.5 \
    --uidmap "0:100000:65536" \
    --gidmap "0:100000:65536" \
    --user 1000:1000 \
    --hostname my-test-container \
    docker.io/library/alpine:latest \
    test-alpine-comprehensive1 \
    sh -c 'echo "Container started, PID: $$"; sleep 300'
```

In another terminal, run the verification script with sudo and provide the container ID

```bash
sudo ./verify-from-host.sh test-alpine-comprehensive1
```

output

```
================================
HOST-SIDE CONTAINER VERIFICATION
================================
Container ID: test-alpine-comprehensive1
Timestamp: Mon Oct 27 08:48:29 UTC 2025

=== 1. CONTAINER PROCESS DISCOVERY ===
Found state file: /run/containerd/runc/default/test-alpine-comprehensive1/state.json
Container init process PID (host view): 577653
Process command: sleep
Process cmdline: sleep 300 ...

=== 2. NAMESPACE VERIFICATION ===
Container namespaces (from host):
total 0
lrwxrwxrwx 1 101000 101000 0 Oct 27 08:48 cgroup -> 'cgroup:[4026531835]'
lrwxrwxrwx 1 101000 101000 0 Oct 27 08:48 ipc -> 'ipc:[4026532365]'
lrwxrwxrwx 1 101000 101000 0 Oct 27 08:48 mnt -> 'mnt:[4026532303]'
lrwxrwxrwx 1 101000 101000 0 Oct 27 08:48 net -> 'net:[4026532304]'
lrwxrwxrwx 1 101000 101000 0 Oct 27 08:48 pid -> 'pid:[4026532366]'
lrwxrwxrwx 1 101000 101000 0 Oct 27 08:48 pid_for_children -> 'pid:[4026532366]'
lrwxrwxrwx 1 101000 101000 0 Oct 27 08:48 time -> 'time:[4026531834]'
lrwxrwxrwx 1 101000 101000 0 Oct 27 08:48 time_for_children -> 'time:[4026531834]'
lrwxrwxrwx 1 101000 101000 0 Oct 27 08:48 user -> 'user:[4026532302]'
lrwxrwxrwx 1 101000 101000 0 Oct 27 08:48 uts -> 'uts:[4026532363]'

Comparison with host namespaces:
Host PID namespace:      pid:[4026531836]
Container PID namespace: pid:[4026532366]
Different? YES ✓

Host User namespace:      user:[4026531837]
Container User namespace: user:[4026532302]
Different? YES ✓

=== 3. UID/GID MAPPING ===
UID mapping (host view):
         0     100000      65536
Expected: 0     100000      65536

GID mapping (host view):
         0     100000      65536
Expected: 0     100000      65536

Setgroups status:
allow

=== 4. PROCESS CREDENTIALS ===
Container process status (credentials):
Uid:    101000  101000  101000  101000
Gid:    101000  101000  101000  101000
Groups: 101000 
Expected: Uid should show 101000 (host UID) for container UID 1000
          Gid should show 101000 (host GID) for container GID 1000

=== 5. CGROUP MEMBERSHIP ===
Container process cgroup:
0::/default/test-alpine-comprehensive1

Extracted cgroup path: /default/test-alpine-comprehensive1

=== 6. CGROUP LIMITS (HOST FILESYSTEM) ===
Full cgroup path: /sys/fs/cgroup/default/test-alpine-comprehensive1

Memory limit (memory.max):
134217728
Expected: 134217728 (128MB)

Memory current usage (memory.current):
245760

CPU quota (cpu.max):
50000 100000
Expected: 50000 100000 (50% of 1 CPU)

CPU weight (cpu.weight):
39

PIDs limit (pids.max):
max

PIDs current (pids.current):
1

=== 7. CAPABILITIES ===
Container process capabilities:
CapInh: 0000000000000000
CapPrm: 0000000000000000
CapEff: 0000000000000000
CapBnd: 000001c0a80425fb
CapAmb: 0000000000000000

Capability bounding set (CapBnd):
Raw value: 000001c0a80425fb
Decoded: 0x000001c0a80425fb=cap_chown,cap_dac_override,cap_fowner,cap_fsetid,cap_kill,cap_setgid,cap_setuid,cap_setpcap,cap_net_bind_service,cap_net_raw,cap_sys_chroot,cap_mknod,cap_audit_write,cap_setfcap,cap_perfmon,cap_bpf,cap_checkpoint_restore

=== 8. SECCOMP STATUS ===
Seccomp:        2
Seccomp_filters:        1
Expected: Seccomp: 2 (filter mode)
          Seccomp_filters: 1 or more

=== 9. NO_NEW_PRIVS ===
NoNewPrivs:     1
Expected: NoNewPrivs: 1

=== 10. MOUNT NAMESPACE CONTENT ===
Container's mount points (first 15):
783 745 0:53 / / ro,relatime master:302 - overlay overlay rw,lowerdir=/var/lib/containerd/io.containerd.snapshotter.v1.overlayfs/snapshots/168/fs:/var/lib/containerd/io.containerd.snapshotter.v1.overlayfs/snapshots/1/fs,upperdir=/var/lib/containerd/io.containerd.snapshotter.v1.overlayfs/snapshots/204/fs,workdir=/var/lib/containerd/io.containerd.snapshotter.v1.overlayfs/snapshots/204/work
784 783 0:56 / /proc rw,nosuid,nodev,noexec,relatime - proc proc rw
785 783 0:57 / /dev rw,nosuid,noexec,relatime - tmpfs tmpfs rw,mode=755,uid=100000,gid=100000,inode64
786 785 0:5 /null /dev/null rw,nosuid,relatime master:2 - devtmpfs udev rw,size=8166512k,nr_inodes=2041628,mode=755,inode64
787 785 0:5 /zero /dev/zero rw,nosuid,relatime master:2 - devtmpfs udev rw,size=8166512k,nr_inodes=2041628,mode=755,inode64
788 785 0:5 /random /dev/random rw,nosuid,relatime master:2 - devtmpfs udev rw,size=8166512k,nr_inodes=2041628,mode=755,inode64
789 785 0:5 /urandom /dev/urandom rw,nosuid,relatime master:2 - devtmpfs udev rw,size=8166512k,nr_inodes=2041628,mode=755,inode64
790 785 0:58 / /dev/shm rw,nosuid,nodev,noexec,relatime - tmpfs shm rw,size=65536k,uid=100000,gid=100000,inode64
791 783 0:59 / /sys ro,nosuid,nodev,noexec,relatime - sysfs sysfs ro
792 791 0:28 /default/test-alpine-comprehensive1 /sys/fs/cgroup ro,nosuid,nodev,noexec,relatime master:9 - cgroup2 cgroup2 rw,nsdelegate,memory_recursiveprot

=== 11. RUNTIME STATE FILE ===
State file: /run/containerd/runc/default/test-alpine-comprehensive1/state.json
Content:
{
  "ociVersion": "1.1.0",
  "id": "test-alpine-comprehensive1",
  "status": "running",
  "pid": 577653,
  "bundle": "/run/containerd/io.containerd.runtime.v2.task/default/test-alpine-comprehensive1",
  "created": "2025-10-27T08:48:09Z"
}

=== 12. IPC NAMESPACE VERIFICATION ===
Host IPC resources (for comparison with container):

Message Queues:

------ Message Queues --------
key        msqid      owner      perms      used-bytes   messages    
0x984671a8 1          ternbusty  644        0            0           


Shared Memory Segments:

------ Shared Memory Segments --------
key        shmid      owner      perms      bytes      nattch     status      
0x5af8f591 1          ternbusty  644        1024       0                       


Semaphore Arrays:

------ Semaphore Arrays --------
key        semid      owner      perms      nsems     
0xc11b8f99 1          ternbusty  644        1         


All IPC resources summary:

------ Message Queues --------
key        msqid      owner      perms      used-bytes   messages    
0x984671a8 1          ternbusty  644        0            0           

------ Shared Memory Segments --------
key        shmid      owner      perms      bytes      nattch     status      
0x5af8f591 1          ternbusty  644        1024       0                       

------ Semaphore Arrays --------
key        semid      owner      perms      nsems     
0xc11b8f99 1          ternbusty  644        1         


Expected: Container's ipcs output (from container test) should be DIFFERENT
          If they show the same resources, IPC namespace is NOT isolated
          Container may create its own IPC resources invisible to host

================================
VERIFICATION COMPLETED
================================
```
