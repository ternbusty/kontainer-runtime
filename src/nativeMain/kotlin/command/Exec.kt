package command

import cgroup.Cgroup
import config.loadKontainerConfig
import kotlinx.cinterop.*
import logger.Logger
import platform.linux.setns_wrapper
import platform.posix.*
import process.applyProcessSecurity
import spec.Spec
import spec.loadSpec
import state.ContainerStatus
import state.loadState
import state.refreshStatus
import syscall.Syscall
import utils.FileSystem

/**
 * Exec command — run an additional process inside a running container.
 *
 * The new process joins the container's cgroup and namespaces, then goes
 * through the same privilege-drop sequence as the init process (rlimits,
 * no_new_privileges, seccomp, capabilities, uid/gid, LSM exec labels) built
 * from the bundle's config.json, so it ends up no more privileged than the
 * container's own processes and subject to the same resource limits.
 *
 * Minimal interface: positional argv only, no separate process.json, no TTY.
 * cwd / env / user / umask come from the container's config.json process
 * block.
 *
 * setns into mount/pid requires a single-threaded process. fork() returns
 * a single-threaded child even if the parent runtime has worker threads, so
 * we do the namespace joining in a forked child before execve.
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

    val state =
        try {
            loadState(fs, rootPath, containerId).refreshStatus()
        } catch (e: Exception) {
            Logger.error("exec: failed to load state for $containerId: ${e.message}")
            exit(1)
            return
        }
    if (state.status != ContainerStatus.RUNNING) {
        Logger.error("exec: container is not running (status: ${state.status.value})")
        exit(1)
    }
    val initPid =
        state.pid ?: run {
            Logger.error("exec: container has no init PID; is it running?")
            exit(1)
            return
        }

    // The exec'd process must run under the container's security profile, so
    // the spec is required — failing open (host privileges inside the
    // container's namespaces) is not an option.
    val spec =
        try {
            loadSpec(fs, "${state.bundle}/config.json")
        } catch (e: Exception) {
            Logger.error("exec: failed to load spec from ${state.bundle}/config.json: ${e.message}")
            exit(1)
            return
        }

    // SCMP_ACT_NOTIFY needs an agent to service notifications. exec has no
    // channel to the container's seccomp agent, so an exec'd process under
    // such a filter would block forever on the first notified syscall.
    if (spec.linux?.seccomp?.let { seccomp.hasNotifyAction(it) } == true) {
        Logger.error("exec: seccomp filters using SCMP_ACT_NOTIFY are not supported by exec")
        exit(1)
    }

    val cgroupPath =
        try {
            loadKontainerConfig(fs, rootPath, containerId).cgroupPath
        } catch (e: Exception) {
            Logger.error("exec: failed to load kontainer config: ${e.message}")
            exit(1)
            return
        }

    // Open every namespace file under /proc/<initPid>/ns/. user must be first
    // (transition affects capability checks), pid last among the post-fork ones.
    // Namespaces the container shares with us (e.g. cgroup when the spec does
    // not request one) are skipped: joining them is a no-op at best, and after
    // the user-ns join we no longer hold CAP_SYS_ADMIN over host-owned
    // namespaces, so the setns would fail with EPERM.
    val nsOrder = listOf("user", "ipc", "uts", "net", "mnt", "cgroup", "pid")
    val nsFds = mutableMapOf<String, Int>()
    memScoped {
        val stSelf = alloc<stat>()
        val stInit = alloc<stat>()
        for (ns in nsOrder) {
            val path = "/proc/$initPid/ns/$ns"
            if (access(path, F_OK) != 0) continue
            if (stat("/proc/self/ns/$ns", stSelf.ptr) == 0 &&
                stat(path, stInit.ptr) == 0 &&
                stSelf.st_ino == stInit.st_ino &&
                stSelf.st_dev == stInit.st_dev
            ) {
                Logger.debug("exec: skipping $ns namespace (shared with the runtime)")
                continue
            }
            val fd = open(path, O_RDONLY)
            if (fd < 0) {
                Logger.warn("exec: failed to open $path (errno=$errno)")
                continue
            }
            nsFds[ns] = fd
        }
    }

    val pid = fork()
    if (pid < 0) {
        Logger.error("exec: fork() failed (errno=$errno)")
        exit(1)
    }
    if (pid == 0) {
        // Child (single-threaded). Join the container's cgroup BEFORE setns:
        // the user-ns join drops host-side privileges needed to write host
        // cgroupfs, and joining first also puts the grandchild under the
        // container's pids/memory limits from the start (fork inherits cgroup
        // membership).
        if (cgroupPath != null) {
            try {
                cgroup.addProcess(cgroupPath, getpid())
            } catch (e: Exception) {
                fprintf(stderr, "exec: failed to join cgroup %s: %s\n", cgroupPath, e.message ?: "unknown")
                _exit(1)
            }
        }
        // setns(pidfd, CLONE_NEWPID) puts CHILDREN of the caller in the new
        // pid ns, not the caller itself. To inherit the pid ns for the user's
        // command we therefore fork once more below.
        for (ns in nsOrder) {
            val fd = nsFds[ns] ?: continue
            if (setns_wrapper(fd, 0) != 0) {
                fprintf(stderr, "exec: setns(%s) failed: %s\n", ns, strerror(errno))
                _exit(1)
            }
        }
        val grandchild = fork()
        if (grandchild < 0) _exit(1)
        if (grandchild == 0) {
            // Grandchild — inside the container's pid ns with its rootfs via
            // the mount ns. chdir while still root, like init does (cwd may
            // not be reachable by the container user).
            if (chdir(spec.process.cwd) != 0) {
                fprintf(stderr, "exec: chdir(%s) failed: %s\n", spec.process.cwd, strerror(errno))
                _exit(1)
            }
            try {
                applyProcessSecurity(syscall, spec)
            } catch (e: Exception) {
                fprintf(stderr, "exec: privilege drop failed: %s\n", e.message ?: "unknown")
                _exit(1)
            }
            syscall.umask(spec.process.umask ?: 0x12u) // 0o022
            // Replace the inherited host environment with the container's;
            // execvp's PATH lookup must resolve inside the rootfs.
            clearenv()
            spec.process.env?.forEach { envEntry ->
                val parts = envEntry.split("=", limit = 2)
                if (parts.size == 2) setenv(parts[0], parts[1], 1)
            }
            memScoped {
                val argv = allocArray<CPointerVar<ByteVar>>(args.size + 1)
                args.forEachIndexed { i, a -> argv[i] = a.cstr.ptr }
                argv[args.size] = null
                execvp(args[0], argv)
                fprintf(stderr, "exec: execvp(%s) failed: %s\n", args[0], strerror(errno))
                _exit(127)
            }
        }
        // Child waits for grandchild and propagates exit code.
        memScoped {
            val status = alloc<IntVar>()
            waitpid(grandchild, status.ptr, 0)
            val code = if ((status.value and 0x7f) == 0) (status.value shr 8) and 0xff else 1
            _exit(code)
        }
    }

    // Parent: wait for child.
    nsFds.values.forEach { close(it) }
    memScoped {
        val status = alloc<IntVar>()
        waitpid(pid, status.ptr, 0)
        val code = if ((status.value and 0x7f) == 0) (status.value shr 8) and 0xff else 1
        exit(code)
    }
}
