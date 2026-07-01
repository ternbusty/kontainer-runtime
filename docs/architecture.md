# Architecture

## Process model

Creating a container involves three cooperating processes.

```mermaid
flowchart TD
    CLI["kontainer-runtime create<br/>(user shell)"]
    CLI --> Main["main<br/>Kotlin, multi-threaded"]
    Main -- "fork + exec self with<br/>_KONTAINER_IS_BOOTSTRAP=1" --> Stage1["stage-1<br/>bootstrap.c constructor<br/>single-threaded C"]
    Stage1 -- "setns paths<br/>unshare remaining ns<br/>clone(CLONE_PARENT)" --> Stage2["stage-2 (init, PID 1)<br/>Kotlin runInitProcess"]
    Stage2 -- "execve" --> Container["container process<br/>(spec.process.args)"]
    Stage1 -. "exits after clone" .-> X((·))
    Main -. "waits for init-ready,<br/>saves state.json, exits" .-> Y((·))
```

### Main process

The CLI you invoke. Loads the OCI spec, sets up cgroup/rlimits on stage-1, handles the UID/GID mapping handshake, forwards the seccomp notify FD if configured, waits for stage-2 to reach "init ready", saves `state.json`, exits. Also runs `prestart` / `createRuntime` / `poststart` / `poststop` hooks from its own namespace.

### Stage-1

A short-lived C bootstrap living in [`src/nativeInterop/cinterop/bootstrap/bootstrap.c`](https://github.com/ternbusty/kontainer-runtime/blob/main/src/nativeInterop/cinterop/bootstrap/bootstrap.c). It calls `setns` for every `spec.linux.namespaces[].path` entry. The kernel accepts mount-ns `setns` only from a single-threaded process, and PID-ns joining must happen before the fork of stage-2. Stage-1 then runs `unshare` for the remaining namespaces and `clone`s stage-2 with `CLONE_PARENT`, which makes stage-2 a sibling of main. Stage-1 exits right after.

### Stage-2 (init)

PID 1 in the container. Runs `runInitProcess()` in Kotlin. Does `prepareRootfs`, `pivot_root`, `applySysctls`, `applyMaskedPaths`, the capabilities/setuid dance, the seccomp filter install, the `createContainer` hook, waits for the start signal, runs the `startContainer` hook, then `execve`s into the user's program.

## Container lifecycle sequence

The full `create` + `start` flow. UID/GID map handshake, seccomp notify FD forwarding, and hook points all live inside it as `alt` or `opt` blocks. Everything below the "start" divider only runs when the user invokes `kontainer-runtime start #lt;id#gt;` in a separate process.

```mermaid
sequenceDiagram
    autonumber
    actor User as User shell
    participant Main as main<br/>(Kotlin, multi-threaded)
    participant S1 as stage-1<br/>(bootstrap.c, single-threaded)
    participant S2 as stage-2 / init<br/>(Kotlin, PID 1 in container)
    participant Listener as seccomp<br/>listenerPath

    User->>Main: kontainer-runtime create #lt;id#gt;
    Note over Main: loadSpec, resolveCgroupPath,<br/>SocketNotifyListener bind
    Main->>S1: fork + execve self<br/>(env: _KONTAINER_IS_BOOTSTRAP=1,<br/>clone flags, ns paths, FDs)

    opt spec.linux.namespaces contains "user"
        S1->>S1: unshare(CLONE_NEWUSER)
        S1->>S1: prctl(PR_SET_DUMPABLE, 1)
        S1->>Main: SYNC_USERMAP_PLS (0x40) + stage-1 pid
        Note over Main: write /proc/#lt;s1#gt;/setgroups (deny if unprivileged),<br/>/proc/#lt;s1#gt;/uid_map, /proc/#lt;s1#gt;/gid_map
        Main->>S1: SYNC_USERMAP_ACK (0x41)
        S1->>S1: prctl(PR_SET_DUMPABLE, 0)
        S1->>S1: setuid(0), setgid(0)
    end

    S1->>S1: setns for spec.linux.namespaces[].path entries
    S1->>S1: unshare(remaining flags: mount, net, uts, ipc, pid, cgroup)
    S1->>S2: clone(CLONE_PARENT | SIGCHLD)
    S1->>Main: stage-2 pid (int32) over sync socket
    S1--)Main: exit

    Note over Main: cgroup.setup(stage2Pid, resolvedPath, resources),<br/>applyRlimits(stage2Pid)

    Note over S2: setLoopbackUp,<br/>prepareRootfs (mount /proc, /dev, /sys, devices, symlinks),<br/>applySpecMounts
    opt spec.hooks.createContainer
        S2->>S2: exec each hook with state JSON on stdin
    end
    Note over S2: pivot_root(rootfsPath, rootfsPath),<br/>applyRootfsPropagation,<br/>chdir(cwd), sethostname,<br/>applyLinuxDevices, applySysctls,<br/>applyMaskedPaths, applyReadonlyPaths,<br/>finalizeRootfs, applyRlimits(0),<br/>setNoNewPrivileges if requested

    S2->>S2: seccomp(SET_MODE_FILTER, ..., NEW_LISTENER)
    opt any syscall action is SCMP_ACT_NOTIFY
        S2->>Main: notify FD via SCM_RIGHTS
        Main->>Listener: connect(listenerPath)
        Main->>Listener: forward FD + container state JSON
        Main->>S2: seccompNotifyDone
    end

    Note over S2: applyBoundingSet, setKeepCaps,<br/>setgid, setuid, clearKeepCaps,<br/>applyCapabilities (capset),<br/>write AppArmor / SELinux exec label to /proc/self/attr/*

    S2->>Main: Init Ready
    Note over Main: save state.json (status=created),<br/>save internal config
    opt spec.hooks.prestart / createRuntime
        Main->>Main: exec each hook with state JSON on stdin
    end
    Main--)User: exit 0
    S2->>S2: closeRange (fallback via /proc/self/fd if seccomp blocks it)
    S2->>S2: listen on /tmp/kontainer-#lt;id#gt;.sock

    Note over User,S2: A separate process runs kontainer-runtime start #lt;id#gt; below.

    User->>Main: kontainer-runtime start #lt;id#gt;
    Main->>Main: loadState, check status == created
    Main->>S2: notifyContainerStart via /tmp/kontainer-#lt;id#gt;.sock
    Main->>Main: save state.json (status=running)
    opt spec.hooks.poststart
        Main->>Main: exec each hook with state JSON on stdin
    end
    Main--)User: exit 0

    opt spec.hooks.startContainer
        S2->>S2: exec each hook with state JSON on stdin
    end
    S2->>S2: execve(spec.process.args)
    Note over S2: The process image is now the container's program.<br/>Kontainer-runtime code is gone from this PID.
```

The `opt` blocks fire when the corresponding OCI feature is present in the spec. On a bare-bones spec (no user namespace, no `SCMP_ACT_NOTIFY`, no hooks) the flow collapses to the linear main-path: fork stage-1, stage-1 unshares and clones stage-2, stage-2 does rootfs + capability + seccomp setup, main saves state and exits, then `start` wakes stage-2 for `execve`.

## Why the C bootstrap

Kotlin/Native spawns GC and runtime worker threads at `main`. Several kernel operations reject multi-threaded callers. `setns(fd, CLONE_NEWNS)` returns `EINVAL`, and PID-ns joining requires the caller to fork afterwards. The bootstrap runs before any Kotlin code, so it can join a mount namespace by path, join a PID namespace before forking stage-2, and unshare the rest of the namespaces.

Stage-2 starts as a fresh single-thread process in the new namespaces. The Kotlin runtime takes over from there.

## Modules

For the directory tree that maps each component onto the source layout, see [Contributing → Repo layout](contributing.md#repo-layout).
