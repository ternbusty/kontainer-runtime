package command

import kotlinx.cinterop.*
import logger.Logger
import platform.linux.setns_wrapper
import platform.posix.*
import state.loadState
import utils.FileSystem

/**
 * Exec command — run an additional process inside a running container by joining
 * its namespaces via setns(2) and then execve(2)'ing the user's command.
 *
 * Minimal implementation: positional argv only, no separate process.json,
 * no TTY, no --user / --cwd / --env overrides (those use the container's
 * existing setup). Enough for `kontainer-runtime exec <id> sh -c '...'`.
 *
 * setns into mount/pid/etc requires a single-threaded process. fork() returns
 * a single-threaded child even if the parent runtime has worker threads, so
 * we do the namespace joining in a forked child before execve.
 */
@OptIn(ExperimentalForeignApi::class)
fun exec(
    fs: FileSystem,
    rootPath: String,
    containerId: String,
    args: List<String>,
) {
    if (args.isEmpty()) {
        Logger.error("exec: at least one command argument is required")
        exit(1)
    }

    val state = try {
        loadState(fs, rootPath, containerId)
    } catch (e: Exception) {
        Logger.error("exec: failed to load state for $containerId: ${e.message}")
        exit(1)
        return
    }
    val initPid = state.pid ?: run {
        Logger.error("exec: container has no init PID; is it running?")
        exit(1)
        return
    }

    // Open every namespace file under /proc/<initPid>/ns/. user must be first
    // (transition affects capability checks), pid last among the post-fork ones.
    val nsOrder = listOf("user", "ipc", "uts", "net", "mnt", "cgroup", "pid")
    val nsFds = mutableMapOf<String, Int>()
    for (ns in nsOrder) {
        val path = "/proc/$initPid/ns/$ns"
        if (access(path, F_OK) != 0) continue
        val fd = open(path, O_RDONLY)
        if (fd < 0) {
            Logger.warn("exec: failed to open $path (errno=$errno)")
            continue
        }
        nsFds[ns] = fd
    }

    val pid = fork()
    if (pid < 0) {
        Logger.error("exec: fork() failed (errno=$errno)")
        exit(1)
    }
    if (pid == 0) {
        // Child (single-threaded). Join the container's namespaces.
        // PID join takes effect on the NEXT fork — execve in this same
        // process still operates in the new pid ns since the kernel
        // applies the change at fork/clone boundaries... wait, the spec
        // is subtler: setns(pidfd, CLONE_NEWPID) puts CHILDREN of the
        // caller in the new pid ns, not the caller itself. To inherit
        // pid ns for the user's command we therefore fork once more.
        for (ns in nsOrder) {
            val fd = nsFds[ns] ?: continue
            if (setns_wrapper(fd, 0) != 0) {
                fprintf(stderr, "exec: setns(%s) failed: %s\n", ns, strerror(errno))
                _exit(1)
            }
        }
        chdir("/")
        val grandchild = fork()
        if (grandchild < 0) _exit(1)
        if (grandchild == 0) {
            // Grandchild — now inside the container's pid ns. Exec the command.
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
