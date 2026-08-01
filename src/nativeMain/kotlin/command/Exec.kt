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
import spec.Spec
import spec.loadSpec
import state.ContainerStatus
import state.State
import state.loadState
import state.refreshStatus
import syscall.Syscall
import utils.FileSystem

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
 * Positional argv only; no separate process.json, no TTY, and no --user /
 * --cwd / --env overrides yet — the values come from the bundle's
 * config.json.
 *
 * Process topology (three processes, mirroring runc's exec shape):
 * - parent: multithreaded runtime CLI. Loads state/spec, opens the ns fds,
 *   forwards the seccomp notify FD if needed, waitpid's and propagates the
 *   exit code.
 * - child: forked, therefore single-threaded — a requirement for
 *   setns(CLONE_NEWNS) etc., which the kernel rejects from multithreaded
 *   processes. Joins the cgroup (before any setns, while the host cgroupfs
 *   view is still visible and we still hold the privileges to write it),
 *   then joins the namespaces.
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
) {
    if (args.isEmpty()) {
        Logger.error("exec: at least one command argument is required")
        exit(1)
    }

    var state =
        try {
            loadState(fs, rootPath, containerId)
        } catch (e: Exception) {
            Logger.error("exec: failed to load state for $containerId: ${e.message}")
            exit(1)
            return
        }
    state = state.refreshStatus()
    if (!state.status.canExec()) {
        Logger.error("exec: cannot exec into container in '${state.status.value}' state; container must be running")
        exit(1)
    }
    val initPid =
        state.pid ?: run {
            Logger.error("exec: container has no init PID; is it running?")
            exit(1)
            return
        }

    // The bundle's config.json is the source of the process profile (user,
    // capabilities, seccomp, env, cwd, ...) and of the namespace list.
    val spec =
        try {
            loadSpec(fs, "${state.bundle}/config.json")
        } catch (e: Exception) {
            Logger.error("exec: failed to load spec from ${state.bundle}/config.json: ${e.message}")
            exit(1)
            return
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

    // When the seccomp profile uses SCMP_ACT_NOTIFY, the notify FD must reach
    // the OCI listener socket — a HOST path the grandchild can no longer
    // connect to once inside the container's mount namespace. Reuse the
    // create path's machinery: the grandchild ships the FD over a channel
    // (like init does to the main process) and this parent forwards it.
    // The pid pipe carries the grandchild's host-perspective pid, which the
    // listener protocol needs in the container state JSON.
    val usesNotify = spec.linux?.seccomp?.let { seccompUsesNotify(it) } ?: false
    var notifyMainSender: MainSender? = null
    var notifyMainReceiver: MainReceiver? = null
    var notifyInitSender: InitSender? = null
    var notifyInitReceiver: InitReceiver? = null
    val pidPipe = IntArray(2)
    if (usesNotify) {
        val (ms, mr) = mainChannel()
        val (is_, ir) = initChannel()
        notifyMainSender = ms
        notifyMainReceiver = mr
        notifyInitSender = is_
        notifyInitReceiver = ir
        pidPipe.usePinned { pinned ->
            if (pipe(pinned.addressOf(0)) != 0) {
                Logger.error("exec: pipe() failed (errno=$errno)")
                exit(1)
            }
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
            runExecChild(syscall, cgroup, cgroupPath, spec, args, joins, nsFds, usesNotify, pidPipe, notifyMainSender, notifyInitReceiver)
        } catch (t: Throwable) {
            fprintf(stderr, "exec: %s\n", t.message ?: "unknown error")
        }
        _exit(1)
    }

    // Parent: forward the seccomp notify FD if the profile uses one, then
    // reap the child and propagate the exit code.
    nsFds.values.forEach { close(it) }
    if (usesNotify) {
        close(pidPipe[1])
        notifyMainSender?.close()
        notifyInitReceiver?.close()
        forwardSeccompNotify(spec, state, containerId, pidPipe[0], notifyMainReceiver!!, notifyInitSender!!)
        close(pidPipe[0])
        notifyMainReceiver.close()
        notifyInitSender.close()
    }
    memScoped {
        val status = alloc<IntVar>()
        waitpid(pid, status.ptr, 0)
        exit(exitCodeFromWaitStatus(status.value))
    }
}

/**
 * Child process body: join cgroup and namespaces, fork the grandchild that
 * becomes the user command, wait for it and propagate its exit code.
 * Runs single-threaded (fresh fork); exits via _exit only.
 */
@OptIn(ExperimentalForeignApi::class)
private fun runExecChild(
    syscall: Syscall,
    cgroup: Cgroup,
    cgroupPath: String,
    spec: Spec,
    args: List<String>,
    joins: List<NsJoin>,
    nsFds: Map<String, Int>,
    usesNotify: Boolean,
    pidPipe: IntArray,
    notifyMainSender: MainSender?,
    notifyInitReceiver: InitReceiver?,
) {
    if (usesNotify) close(pidPipe[0])

    // Join the container's cgroup BEFORE any setns: the write needs the host
    // cgroupfs view (gone after setns(mnt)) and host-side privileges (gone
    // after setns(user)). cgroup.procs migration moves only the named
    // process, so this must also happen before forking the grandchild, which
    // then inherits the membership.
    cgroup.addProcess(0, cgroupPath)

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
        try {
            runExecGrandchild(syscall, spec, args, notifyMainSender, notifyInitReceiver)
        } catch (t: Throwable) {
            fprintf(stderr, "exec: setup failed: %s\n", t.message ?: "unknown error")
        }
        _exit(1)
    }

    // Report the grandchild's pid to the parent for the seccomp listener
    // protocol. This process never joined the pid namespace itself (setns
    // only affects children), so fork() returned the host-perspective pid.
    if (usesNotify) {
        memScoped {
            val gcPid = alloc<IntVar>()
            gcPid.value = grandchild
            write(pidPipe[1], gcPid.ptr, sizeOf<IntVar>().toULong())
        }
        close(pidPipe[1])
    }
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
    args: List<String>,
    notifyMainSender: MainSender?,
    notifyInitReceiver: InitReceiver?,
) {
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
    syscall.closeRange(0)

    // rlimits dead-last: a low RLIMIT_AS applied any earlier could abort the
    // Kotlin/Native runtime itself before execve. The user process picks the
    // limits up across exec.
    syscall.applyRlimits(0, spec.process.rlimits)

    memScoped {
        val argv = allocArray<CPointerVar<ByteVar>>(args.size + 1)
        args.forEachIndexed { i, a -> argv[i] = a.cstr.ptr }
        argv[args.size] = null
        execvp(args[0], argv)
        fprintf(stderr, "exec: execvp(%s) failed: %s\n", args[0], strerror(errno))
        _exit(if (errno == ENOENT) 127 else 126)
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
    pidPipeReadFd: Int,
    mainReceiver: MainReceiver,
    initSender: InitSender,
) {
    try {
        val notifyFd = mainReceiver.waitForSeccompRequest()
        Logger.debug("exec: received seccomp notify FD: $notifyFd")

        val gcPid =
            memScoped {
                val buf = alloc<IntVar>()
                if (read(pidPipeReadFd, buf.ptr, sizeOf<IntVar>().toULong()) != sizeOf<IntVar>()) {
                    throw Exception("failed to read exec'd process pid from child")
                }
                buf.value
            }

        val listenerPath = spec.linux?.seccomp?.listenerPath
        if (listenerPath != null) {
            val containerState =
                State(
                    ociVersion = spec.ociVersion,
                    id = containerId,
                    status = ContainerStatus.RUNNING,
                    pid = gcPid,
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
