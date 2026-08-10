package command

import cgroup.Cgroup
import channel.InitReceiver
import channel.InitSender
import channel.MainReceiver
import channel.MainSender
import channel.initChannel
import channel.mainChannel
import config.loadKontainerConfig
import kotlinx.cinterop.*
import logger.Logger
import namespace.NsJoin
import namespace.nsJoinList
import platform.posix.*
import process.applyProcessEnv
import process.applyProcessSecurity
import process.syncSeccompNotifyFd
import seccomp.seccompUsesNotify
import seccomp.sendToSeccompListener
import spec.Process
import spec.Spec
import spec.User
import spec.loadSpec
import state.ContainerStatus
import state.State
import state.loadState
import state.refreshStatus
import syscall.Syscall
import utils.FileSystem
import utils.JsonCodec

/**
 * Exec command — run an additional process inside a running container by
 * joining its namespaces via setns(2) and execve(2)'ing the user's command.
 *
 * The new process gets the SAME spec.process treatment as the container's
 * init process (applyProcessSecurity / applyProcessEnv): uid/gid and
 * supplementary groups, capabilities, seccomp (including SCMP_ACT_NOTIFY
 * forwarding to the OCI listener), no_new_privileges, umask, rlimits,
 * AppArmor/SELinux labels, spec env and cwd. It is also moved into the
 * container's cgroup so resource limits apply.
 *
 * Supports `--process` (`-p`) to load the process spec from a JSON file
 * (the OCI "process.json" convention), or positional command args to build
 * a process spec from the bundle's config.json with overridden args.
 * `--pid-file` writes the exec'd process's host-perspective PID.
 * `--detach` (`-d`) returns immediately instead of waiting for the exec'd
 * process to exit.  No TTY and no --user / --cwd / --env overrides yet —
 * non-args values come from process.json or the bundle's config.json.
 *
 * Process topology (three processes, mirroring runc's exec shape):
 * - parent: multithreaded runtime CLI. Loads state/spec, opens the ns fds,
 *   attaches the child to the container's cgroup and raises its hard
 *   rlimits (host-context work: the host cgroupfs view and
 *   CAP_SYS_RESOURCE in the initial user ns are both unavailable once the
 *   child has joined the container's namespaces — and doing it here keeps
 *   allocation-heavy Kotlin out of the forked child, where the runtime is
 *   not fork-safe), forwards the seccomp notify FD if needed, waitpid's
 *   and propagates the exit code.
 * - child: forked, therefore single-threaded — a requirement for
 *   setns(CLONE_NEWNS) etc., which the kernel rejects from multithreaded
 *   processes. Waits for the parent's cgroup/rlimit setup over a pipe
 *   (both are inherited at fork, so they must precede the grandchild
 *   fork), then joins the namespaces.
 * - grandchild: setns(CLONE_NEWPID) only puts CHILDREN of the caller into
 *   the pid namespace, so the child forks once more; the grandchild is the
 *   process that is actually born inside the container's pid ns. It applies
 *   the spec.process restrictions and execvp's the user command in place.
 */
@OptIn(ExperimentalForeignApi::class)
fun exec(
    syscall: Syscall,
    fs: FileSystem,
    cgroup: Cgroup,
    rootPath: String,
    containerId: String,
    args: List<String>,
    processSpecPath: String? = null,
    pidFilePath: String? = null,
    detach: Boolean = false,
    tty: Boolean = false,
    cwdOverride: String? = null,
    envOverrides: List<String> = emptyList(),
    userOverride: String? = null,
    additionalGids: List<String> = emptyList(),
    preserveFds: Int = 0,
    cgroupOverride: List<String> = emptyList(),
) {
    if (processSpecPath != null && args.isNotEmpty()) {
        Logger.error("exec: --process cannot be combined with a positional command")
        exit(1)
    }
    if (processSpecPath == null && args.isEmpty()) {
        Logger.error("exec: a command is required (positional args or --process)")
        exit(1)
    }

    // Validate --pid-file path early (runc exits 255 for invalid path)
    if (pidFilePath != null) {
        val parentDir = pidFilePath.substringBeforeLast('/', "")
        if (parentDir.isNotEmpty() && access(parentDir, F_OK) != 0) {
            Logger.error("exec: failed to create pid file: open $pidFilePath: no such file or directory")
            _exit(255)
        }
    }

    var state =
        try {
            loadState(fs, rootPath, containerId)
        } catch (e: Exception) {
            Logger.error("exec: failed to load state for $containerId: ${e.message}")
            _exit(255)
            @Suppress("UNREACHABLE_CODE")
            return
        }
    state = state.refreshStatus()
    if (!state.status.canExec()) {
        Logger.error("exec: cannot exec into container in '${state.status.value}' state; container must be created or running")
        exit(1)
    }
    val initPid =
        state.pid ?: run {
            Logger.error("exec: container has no init PID; is it running?")
            exit(1)
            return
        }

    // The bundle's config.json is the authoritative source of the namespace
    // list and the base process profile.  When --process is given, the
    // process profile comes from the JSON file instead — the bundle spec
    // is still needed for namespaces, seccomp architecture, etc.
    val spec =
        try {
            loadSpec(fs, "${state.bundle}/config.json")
        } catch (e: Exception) {
            Logger.error("exec: failed to load spec from ${state.bundle}/config.json: ${e.message}")
            exit(1)
            return
        }

    var execProcess =
        if (processSpecPath != null) {
            try {
                val p = JsonCodec.loadFromFile<Process>(fs, processSpecPath)
                if (p.args.isEmpty()) {
                    Logger.error("exec: process.json has no args")
                    exit(1)
                    return
                }
                p
            } catch (e: Exception) {
                Logger.error("exec: failed to load process spec from $processSpecPath: ${e.message}")
                exit(1)
                return
            }
        } else {
            spec.process.copy(args = args)
        }

    // Apply CLI overrides to the exec process spec
    if (cwdOverride != null) {
        execProcess = execProcess.copy(cwd = cwdOverride)
    }
    if (envOverrides.isNotEmpty()) {
        val existingEnv = execProcess.env?.toMutableList() ?: mutableListOf()
        existingEnv.addAll(envOverrides)
        execProcess = execProcess.copy(env = existingEnv)
    }
    if (userOverride != null) {
        val parts = userOverride.split(":")
        val uid = parts[0].toUIntOrNull() ?: 0u
        val gid = if (parts.size > 1) parts[1].toUIntOrNull() ?: 0u else uid
        val existingAdditionalGids = execProcess.user.additionalGids
        execProcess = execProcess.copy(user = User(uid = uid, gid = gid, additionalGids = existingAdditionalGids))
    }
    if (additionalGids.isNotEmpty()) {
        val existingGids = execProcess.user.additionalGids?.toMutableList() ?: mutableListOf()
        additionalGids.forEach { g ->
            g.toUIntOrNull()?.let { existingGids.add(it) }
        }
        execProcess = execProcess.copy(user = execProcess.user.copy(additionalGids = existingGids))
    }
    if (tty) {
        execProcess = execProcess.copy(terminal = true)
    }

    // Build a spec overlay that carries the exec process profile but
    // preserves the bundle's linux/namespace/seccomp configuration.
    val execSpec = spec.copy(process = execProcess)

    // Validate --cgroup subcgroup path (reject path traversal)
    if (cgroupOverride.isNotEmpty()) {
        val subPath = cgroupOverride.first()
        if (subPath.contains("..")) {
            Logger.error("exec: bad sub cgroup path \"$subPath\": .. is not a sub cgroup path")
            exit(1)
            return
        }
    }

    // The resolved cgroup path was persisted at create time; without it we
    // cannot place the exec'd process under the container's resource limits.
    val cgroupPath =
        try {
            loadKontainerConfig(fs, rootPath, containerId).cgroupPath
        } catch (e: Exception) {
            Logger.error("exec: failed to load kontainer config: ${e.message}")
            exit(1)
            return
        }
    if (cgroupPath == null) {
        Logger.error("exec: no cgroup path recorded for container $containerId")
        exit(1)
        return
    }

    // Join the namespaces the SPEC defines, not whatever exists under
    // /proc/<pid>/ns/ (an entry exists there for every type; setns into our
    // own user namespace would fail with EINVAL). Opened with O_CLOEXEC so
    // the fds cannot leak across execvp into the container process; setns
    // works fine on CLOEXEC fds, and the child closes them explicitly.
    val joins = nsJoinList(spec.linux?.namespaces)
    val nsFds = mutableMapOf<String, Int>()
    for (nsj in joins) {
        val path = "/proc/$initPid/ns/${nsj.procName}"
        val fd = open(path, O_RDONLY or O_CLOEXEC)
        if (fd < 0) {
            Logger.error("exec: failed to open $path (errno=$errno); refusing to run partially outside the container")
            nsFds.values.forEach { close(it) }
            exit(1)
        }
        nsFds[nsj.procName] = fd
    }

    // The child must not fork the grandchild until the parent has attached it
    // to the container's cgroup and raised its hard rlimits — both are
    // inherited at fork time. A byte over this pipe is the go signal; EOF
    // without the byte tells the child the setup failed and it must abort.
    val setupPipe = IntArray(2)
    setupPipe.usePinned { pinned ->
        if (pipe(pinned.addressOf(0)) != 0) {
            Logger.error("exec: pipe() failed (errno=$errno)")
            exit(1)
        }
    }

    // The seccomp listener protocol (SCMP_ACT_NOTIFY) needs the grandchild's
    // host-perspective PID; pid-file also needs it. The pipe is therefore
    // always created — the child writes the grandchild PID, the parent reads
    // it after the fork.
    val usesNotify = execSpec.linux?.seccomp?.let { seccompUsesNotify(it) } ?: false
    var notifyMainSender: MainSender? = null
    var notifyMainReceiver: MainReceiver? = null
    var notifyInitSender: InitSender? = null
    var notifyInitReceiver: InitReceiver? = null
    if (usesNotify) {
        val (ms, mr) = mainChannel()
        val (is_, ir) = initChannel()
        notifyMainSender = ms
        notifyMainReceiver = mr
        notifyInitSender = is_
        notifyInitReceiver = ir
    }
    val pidPipe = IntArray(2)
    pidPipe.usePinned { pinned ->
        if (pipe(pinned.addressOf(0)) != 0) {
            Logger.error("exec: pipe() failed (errno=$errno)")
            exit(1)
        }
    }

    val pid = fork()
    if (pid < 0) {
        Logger.error("exec: fork() failed (errno=$errno)")
        exit(1)
    }
    if (pid == 0) {
        // Child (single-threaded). Never let a Kotlin exception unwind past
        // the fork boundary — the runtime's default handler would exit(),
        // flushing stdio duplicated from the parent; only _exit() here.
        try {
            runExecChild(syscall, execSpec, joins, nsFds, setupPipe, usesNotify, pidPipe, notifyMainSender, notifyInitReceiver, preserveFds)
        } catch (t: Throwable) {
            fprintf(stderr, "exec: %s\n", t.message ?: "unknown error")
        }
        _exit(1)
    }

    // Parent: attach the child to the container's cgroup and raise its hard
    // rlimits, both from the host context — the host cgroupfs view is gone
    // after the child's setns(mnt), raising a hard limit needs
    // CAP_SYS_RESOURCE in the initial user ns (gone after setns(user)), and
    // doing it here rather than in the child keeps cgroupfs writes, logging
    // and exception machinery out of the forked child of this multithreaded
    // process, where the Kotlin runtime is not fork-safe.
    nsFds.values.forEach { close(it) }
    close(setupPipe[0])
    val setupOk =
        try {
            cgroup.addProcess(pid, cgroupPath)
            syscall.raiseRlimits(pid, execSpec.process.rlimits)
            true
        } catch (e: Exception) {
            Logger.error("exec: failed to set up child (cgroup $cgroupPath): ${e.message}")
            false
        }
    if (setupOk) {
        memScoped {
            val goByte = alloc<ByteVar>()
            goByte.value = 1
            if (write(setupPipe[1], goByte.ptr, 1u) != 1L) {
                Logger.error("exec: failed to signal child (errno=$errno)")
            }
        }
    }
    close(setupPipe[1])

    // Read the grandchild's host-perspective PID from the child.
    close(pidPipe[1])
    val grandchildPid =
        memScoped {
            val buf = alloc<IntVar>()
            if (read(pidPipe[0], buf.ptr, sizeOf<IntVar>().toULong()) == sizeOf<IntVar>()) {
                buf.value
            } else {
                -1
            }
        }
    close(pidPipe[0])

    if (pidFilePath != null && grandchildPid > 0) {
        try {
            fs.writeTextFile(pidFilePath, grandchildPid.toString())
        } catch (e: Exception) {
            Logger.warn("exec: failed to write pid file $pidFilePath: ${e.message}")
        }
    }

    // Forward the seccomp notify FD if the profile uses one.
    if (usesNotify) {
        notifyMainSender?.close()
        notifyInitReceiver?.close()
        forwardSeccompNotify(execSpec, state, containerId, grandchildPid, notifyMainReceiver!!, notifyInitSender!!)
        notifyMainReceiver.close()
        notifyInitSender.close()
    }

    if (detach) {
        exit(0)
    }

    memScoped {
        val status = alloc<IntVar>()
        waitpid(pid, status.ptr, 0)
        exit(exitCodeFromWaitStatus(status.value))
    }
}

/**
 * Child process body: wait for the parent's cgroup/rlimit setup, join the
 * namespaces, fork the grandchild that becomes the user command, wait for
 * it and propagate its exit code.
 * Runs single-threaded (fresh fork); exits via _exit only.
 */
@OptIn(ExperimentalForeignApi::class)
private fun runExecChild(
    syscall: Syscall,
    spec: Spec,
    joins: List<NsJoin>,
    nsFds: Map<String, Int>,
    setupPipe: IntArray,
    usesNotify: Boolean,
    pidPipe: IntArray,
    notifyMainSender: MainSender?,
    notifyInitReceiver: InitReceiver?,
    preserveFds: Int = 0,
) {
    close(pidPipe[0])
    close(setupPipe[1])

    Logger.debug("nsexec container setup")

    // Block until the parent has attached us to the container's cgroup and
    // raised our hard rlimits (host-context work — see the parent). Both are
    // inherited at fork, so they must be in place before the grandchild
    // fork below. One byte = go; EOF = the parent failed and we must not
    // run the command outside the container's limits.
    memScoped {
        val goByte = alloc<ByteVar>()
        if (read(setupPipe[0], goByte.ptr, 1u) != 1L) {
            fprintf(stderr, "exec: parent failed to set up cgroup/rlimits\n")
            _exit(1)
        }
    }
    close(setupPipe[0])

    // Join order matters: user first (grants the capabilities inside the
    // container's userns that authorize the remaining joins), pid last.
    // The CLONE_NEW* nstype guard makes the kernel verify each fd is the
    // namespace type we think it is.
    for (nsj in joins) {
        val fd = nsFds[nsj.procName] ?: continue
        if (syscall.setns(fd, nsj.cloneFlag) != 0) {
            fprintf(stderr, "exec: setns(%s) failed: %s\n", nsj.ociType, strerror(errno))
            _exit(1)
        }
    }
    nsFds.values.forEach { close(it) }

    val grandchild = fork()
    if (grandchild < 0) _exit(1)
    if (grandchild == 0) {
        close(pidPipe[1])
        try {
            runExecGrandchild(syscall, spec, notifyMainSender, notifyInitReceiver, preserveFds)
        } catch (t: Throwable) {
            fprintf(stderr, "exec: setup failed: %s\n", t.message ?: "unknown error")
        }
        _exit(1)
    }

    // Report the grandchild's host-perspective PID to the parent. This is
    // always sent (used for pid-file and seccomp notify), not only when
    // usesNotify is set.  setns only affects children, so fork() returned
    // the host-perspective pid.
    memScoped {
        val gcPid = alloc<IntVar>()
        gcPid.value = grandchild
        write(pidPipe[1], gcPid.ptr, sizeOf<IntVar>().toULong())
    }
    close(pidPipe[1])
    notifyMainSender?.close()
    notifyInitReceiver?.close()

    memScoped {
        val status = alloc<IntVar>()
        waitpid(grandchild, status.ptr, 0)
        _exit(exitCodeFromWaitStatus(status.value))
    }
}

/**
 * Grandchild process body: the process that becomes the user command.
 * Already inside all of the container's namespaces (including pid, by being
 * born after the child's setns). Applies the shared spec.process setup and
 * execvp's in place. Exits via _exit only.
 */
@OptIn(ExperimentalForeignApi::class)
private fun runExecGrandchild(
    syscall: Syscall,
    spec: Spec,
    notifyMainSender: MainSender?,
    notifyInitReceiver: InitReceiver?,
    preserveFds: Int = 0,
) {
    Logger.debug("child process in init()")

    val args = spec.process.args
    // Unlike init (which warns for bundle compatibility), a cwd that doesn't
    // exist inside the container is a hard error for exec — runc parity.
    val cwd = spec.process.cwd
    if (syscall.chdir(cwd) != 0) {
        fprintf(stderr, "exec: chdir(%s) failed: %s\n", cwd, strerror(errno))
        _exit(1)
    }

    applyProcessSecurity(syscall, spec.process, spec.linux?.seccomp) { notifyFd ->
        if (notifyMainSender != null && notifyInitReceiver != null) {
            // Same synchronization as init: blocks until the parent has
            // forwarded the FD, so the listener is attached before any user
            // code runs.
            syncSeccompNotifyFd(notifyFd, notifyMainSender, notifyInitReceiver)
        } else {
            throw IllegalStateException("seccomp notify FD returned but no forwarding channel was set up (BUG)")
        }
    }
    notifyMainSender?.close()
    notifyInitReceiver?.close()

    applyProcessEnv(spec.process.env ?: emptyList())

    // Flag every inherited runtime fd CLOEXEC so nothing leaks into the user
    // process (CVE-2024-21626 hygiene).
    syscall.closeRange(preserveFds)

    // rlimits dead-last: a low RLIMIT_AS applied any earlier could abort the
    // Kotlin/Native runtime itself before execve. The user process picks the
    // limits up across exec. Raising a hard limit would EPERM here (that
    // needs CAP_SYS_RESOURCE in the initial user ns), which is why the
    // parent already raised them via prlimit from the host context; this
    // pass only sets the exact spec values, which is unprivileged.
    syscall.applyRlimits(0, spec.process.rlimits)

    Logger.debug("setns_init: about to exec")

    memScoped {
        val argv = allocArray<CPointerVar<ByteVar>>(args.size + 1)
        args.forEachIndexed { i, a -> argv[i] = a.cstr.ptr }
        argv[args.size] = null
        execvp(args[0], argv)
        // runc surfaces execve errors as exit code 255 (not the traditional
        // 127/126) so the caller can distinguish "runtime error" from
        // "container process exited with code N".
        fprintf(stderr, "exec %s: %s\n", args[0], strerror(errno))
        _exit(255)
    }
}

/**
 * Parent-side seccomp notify forwarding — the exec counterpart of the block
 * in MainProcess: receive the notify FD from the grandchild, send it with
 * the container process state to the OCI listener socket, and ack so the
 * grandchild can proceed to execvp.
 */
@OptIn(ExperimentalForeignApi::class)
private fun forwardSeccompNotify(
    spec: Spec,
    state: State,
    containerId: String,
    grandchildPid: Int,
    mainReceiver: MainReceiver,
    initSender: InitSender,
) {
    try {
        val notifyFd = mainReceiver.waitForSeccompRequest()
        Logger.debug("exec: received seccomp notify FD: $notifyFd")

        val listenerPath = spec.linux?.seccomp?.listenerPath
        if (listenerPath != null) {
            val containerState =
                State(
                    ociVersion = spec.ociVersion,
                    id = containerId,
                    status = ContainerStatus.RUNNING,
                    pid = grandchildPid,
                    bundle = state.bundle,
                    annotations = spec.annotations,
                )
            sendToSeccompListener(listenerPath, containerState, notifyFd)
            Logger.debug("exec: forwarded seccomp notify FD to listener")
        } else {
            Logger.warn("exec: seccomp notify FD received but no listenerPath specified")
        }
        close(notifyFd)

        initSender.seccompNotifyDone()
    } catch (e: Exception) {
        // Most likely the grandchild died before shipping the FD; the
        // waitpid in the caller will surface its exit code.
        Logger.warn("exec: seccomp notify forwarding failed: ${e.message}")
    }
}

/** Map a waitpid status to a shell-style exit code (128 + signal for signal deaths). */
private fun exitCodeFromWaitStatus(status: Int): Int =
    if ((status and 0x7f) == 0) {
        (status shr 8) and 0xff
    } else {
        128 + (status and 0x7f)
    }
