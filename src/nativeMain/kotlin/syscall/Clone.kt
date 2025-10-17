package syscall

import kotlinx.cinterop.*
import logger.Logger
import platform.linux._CLONE_PARENT
import platform.posix.*

/**
 * Clone system call wrapper with CLONE_PARENT support
 *
 * This is used to create the init process as a sibling of the intermediate process,
 * rather than as a child. This ensures that when the intermediate process exits,
 * the init process is not re-parented to PID 1 (system init), but remains a child
 * of the main process.
 */

// System call numbers (x86_64 Linux)
private const val SYS_clone3 = 435L
private const val SYS_clone = 56L

/**
 * Size of clone3_args structure in bytes
 * 11 fields * 8 bytes (ULong) = 88 bytes
 */
private const val CLONE3_ARGS_SIZE = 88

/**
 * Clone a sibling process that shares the same parent as the calling process.
 *
 * This is used to launch the container init process so the parent process of the
 * calling process can receive ownership of the process. If we clone a child process
 * as the init process, the calling process (intermediate process) will exit and the
 * init process will be re-parented to process 1 (system init process), which is not
 * the right behavior of what we look for.
 *
 * @param callback Function to execute in the child process. Must call _exit() and never return.
 * @return PID of the cloned process in the parent, does not return in the child
 * @throws Exception if clone fails
 */
@OptIn(ExperimentalForeignApi::class)
fun cloneSibling(callback: () -> Nothing): Int {
    Logger.debug("cloning sibling process with CLONE_PARENT")

    // Try clone3 first
    val pid = tryClone3(callback)
    if (pid != null) {
        return pid
    }

    // If clone3 is not supported (ENOSYS), fall back to clone
    Logger.debug("clone3 not supported (ENOSYS), falling back to clone")
    return tryClone(callback)
}

/**
 * Try to use clone3 syscall
 *
 * @return PID in parent, null if ENOSYS (syscall not available)
 * @throws Exception for other errors
 */
@OptIn(ExperimentalForeignApi::class)
private fun tryClone3(callback: () -> Nothing): Int? = memScoped {
    // Allocate and initialize clone3_args structure
    // The structure has 11 ULong fields (88 bytes total)
    val args = allocArray<ULongVar>(11)

    // Set fields in order:
    // 0: flags, 1: pidfd, 2: child_tid, 3: parent_tid, 4: exit_signal
    // 5: stack, 6: stack_size, 7: tls, 8: set_tid, 9: set_tid_size, 10: cgroup
    args[0] = _CLONE_PARENT().toULong()  // flags
    args[1] = 0u  // pidfd
    args[2] = 0u  // child_tid
    args[3] = 0u  // parent_tid
    // Note: When using CLONE_PARENT, exit_signal must be 0, otherwise clone3 returns EINVAL
    args[4] = 0u  // exit_signal
    args[5] = 0u  // stack
    args[6] = 0u  // stack_size
    args[7] = 0u  // tls
    args[8] = 0u  // set_tid
    args[9] = 0u  // set_tid_size
    args[10] = 0u  // cgroup

    Logger.debug("calling clone3 with CLONE_PARENT flag")

    val result = syscall(SYS_clone3, args, CLONE3_ARGS_SIZE.toLong())

    when {
        result == -1L -> {
            val errNum = errno
            if (errNum == ENOSYS) {
                // clone3 not supported by kernel
                Logger.debug("clone3 returned ENOSYS (not supported)")
                return@memScoped null
            } else {
                // Other error
                perror("clone3")
                Logger.error("clone3 failed with errno=$errNum")
                throw Exception("Failed to clone3: errno=$errNum")
            }
        }
        result == 0L -> {
            // Child process
            Logger.debug("clone3: in child process")
            callback()
            // callback calls _exit() and never returns
        }
        result > 0 -> {
            // Parent process
            val pid = result.toInt()
            Logger.debug("clone3: cloned child with PID $pid")
            return@memScoped pid
        }
        else -> {
            Logger.error("clone3 returned unexpected value: $result")
            throw Exception("clone3 returned unexpected value: $result")
        }
    }
}

/**
 * Fallback to old clone syscall
 *
 * Note: The clone syscall requires explicit stack management, which is complex.
 * For now, this is a placeholder that throws an exception.
 * We can implement full clone support if needed (clone3 should be available on kernel 5.3+).
 *
 * @throws Exception always (not implemented yet)
 */
@OptIn(ExperimentalForeignApi::class)
private fun tryClone(callback: () -> Nothing): Int {
    Logger.error("clone fallback not yet implemented")
    Logger.error("clone3 is not available on this kernel (< 5.3)")
    Logger.error("Please upgrade to a kernel that supports clone3 (5.3+)")
    throw Exception("clone3 not supported and clone fallback not implemented")
}
