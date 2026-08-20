package process

import channel.InitReceiver
import channel.MainSender
import channel.NotifyListener
import console.connectConsoleSocket
import console.openPtyFromDevpts
import console.sendMasterViaFd
import console.wireStdio
import kotlinx.cinterop.*
import logger.Logger
import platform.posix.*
import rootfs.applyDeferredPropagation
import rootfs.applyLinuxDevices
import rootfs.applyMaskedPaths
import rootfs.applyReadonlyPaths
import rootfs.applyRootfsPropagation
import rootfs.applySpecMounts
import rootfs.applySysctls
import rootfs.pivotRoot
import rootfs.prepareRootfs
import rootfs.setRootfsReadonly
import spec.Spec
import syscall.Syscall

/**
 * Init process (Stage-2 / PID 1 in container)
 *
 * This process runs as PID 1 in the new PID namespace (if configured).
 * It is created by bootstrap.c Stage-1 using CLONE_PARENT, which makes
 * its parent the main process (not Stage-1).  This allows the main
 * process to waitpid on it for exit-code forwarding.
 *
 * This process:
 * - Runs as PID 1 in the container
 * - Sets up cgroup, user namespace mappings, rootfs, and seccomp
 * - Eventually calls execve() to become the container process
 * - Does NOT fork any additional processes
 */
@OptIn(ExperimentalForeignApi::class)
private fun initProcessInternal(
    syscall: Syscall,
    spec: Spec,
    rootfsPath: String,
    mainSender: MainSender,
    initReceiver: InitReceiver,
    notifyListener: NotifyListener,
): Unit =
    memScoped {
        Logger.setContext("init")
        Logger.debug("started, pid=${getpid()} ppid=${getppid()}")

        // All namespaces (user, mount, network, uts, ipc, pid) are unshared by bootstrap.c Stage-1
        // before the Kotlin runtime starts. UID/GID mapping was also completed by Stage-1.
        // See bootstrap.c for the full 2-stage protocol.
        Logger.debug("all namespaces already unshared by Stage-1, UID/GID mapping already done")
        Logger.debug("user namespace mapping already done by Stage-1, we are root in user NS")
        Logger.debug("session already created by bootstrap.c (sid=${getsid(0)})")

        // Bring up the loopback interface inside the container's network
        // namespace (when one is configured). Without this, the container has
        // no working network at all — `ping 127.0.0.1` fails, `bind(...)` to
        // 127.0.0.1 fails with EADDRNOTAVAIL, etc.
        if (spec.hasNamespace("network")) {
            if (platform.linux.set_loopback_up() != 0) {
                Logger.warn("failed to bring up loopback interface (errno=$errno)")
            } else {
                Logger.debug("brought up loopback interface")
            }
        }

        // Build a State view of ourselves for hook stdin. We're in the
        // container's namespaces but haven't pivoted root or execve'd yet, so
        // status is still "created" for createContainer and "running" for
        // startContainer (set right before exec).
        val bundlePath = getenv("_KONTAINER_BUNDLE_PATH")?.toKString() ?: ""
        val containerId = getenv("_KONTAINER_CONTAINER_ID")?.toKString() ?: ""
        val createdState =
            state.State(
                ociVersion = spec.ociVersion,
                id = containerId,
                status = state.ContainerStatus.CREATED,
                pid = getpid(),
                bundle = bundlePath,
                annotations = spec.annotations,
            )

        // Obtain the console socket fd for PTY master handoff.
        //
        // Prefer _KONTAINER_CONSOLE_SOCKET_FD: the main process connected to
        // the socket while still in the host namespace (before the user-ns
        // switch), so the fd works regardless of uid/gid mappings.
        //
        // Fallback to _KONTAINER_CONSOLE_SOCKET (path-based connect) only for
        // backwards compatibility; this fails when a user namespace remaps the
        // init's host UID to one that lacks permission on the socket.
        var consoleSocketFd = -1
        if (spec.process.terminal) {
            val preFd = getenv("_KONTAINER_CONSOLE_SOCKET_FD")?.toKString()?.toIntOrNull()
            if (preFd != null && preFd >= 0) {
                consoleSocketFd = preFd
                Logger.debug("using pre-connected console socket fd=$consoleSocketFd")
            } else {
                val consoleSocketPath =
                    getenv("_KONTAINER_CONSOLE_SOCKET")?.toKString()
                        ?: run {
                            Logger.error(
                                "spec.process.terminal=true but --console-socket was not provided",
                            )
                            _exit(1)
                            @Suppress("UNREACHABLE_CODE")
                            return@memScoped
                        }
                consoleSocketFd = connectConsoleSocket(consoleSocketPath)
                if (consoleSocketFd < 0) {
                    Logger.error("failed to connect to console socket: $consoleSocketPath")
                    _exit(1)
                }
            }
        }

        // Prepare rootfs
        if (spec.hasNamespace("mount")) {
            prepareRootfs(syscall, rootfsPath, spec.linux?.rootfsPropagation, spec.mounts)
            // Process spec.mounts BEFORE pivot_root so bind-mount source paths from
            // the host are still reachable. Targets are inside rootfsPath.
            // Returns deferred propagation entries that must be applied after
            // pivot_root (propagation set before pivot is lost during cleanup).
            val deferredPropagation = applySpecMounts(syscall, spec.mounts, rootfsPath, spec.linux, mainSender, initReceiver)

            // createContainer hooks run after the container's mount namespace
            // is established but BEFORE pivot_root — they can still see the
            // host paths via the new rootfs's parent. This is the standard
            // spec timing (post-1.0.2).
            if (spec.hooks?.createContainer != null) {
                if (!hook.runHooks(spec.hooks.createContainer, createdState)) {
                    Logger.error("createContainer hook failed; aborting")
                    _exit(1)
                }
            }
            pivotRoot(syscall, rootfsPath)
            // Apply rootfsPropagation only AFTER pivot_root; the kernel forbids
            // pivot_root into a MS_SHARED subtree.
            applyRootfsPropagation(syscall, spec.linux?.rootfsPropagation)
            // Apply deferred mount propagation after pivot_root.
            applyDeferredPropagation(deferredPropagation)
        } else {
            Logger.debug("no mount namespace, skipping rootfs preparation")
        }

        // Change to working directory
        val cwd = spec.process.cwd
        if (syscall.chdir(cwd) != 0) {
            perror("chdir")
            Logger.warn("failed to chdir to $cwd")
        } else {
            Logger.debug("changed directory to $cwd")
        }

        // Set hostname (within UTS namespace).
        // Must be done BEFORE dropping privileges (setuid/setgid) because
        // sethostname() requires CAP_SYS_ADMIN.
        // See: runc/libcontainer/standard_init_linux.go:120-129
        spec.hostname?.let { hostname ->
            if (syscall.sethostname(hostname) != 0) {
                perror("sethostname")
                Logger.warn("failed to set hostname to $hostname")
            } else {
                Logger.debug("set hostname to $hostname")
            }
        }

        spec.domainname?.let { domainname ->
            if (syscall.setdomainname(domainname) != 0) {
                perror("setdomainname")
                Logger.warn("failed to set domainname to $domainname")
            } else {
                Logger.debug("set domainname to $domainname")
            }
        }

        // Create spec.linux.devices[] device nodes inside the container's /dev.
        applyLinuxDevices(syscall, spec.linux?.devices)

        // Apply spec.linux.sysctl entries via /proc/sys/*. /proc is mounted by
        // prepareRootfs; we must do this while still root (writing /proc/sys
        // generally needs CAP_SYS_ADMIN or similar).
        applySysctls(spec.linux?.sysctl)

        // Mask and remount-readonly paths inside the container. Done after
        // prepareRootfs+pivotRoot (so the target paths exist inside the new
        // root) and before dropping caps (mount/remount need CAP_SYS_ADMIN).
        applyMaskedPaths(syscall, spec.linux?.maskedPaths)
        applyReadonlyPaths(syscall, spec.linux?.readonlyPaths)

        // Finalize rootfs (set readonly, umask).
        // Must be done BEFORE dropping privileges (setuid/setgid) because remounting
        // requires CAP_SYS_ADMIN.
        // See: runc/libcontainer/standard_init_linux.go:114-118
        finalizeRootfs(syscall, spec)

        // Prepare environment and FD handling
        val processArgs = spec.process.args
        val processEnv = spec.process.env?.toMutableList() ?: mutableListOf()

        // Handle LISTEN_FDS for systemd socket activation.
        // See https://www.freedesktop.org/software/systemd/man/sd_listen_fds.html
        val listenFds = getenv("LISTEN_FDS")?.toKString()?.toIntOrNull() ?: 0
        val preserveFds =
            if (listenFds > 0) {
                processEnv.add("LISTEN_FDS=$listenFds")
                processEnv.add("LISTEN_PID=1")
                Logger.debug("preserving $listenFds FDs for systemd socket activation")
                listenFds
            } else {
                0
            }

        // Set up the container's session keyring. Must happen BEFORE
        // applyProcessSecurity so the keyring is created while we still
        // have capabilities, and with the correct SELinux label set on
        // /proc/self/attr/keycreate.
        // See: runc/libcontainer/standard_init_linux.go
        setupSessionKeyring(
            containerId = containerId,
            processLabel = spec.process.selinuxLabel,
            hasUserNamespace = spec.hasNamespace("user"),
            isExec = false,
        )

        // Send a pidfd for ourselves over the pre-connected pidfd socket.
        // Done after namespace setup (so the pidfd refers to a process
        // inside the container namespaces) and before execve.
        val pidfdSocketFd = getenv("_KONTAINER_PIDFD_SOCKET_FD")?.toKString()?.toIntOrNull()
        if (pidfdSocketFd != null && pidfdSocketFd >= 0) {
            Logger.debug("sending pidfd over pre-connected socket fd=$pidfdSocketFd")
            sendPidfd(pidfdSocketFd, "standard")
        }

        // Apply the shared spec.process security profile (umask, NNP, seccomp,
        // capabilities, setgid/setuid, AppArmor/SELinux). The seccomp notify FD,
        // if any, is forwarded to the main process over the channel.
        // rlimits are applied dead-last, right before execvp (see below).
        applyProcessSecurity(syscall, spec.process, spec.linux?.seccomp) { notifyFd ->
            syncSeccompNotifyFd(notifyFd, mainSender, initReceiver)
        }

        // Cleanup extra file descriptors to prevent FD leaks (CVE-2024-21626).
        // This sets FD_CLOEXEC on all FDs >= 3 + preserveFds so they're auto-closed at
        // execve.  Channel fds remain usable (CLOEXEC only fires at execve, not
        // during read/write), so initReady() below still works.
        syscall.closeRange(preserveFds)

        // PTY allocation: when spec.process.terminal is true, allocate a
        // pseudo-terminal pair from the container's devpts (/dev/pts),
        // ship the master fd to the caller via the pre-connected console
        // socket, and wire the slave to stdio.
        //
        // Placed AFTER closeRange: the PTY fds are created fresh (no stale
        // FD_CLOEXEC), the master is sent and closed manually, and the slave
        // is dup2'd onto 0/1/2 before execvp.
        //
        // Placed AFTER applyProcessSecurity: grantpt sets the slave's UID to
        // the calling process's real UID, which is the container process's
        // UID after setuid has been applied — so the PTY slave's ownership
        // automatically matches the spec's process.user.uid.
        //
        // The PTY is allocated from the container's devpts (via /dev/pts/ptmx)
        // rather than the host's /dev/ptmx so that /dev/pts/N paths resolve
        // correctly inside the container.
        if (spec.process.terminal) {
            val pty =
                openPtyFromDevpts("/dev/pts")
                    ?: run {
                        Logger.error("failed to allocate pseudo-terminal from container devpts")
                        if (consoleSocketFd >= 0) close(consoleSocketFd)
                        _exit(1)
                        @Suppress("UNREACHABLE_CODE")
                        return@memScoped
                    }

            if (!sendMasterViaFd(consoleSocketFd, pty.master)) {
                Logger.error("failed to send PTY master via console socket")
                close(pty.master)
                close(pty.slave)
                close(consoleSocketFd)
                _exit(1)
            }
            close(pty.master)
            close(consoleSocketFd)

            // Save a dup of stderr BEFORE wireStdio replaces it with the
            // PTY slave. This lets the Logger write to the original stderr
            // (which connects back to the runtime's stderr) instead of
            // going through the PTY relay and polluting the container's
            // output stream.
            //
            // Mark the dup CLOEXEC so it does NOT leak across execvp into
            // the container process. Without CLOEXEC the fd survives exec
            // (it was created after closeRange) and keeps the caller's
            // pipe/fd open, causing $() subshell captures (e.g. bats'
            // `run`) to hang indefinitely.
            val savedStderr = dup(STDERR_FILENO)
            if (savedStderr >= 0) {
                fcntl(savedStderr, F_SETFD, FD_CLOEXEC)
                Logger.redirectToFd(savedStderr)
            }

            wireStdio(
                pty.slave,
                spec.process.consoleSize?.height,
                spec.process.consoleSize?.width,
            )
        }

        mainSender.initReady()
        Logger.debug("sent init ready signal")

        mainSender.close()
        initReceiver.close()

        // Close the saved stderr fd before blocking for the start signal.
        // In the "create" flow, the init process sits in "created" state
        // while the CLI has already returned. The saved stderr fd (which
        // points to the original runtime stderr) keeps the caller's pipe
        // alive, preventing bats' output capture from completing.
        // We re-open it after start if terminal mode is active.
        Logger.debug("waiting for start signal...")
        Logger.closeRedirect()

        // Install signal handlers so that PID 1 (init) can receive signals
        // from ancestor PID namespaces. In a PID namespace, PID 1 only
        // receives signals for which it has installed a handler; the kernel
        // silently drops others (even from ancestor namespaces, except
        // SIGKILL/SIGSTOP). Without these handlers, pidfd_send_signal()
        // with SIGTERM from the container manager would be silently ignored.
        signal(SIGTERM, staticCFunction<Int, Unit> { _ -> _exit(130) })
        signal(SIGINT, staticCFunction<Int, Unit> { _ -> _exit(130) })
        signal(SIGHUP, staticCFunction<Int, Unit> { _ -> _exit(130) })

        // Explicitly close all leaked FDs before blocking in "created"
        // state.  closeRange (above) only sets CLOEXEC which fires at
        // execve, but tools like runc's FD-leak test inspect
        // /proc/$pid/fd while we're blocked here — before execve.  Close
        // everything except stdio (0-2) and the notify listener socket.
        val keepFd = notifyListener.fd()
        val dir = opendir("/proc/self/fd")
        if (dir != null) {
            val leaked = mutableListOf<Int>()
            while (true) {
                val entry = readdir(dir) ?: break
                val fd =
                    entry.pointed.d_name
                        .toKString()
                        .toIntOrNull() ?: continue
                if (fd >= 3 && fd != keepFd) leaked.add(fd)
            }
            closedir(dir) // closes its own internal fd
            for (fd in leaked) close(fd) // EBADF on dir-fd is harmless
        }

        notifyListener.waitForContainerStart()
        // Reopen Logger redirect for any post-start log messages.
        // The PTY slave is already wired to stdio, so dup stderr again
        // from the ORIGINAL stderr isn't possible (it was closed above).
        // That's fine — post-start logging goes to the PTY-backed stderr,
        // which is what the container process will use anyway.
        Logger.debug("received start signal, executing container process")

        notifyListener.close()

        // startContainer hooks run in the container's namespaces just before
        // execve. Status is "running" at this point.
        if (spec.hooks?.startContainer != null) {
            val runningState = createdState.copy(status = state.ContainerStatus.RUNNING)
            if (!hook.runHooks(spec.hooks.startContainer, runningState)) {
                Logger.error("startContainer hook failed; aborting exec")
                _exit(1)
            }
        }

        // An empty args list means the spec omitted spec.process entirely. The
        // start operation must still succeed: exit cleanly so the container
        // transitions to "stopped" without trying to exec nothing.
        if (processArgs.isEmpty()) {
            Logger.info("spec.process omitted; init exiting with status 0")
            _exit(0)
        }

        // Resolve HOME from /etc/passwd if not already set in spec.
        // Must happen AFTER pivotRoot (so /etc/passwd is the container's)
        // and BEFORE applyProcessEnv (which calls clearenv + setenv).
        ensureHomeEnv(processEnv, spec.process.user.uid)

        Logger.info("Executing: ${processArgs.joinToString(" ")}")

        // Clear host environment variables so container starts clean
        applyProcessEnv(processEnv)

        // Apply rlimits dead-last: a low RLIMIT_AS applied any earlier could
        // abort the Kotlin/Native runtime itself before execve. The main
        // process also applies rlimits against stage-1's PID early, which is
        // what allows raising hard limits while still privileged; this final
        // application guarantees the container sees the spec'd values even if
        // stage-2 was cloned before that early prlimit landed.
        syscall.applyRlimits(0, spec.process.rlimits)

        val argv = allocArray<CPointerVar<ByteVar>>(processArgs.size + 1)
        processArgs.forEachIndexed { i, arg ->
            argv[i] = arg.cstr.ptr
        }
        argv[processArgs.size] = null

        // execvp uses PATH lookup and the environment we set above
        execvp(processArgs[0], argv)

        // Match runc's lowercase error format (Go's os error strings are
        // lowercase while C's strerror uses title-case).
        val errMsg = strerror(errno)?.toKString()?.lowercase() ?: "unknown error"
        fprintf(stderr, "exec %s: %s\n", processArgs[0], errMsg)
        _exit(255)
    }

/**
 * Entry point for init process
 * Handles errors and communicates them to main process
 */
@OptIn(ExperimentalForeignApi::class)
fun runInitProcess(
    syscall: Syscall,
    spec: Spec,
    rootfsPath: String,
    mainSender: MainSender,
    initReceiver: InitReceiver,
    notifyListener: NotifyListener,
) {
    try {
        initProcessInternal(syscall, spec, rootfsPath, mainSender, initReceiver, notifyListener)
    } catch (e: Exception) {
        Logger.error("init process failed: ${e.message ?: "unknown"}")

        try {
            mainSender.sendError("Init process failed: ${e.message}")
        } catch (sendErr: Exception) {
            Logger.warn("failed to send error to main process: ${sendErr.message ?: "unknown"}")
        }

        _exit(1)
    }
}

/**
 * Synchronize a seccomp notify FD with the process that forwards it to the
 * OCI seccomp listener. Sends the FD via seccompNotifyRequest() and blocks
 * until the peer acknowledges, so the listener is attached before any user
 * code runs. Used by both the init path (peer = main process) and exec
 * (peer = the exec parent process).
 */
@OptIn(ExperimentalForeignApi::class)
fun syncSeccompNotifyFd(
    notifyFd: Int,
    mainSender: MainSender,
    initReceiver: InitReceiver,
) {
    Logger.debug("sending seccomp notify FD for forwarding")
    mainSender.seccompNotifyRequest(notifyFd)
    initReceiver.waitForSeccompRequestDone()
    // Close our copy of the notify FD — the main process (and then the
    // seccomp agent) now hold their own duplicates via SCM_RIGHTS.
    // Keeping this open prevents the kernel from returning ENOSYS to
    // notified syscalls when the seccomp agent dies.
    close(notifyFd)
    Logger.debug("seccomp notify FD handled by forwarding process (closed local copy)")
}

/**
 * Finalize rootfs setup
 * - Set rootfs as readonly if specified
 * (umask now lives in applyProcessSecurity, alongside the rest of the
 * spec.process setup shared with exec)
 * See: runc/libcontainer/rootfs_linux.go:finalizeRootfs()
 */
@OptIn(ExperimentalForeignApi::class)
private fun finalizeRootfs(
    syscall: Syscall,
    spec: Spec,
) {
    if (spec.root.readonly) {
        Logger.debug("finalizing rootfs as readonly")
        setRootfsReadonly(syscall)
    }
}
