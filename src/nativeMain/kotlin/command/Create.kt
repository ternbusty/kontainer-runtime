package command

import bootstrap.kontainer_clone_into_cgroup
import bootstrap.kontainer_environ
import bootstrap.kontainer_execve
import cgroup.Cgroup
import cgroup.CgroupV2
import cgroup.DeviceCgroup
import channel.SocketNotifyListener
import channel.initChannel
import channel.mainChannel
import console.connectConsoleSocket
import exeseal.sealedBinaryFd
import kotlinx.cinterop.*
import logger.Logger
import namespace.calculateCloneFlags
import platform.posix.*
import process.runMainProcess
import rootfs.validateSysctls
import seccomp.validateSeccompFlags
import spec.loadSpec
import state.containerExists
import state.getContainerDir
import state.getNotifySocketPath
import syscall.Syscall
import utils.FileSystem

/**
 * Create command - Creates a new container
 *
 * @param rootPath Root directory for container state
 * @param containerId Container ID
 * @param bundlePath Path to OCI bundle directory (default: current directory)
 * @param pidFile Optional path to write the Stage-2 (init process) PID
 */
@OptIn(ExperimentalForeignApi::class)
fun create(
    syscall: Syscall,
    fs: FileSystem,
    cgroup: Cgroup,
    rootPath: String,
    containerId: String,
    bundlePath: String = ".",
    pidFile: String? = null,
    consoleSocket: String? = null,
    pidfdSocket: String? = null,
    noPivot: Boolean = false,
): Unit =
    memScoped {
        if (containerExists(fs, rootPath, containerId)) {
            Logger.error("container $containerId already exists")
            exit(1)
        }

        // Canonicalize the bundle path. The OCI runtime spec requires the bundle
        // field in the container state to be an absolute path, and downstream
        // consumers (hooks, /proc/<pid>/root lookups, debuggers) rely on it.
        val absBundle =
            allocArray<ByteVar>(4096).let { buf ->
                if (realpath(bundlePath, buf) == null) {
                    Logger.error("failed to resolve bundle path '$bundlePath' (errno=$errno)")
                    exit(1)
                    return@memScoped
                }
                buf.toKString()
            }

        val configPath = "$absBundle/config.json"

        Logger.info("creating container: $containerId")
        Logger.debug("loading spec from $configPath")

        val spec =
            try {
                loadSpec(fs, configPath)
            } catch (e: Exception) {
                Logger.error("failed to load spec: ${e.message ?: "unknown error"}")
                exit(1)
                return
            }

        Logger.debug("loaded spec version ${spec.ociVersion}")

        // Fail-closed sysctl validation: reject any key that is not on the
        // OCI/runc allowlist, or that requires a namespace the spec does not
        // create (e.g. net.* without a new network namespace).
        val sysctlErrors = validateSysctls(spec.linux?.sysctl, spec.linux?.namespaces)
        if (sysctlErrors.isNotEmpty()) {
            for (err in sysctlErrors) {
                Logger.error(err)
            }
            exit(1)
        }

        // Reject SCHED_DEADLINE combined with specific CPUs (cpuset).
        // sched_setattr(2) returns EPERM if the CPU affinity doesn't include
        // all CPUs, so this combination can never work.  Match runc's error.
        if (spec.process.scheduler != null && spec.linux
                ?.resources
                ?.cpu
                ?.cpus != null
        ) {
            Logger.error("process scheduler can't be used together with AllowedCPUs")
            exit(1)
        }

        // Get absolute path of rootfs
        val rootfsPath =
            if (spec.root.path.startsWith("/")) {
                spec.root.path
            } else {
                "$absBundle/${spec.root.path}"
            }

        Logger.debug("rootfs path: $rootfsPath")
        Logger.debug("main: pid=${getpid()}")

        // Create 2 channels for inter-process communication
        // Main ↔ Stage-2 (init / PID 1)
        val (mainSender, mainReceiver) = mainChannel()
        val (initSender, initReceiver) = initChannel()

        // The notify socket lives inside the root-owned container state
        // directory (not /tmp), so create that directory before binding.
        fs.createDirectories(getContainerDir(rootPath, containerId))
        val notifySocketPath = getNotifySocketPath(rootPath, containerId)

        // Create NotifyListener before forking (will be inherited by child processes)
        val notifyListener =
            try {
                SocketNotifyListener(notifySocketPath)
            } catch (e: Exception) {
                Logger.error("failed to create notify listener: ${e.message ?: "unknown"}")
                exit(1)
                return
            }

        // Fork and exec to trigger bootstrap constructor
        // The bootstrap constructor (in C) will:
        //   - Unshare namespaces (Stage-1)
        //   - Fork Stage-2 (init process / PID 1)
        //   - Exit Stage-1 immediately
        // This ensures all forks happen before Kotlin runtime initialization, avoiding GC deadlock

        // Create sync socketpair for parent-child synchronization
        val syncFds = IntArray(2)
        syncFds.usePinned { pinned ->
            if (socketpair(AF_UNIX, SOCK_STREAM, 0, pinned.addressOf(0)) < 0) {
                perror("socketpair")
                Logger.error("Failed to create sync socketpair")
                notifyListener.close()
                exit(1)
            }
        }
        Logger.debug("created sync socketpair: parent_fd=${syncFds[0]}, child_fd=${syncFds[1]}")

        // Calculate clone flags from OCI spec namespaces
        // These flags will be passed to bootstrap.c via environment variable
        val cloneFlags = calculateCloneFlags(spec.linux?.namespaces)
        Logger.debug("calculated clone_flags: 0x${cloneFlags.toString(16)}")

        // Determine the exec target for the bootstrap child.
        //
        // Optimized path (sealedBinaryFd >= 0): exec directly from the sealed
        // fd via /proc/self/fd/<N>.  This combines the CVE-2019-5736 seal and
        // the bootstrap exec into a single execv — the child is born with
        // /proc/self/exe → sealed copy, and bootstrap.c's constructor runs
        // in that same exec.  No main-process re-exec needed.
        //
        // Fallback path (sealedBinaryFd < 0): the main process was already
        // re-exec'd from a sealed copy by ensureSelfCloned(), so
        // /proc/self/exe is safe to use directly.
        val exePath =
            if (sealedBinaryFd >= 0) {
                "/proc/self/fd/$sealedBinaryFd"
            } else {
                "/proc/self/exe"
            }
        val exePathBuf = exePath.cstr.ptr
        Logger.debug("executable path (for bootstrap exec): $exePath")

        // Validate seccomp flags before forking — after fork, errors from
        // the child process are not visible in the parent's output.
        spec.linux?.seccomp?.let { validateSeccompFlags(it) }

        // ------------------------------------------------------------------
        // Prepare the container cgroup BEFORE spawning Stage-1, so that Stage-1
        // (and Stage-2, which Stage-1 clones and which inherits the cgroup) can
        // be created directly inside it with clone3(CLONE_INTO_CGROUP).
        // Migrating an already-running task via a cgroup.procs write costs
        // 4-8 ms on cgroup v2 (task migration synchronisation in the kernel);
        // being born in the cgroup costs nothing. runc does the same through
        // Go's SysProcAttr.UseCgroupFD.
        // ------------------------------------------------------------------
        val resolvedCgroupPath = CgroupV2.resolveCgroupPath(spec.linux?.cgroupsPath, containerId)
        cgroup.setup(pid = null, cgroupPath = resolvedCgroupPath, resources = spec.linux?.resources, deferPids = true)
        // Attach the eBPF device-cgroup program ONCE, right after the cgroup
        // directory exists. It applies to every process that is (or will be) in
        // the cgroup, so it must not be attached again later — a second
        // BPF_PROG_ATTACH would stack a duplicate filter.
        //
        // DeviceCgroup.apply() appends DEFAULT_ALLOWED_DEVICES which include
        // wildcard mknod-allow rules for all char/block devices (matching
        // runc's AllowedDevices), so the init process can mknod
        // spec.linux.devices[] entries even under a deny-all rule. Read/write
        // access is NOT auto-allowed — only mknod.
        val deviceRules = spec.linux?.resources?.devices
        if (!deviceRules.isNullOrEmpty()) {
            DeviceCgroup.apply("/sys/fs/cgroup/${resolvedCgroupPath.removePrefix("/")}", deviceRules)
        }
        val cgroupDirFd =
            if (resolvedCgroupPath.isNotEmpty()) {
                open("/sys/fs/cgroup/${resolvedCgroupPath.removePrefix("/")}", O_RDONLY or O_DIRECTORY or O_CLOEXEC)
            } else {
                -1
            }

        // ------------------------------------------------------------------
        // Build the child's argv/envp HERE, in the parent. The child (whether
        // created by clone3 or fork) only closes fds and calls execve. A raw
        // clone3 does not run pthread_atfork handlers and this process is
        // multi-threaded (GC threads), so allocating memory or calling
        // setenv/getenv in the child could deadlock on a lock another thread
        // held at clone time.
        // ------------------------------------------------------------------
        val childEnv = mutableListOf<String>()
        // Clone flags as hex string (e.g., "10000000" for CLONE_NEWUSER)
        childEnv += "_KONTAINER_CLONE_FLAGS=${cloneFlags.toString(16)}"
        // Forward debug logging to bootstrap.c — it checks for the existence
        // of _KONTAINER_DEBUG before emitting diagnostics.
        val logLevel = getenv("KONTAINER_LOG_LEVEL")?.toKString()?.uppercase()
        if (logLevel == "DEBUG" || logLevel == "TRACE") {
            childEnv += "_KONTAINER_DEBUG=1"
        }
        childEnv += "_KONTAINER_IS_BOOTSTRAP=1"
        childEnv += "_KONTAINER_SYNCPIPE=${syncFds[1]}"
        // Channel FDs for the init process (Stage-2)
        childEnv += "_KONTAINER_MAIN_SENDER_FD=${mainSender.fd()}"
        childEnv += "_KONTAINER_INIT_RECEIVER_FD=${initReceiver.fd()}"
        childEnv += "_KONTAINER_NOTIFY_LISTENER_FD=${notifyListener.fd()}"
        childEnv += "_KONTAINER_BUNDLE_PATH=$absBundle"
        childEnv += "_KONTAINER_ROOTFS_PATH=$rootfsPath"
        childEnv += "_KONTAINER_NOTIFY_SOCKET=$notifySocketPath"
        childEnv += "_KONTAINER_CONTAINER_ID=$containerId"
        // Log env vars (_KONTAINER_LOG_FILE, _KONTAINER_LOG_FORMAT) are already
        // in our environment (set by KontainerRuntime.run()) and are inherited
        // through envp below.
        if (noPivot) {
            childEnv += "_KONTAINER_NO_PIVOT=1"
        }
        // Console socket: connect NOW, in the host namespace with full
        // credentials, and hand the fd to the init process. Inside a user
        // namespace the init process's effective host UID is the mapped UID
        // (e.g. 100000), which may not be allowed to connect to a socket owned
        // by root. The fd is inherited by the child and closed in the parent
        // after the spawn.
        var consoleSocketFd = -1
        if (consoleSocket != null) {
            consoleSocketFd = connectConsoleSocket(consoleSocket)
            if (consoleSocketFd < 0) {
                Logger.error("failed to connect to console socket: $consoleSocket")
                close(syncFds[0])
                close(syncFds[1])
                if (cgroupDirFd >= 0) close(cgroupDirFd)
                notifyListener.close()
                exit(1)
            }
            childEnv += "_KONTAINER_CONSOLE_SOCKET_FD=$consoleSocketFd"
        }
        // Pidfd socket: same reasoning as the console socket. The init process
        // will open a pidfd for itself and send it over this pre-connected fd.
        var pidfdSocketFd = -1
        if (pidfdSocket != null) {
            pidfdSocketFd = connectConsoleSocket(pidfdSocket)
            if (pidfdSocketFd < 0) {
                Logger.error("failed to connect to pidfd socket: $pidfdSocket")
                close(syncFds[0])
                close(syncFds[1])
                if (cgroupDirFd >= 0) close(cgroupDirFd)
                if (consoleSocketFd >= 0) close(consoleSocketFd)
                notifyListener.close()
                exit(1)
            }
            childEnv += "_KONTAINER_PIDFD_SOCKET_FD=$pidfdSocketFd"
        }
        // Pass any spec.linux.namespaces[].path entries to bootstrap.c so it can
        // setns(2) into existing namespaces before the stage-2 fork. Doing this
        // in the Kotlin runtime is unreliable because the runtime is
        // multi-threaded (the kernel rejects setns into a mount namespace from
        // such a process) and PID namespace join must happen pre-fork. The env
        // var key matches ENV_NS_PATH_PREFIX in bootstrap.c.
        spec.linux?.namespaces?.forEach { ns ->
            val path = ns.path
            if (path.isNullOrEmpty()) return@forEach
            val envKey =
                when (ns.type) {
                    "mount" -> "_KONTAINER_NS_PATH_MOUNT"
                    "network" -> "_KONTAINER_NS_PATH_NETWORK"
                    "uts" -> "_KONTAINER_NS_PATH_UTS"
                    "ipc" -> "_KONTAINER_NS_PATH_IPC"
                    "user" -> "_KONTAINER_NS_PATH_USER"
                    "cgroup" -> "_KONTAINER_NS_PATH_CGROUP"
                    "time" -> "_KONTAINER_NS_PATH_TIME"
                    "pid" -> "_KONTAINER_NS_PATH_PID"
                    else -> return@forEach
                }
            childEnv += "$envKey=$path"
        }
        // envp = our environment + the child-specific entries (which win on key clash)
        val childKeys = childEnv.map { it.substringBefore('=') }.toSet()
        val inheritedEnv = mutableListOf<String>()
        val environ = kontainer_environ()
        if (environ != null) {
            var i = 0
            while (true) {
                val entry = environ[i] ?: break
                inheritedEnv += entry.toKString()
                i++
            }
        }
        val envList = inheritedEnv.filter { it.substringBefore('=') !in childKeys } + childEnv
        val envp = allocArray<CPointerVar<ByteVar>>(envList.size + 1)
        envList.forEachIndexed { idx, entry -> envp[idx] = entry.cstr.ptr }
        envp[envList.size] = null
        val argv = allocArray<CPointerVar<ByteVar>>(3)
        argv[0] = exePathBuf
        argv[1] = "__init__".cstr.ptr
        argv[2] = null

        // ------------------------------------------------------------------
        // Spawn Stage-1. Preferred: clone3(CLONE_INTO_CGROUP) so Stage-1 starts
        // inside the container cgroup. Fallback (kernel < 5.7, or the kernel
        // refuses, e.g. EACCES in some rootless setups): plain fork(); the
        // main process then migrates Stage-2 via cgroup.procs as before.
        //
        // Either way Stage-1 is a child of this process (not CLONE_PARENT):
        // bootstrap.c's clone_parent() then creates Stage-2 with CLONE_PARENT,
        // making Stage-2's parent = Stage-1's parent = this process, so waitpid
        // works for exit-code forwarding in foreground mode (runc's nsexec
        // architecture).
        // ------------------------------------------------------------------
        var stage1InCgroup = false
        var stage1Pid = -1
        if (cgroupDirFd >= 0) {
            stage1Pid = kontainer_clone_into_cgroup(cgroupDirFd)
            if (stage1Pid >= 0) {
                stage1InCgroup = true
            } else {
                Logger.debug("clone3(CLONE_INTO_CGROUP) failed (errno=$errno); falling back to fork() + cgroup.procs")
            }
        }
        if (stage1Pid < 0) {
            stage1Pid = fork()
        }
        when (stage1Pid) {
            -1 -> {
                perror("clone")
                Logger.error("Failed to spawn Stage-1")
                close(syncFds[0])
                close(syncFds[1])
                if (cgroupDirFd >= 0) close(cgroupDirFd)
                if (consoleSocketFd >= 0) close(consoleSocketFd)
                if (pidfdSocketFd >= 0) close(pidfdSocketFd)
                notifyListener.close()
                exit(1)
            }
            0 -> {
                // Child (will become Stage-1): async-signal-safe work only —
                // everything was prepared above. After exec the bootstrap
                // constructor runs and creates Stage-2 before the Kotlin
                // runtime starts. exePath is the sealed binary
                // (/proc/self/fd/<sealedBinaryFd>) when sealing succeeded —
                // CVE-2019-5736 seal and bootstrap exec in a single exec.
                close(syncFds[0])
                kontainer_execve(exePathBuf, argv, envp)
            }
            else -> {
                // Parent process (Create.kt / Main Process):
                // Wait for Stage-1 to complete bootstrap and receive Stage-2 PID

                // Close child-side fds we handed over
                close(syncFds[1])
                if (cgroupDirFd >= 0) close(cgroupDirFd)
                if (consoleSocketFd >= 0) close(consoleSocketFd)
                if (pidfdSocketFd >= 0) close(pidfdSocketFd)

                Logger.debug("spawned Stage-1, PID=$stage1Pid (inCgroup=$stage1InCgroup), waiting for bootstrap to complete")

                runMainProcess(
                    syscall = syscall,
                    fs = fs,
                    cgroup = cgroup,
                    stage1Pid = stage1Pid,
                    syncFd = syncFds[0],
                    spec = spec,
                    containerId = containerId,
                    bundlePath = absBundle,
                    rootPath = rootPath,
                    pidFile = pidFile,
                    notifyListener = notifyListener,
                    mainSender = mainSender,
                    mainReceiver = mainReceiver,
                    initSender = initSender,
                    initReceiver = initReceiver,
                    stage1InCgroup = stage1InCgroup,
                )

                // Reap Stage-1 to avoid zombies. Stage-1 exits after the
                // bootstrap sync protocol completes, which is well before
                // runMainProcess returns. Use WNOHANG as a safety measure —
                // if Stage-1 is somehow still running we don't block.
                val rc = waitpid(stage1Pid, null, WNOHANG)
                if (rc == 0) {
                    // Stage-1 hasn't exited yet — do a blocking wait.
                    waitpid(stage1Pid, null, 0)
                }
                Logger.debug("reaped Stage-1 (pid=$stage1Pid)")
            }
        }
    }
