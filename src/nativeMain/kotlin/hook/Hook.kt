package hook

import ioloop.withIoLoop
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logger.Logger
import platform.linux._NR_pidfd_open
import platform.posix.*
import spec.Hook
import state.State

/**
 * Result of executing an OCI hook.
 */
data class HookResult(
    val success: Boolean,
    /** Combined stdout + stderr captured from the hook process. */
    val output: String = "",
    /** Exit status code, or -1 if the hook was killed / timed out. */
    val exitCode: Int = -1,
    /** True when the hook was killed by a signal rather than exiting. */
    val signaled: Boolean = false,
    /** Signal number if signaled. */
    val signal: Int = 0,
)

/**
 * Map a signal number to the human-readable name that runc uses in hook
 * error messages (lowercase, matching Go's `signal.Signal.String()`).
 */
private fun signalName(sig: Int): String =
    when (sig) {
        SIGHUP -> "hangup"
        SIGINT -> "interrupt"
        SIGQUIT -> "quit"
        SIGILL -> "illegal instruction"
        SIGTRAP -> "trace/breakpoint trap"
        SIGABRT -> "aborted"
        SIGBUS -> "bus error"
        SIGFPE -> "floating point exception"
        SIGKILL -> "killed"
        SIGUSR1 -> "user defined signal 1"
        SIGSEGV -> "segmentation fault"
        SIGUSR2 -> "user defined signal 2"
        SIGPIPE -> "broken pipe"
        SIGALRM -> "alarm clock"
        SIGTERM -> "terminated"
        SIGCHLD -> "child exited"
        SIGCONT -> "continued"
        SIGSTOP -> "stopped (signal)"
        SIGTSTP -> "stopped"
        SIGTTIN -> "stopped (tty input)"
        SIGTTOU -> "stopped (tty output)"
        SIGURG -> "urgent I/O condition"
        SIGXCPU -> "CPU time limit exceeded"
        SIGXFSZ -> "file size limit exceeded"
        SIGVTALRM -> "virtual timer expired"
        SIGPROF -> "profiling timer expired"
        SIGWINCH -> "window changed"
        SIGIO -> "I/O possible"
        SIGSYS -> "bad system call"
        else -> "signal $sig"
    }

/**
 * Execute one OCI hook program. Standard input is the container State JSON.
 * Captures stdout and stderr from the hook so callers can include the output
 * in runc-compatible error messages.
 *
 * @param hook OCI Hook spec
 * @param state Container state (serialized to JSON and piped to hook stdin)
 * @param processEnv Optional process environment to inherit for startContainer hooks
 *                    when the hook does not declare its own env field.
 */
@OptIn(ExperimentalForeignApi::class)
fun execHook(
    hook: Hook,
    state: State,
    processEnv: List<String>? = null,
): HookResult {
    val stateJson = Json.encodeToString(State.serializer(), state)
    Logger.debug("running hook ${hook.path} args=${hook.args}")

    return memScoped {
        // Pipe for feeding state JSON to hook stdin.
        val stdinPipe = allocArray<IntVar>(2)
        if (pipe(stdinPipe) != 0) {
            Logger.warn("hook ${hook.path}: pipe() failed (errno=$errno)")
            return@memScoped HookResult(success = false)
        }
        val stdinRead = stdinPipe[0]
        val stdinWrite = stdinPipe[1]

        // Pipe for capturing hook stdout+stderr.
        val outPipe = allocArray<IntVar>(2)
        if (pipe(outPipe) != 0) {
            close(stdinRead)
            close(stdinWrite)
            Logger.warn("hook ${hook.path}: pipe() for output failed (errno=$errno)")
            return@memScoped HookResult(success = false)
        }
        val outRead = outPipe[0]
        val outWrite = outPipe[1]

        val pid = fork()
        if (pid < 0) {
            close(stdinRead)
            close(stdinWrite)
            close(outRead)
            close(outWrite)
            Logger.warn("hook ${hook.path}: fork() failed (errno=$errno)")
            return@memScoped HookResult(success = false)
        }
        if (pid == 0) {
            // Child: wire stdin to stdinPipe read end, stdout+stderr to outPipe write end.
            close(stdinWrite)
            close(outRead)
            dup2(stdinRead, STDIN_FILENO)
            close(stdinRead)
            dup2(outWrite, STDOUT_FILENO)
            dup2(outWrite, STDERR_FILENO)
            close(outWrite)

            // Per the OCI spec, hook.args is the FULL argv (including argv[0]).
            val args = hook.args ?: listOf(hook.path)
            val argv = allocArray<CPointerVar<ByteVar>>(args.size + 1)
            args.forEachIndexed { i, a -> argv[i] = a.cstr.ptr }
            argv[args.size] = null

            // Determine the environment for this hook.
            val envList = hook.env ?: processEnv
            if (envList != null) {
                val envp = allocArray<CPointerVar<ByteVar>>(envList.size + 1)
                envList.forEachIndexed { i, e -> envp[i] = e.cstr.ptr }
                envp[envList.size] = null
                execve(hook.path, argv, envp)
            } else {
                execv(hook.path, argv)
            }
            // execve only returns on failure.
            _exit(127)
        }

        // Parent: write state JSON to stdin pipe, then close write end.
        close(stdinRead)
        close(outWrite)
        val bytes = stateJson.encodeToByteArray()
        bytes.usePinned { pinned ->
            write(stdinWrite, pinned.addressOf(0), bytes.size.toULong())
        }
        close(stdinWrite)

        // Read captured output (non-blocking read with a size limit to avoid
        // memory issues from a misbehaving hook).
        val outputBuf = allocArray<ByteVar>(65536)
        var totalRead = 0
        while (totalRead < 65535) {
            val n = read(outRead, outputBuf + totalRead, (65535 - totalRead).toULong())
            if (n <= 0) break
            totalRead += n.toInt()
        }
        close(outRead)
        outputBuf[totalRead] = 0
        val output = if (totalRead > 0) outputBuf.toKString().trim() else ""

        // Wait for the hook with a timeout.
        val status = alloc<IntVar>()
        val timeoutMs = (hook.timeout ?: 0) * 1000L

        val pidfd = syscall(_NR_pidfd_open(), pid, 0).toInt()
        if (pidfd >= 0) {
            val timedOut =
                try {
                    withIoLoop { io ->
                        if (timeoutMs > 0) {
                            withTimeoutOrNull(timeoutMs) { io.awaitReadable(pidfd) } == null
                        } else {
                            io.awaitReadable(pidfd)
                            false
                        }
                    }
                } finally {
                    close(pidfd)
                }
            if (timedOut) {
                Logger.warn("hook ${hook.path}: timed out after ${hook.timeout}s; killing")
                kill(pid, SIGKILL)
                waitpid(pid, status.ptr, 0)
                return@memScoped HookResult(
                    success = false,
                    output = output,
                    exitCode = -1,
                    signaled = true,
                    signal = SIGKILL,
                )
            }
            waitpid(pid, status.ptr, 0)
        } else {
            // Fallback for kernel < 5.3: WNOHANG + usleep
            val deadline = if (timeoutMs > 0) monotonicMillis() + timeoutMs else 0L
            while (true) {
                val rc = waitpid(pid, status.ptr, WNOHANG)
                if (rc == pid) break
                if (rc < 0) {
                    Logger.warn("hook ${hook.path}: waitpid failed (errno=$errno)")
                    return@memScoped HookResult(success = false, output = output)
                }
                if (deadline != 0L && monotonicMillis() >= deadline) {
                    Logger.warn("hook ${hook.path}: timed out after ${hook.timeout}s; killing")
                    kill(pid, SIGKILL)
                    waitpid(pid, status.ptr, 0)
                    return@memScoped HookResult(
                        success = false,
                        output = output,
                        exitCode = -1,
                        signaled = true,
                        signal = SIGKILL,
                    )
                }
                usleep(50_000u)
            }
        }

        // Decode wait status.
        val rawStatus = status.value
        val exited = (rawStatus and 0x7f) == 0
        if (exited) {
            val code = (rawStatus shr 8) and 0xff
            if (code == 0) {
                Logger.debug("hook ${hook.path}: completed successfully")
                HookResult(success = true, output = output, exitCode = 0)
            } else {
                HookResult(success = false, output = output, exitCode = code)
            }
        } else {
            // Killed by signal
            val sig = rawStatus and 0x7f
            HookResult(success = false, output = output, exitCode = -1, signaled = true, signal = sig)
        }
    }
}

/**
 * Read the monotonic clock and return milliseconds.
 */
@OptIn(ExperimentalForeignApi::class)
private fun monotonicMillis(): Long =
    memScoped {
        val ts = alloc<timespec>()
        clock_gettime(CLOCK_MONOTONIC, ts.ptr)
        ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
    }

/**
 * Run every hook in [hooks], stopping at the first failure. Returns null if all
 * hooks ran cleanly, or the runc-compatible error message on failure.
 *
 * @param phase The hook lifecycle phase name (e.g. "prestart", "startContainer")
 * @param processEnv Optional process env for startContainer hooks
 */
fun runHooksGetError(
    hooks: List<Hook>?,
    state: State,
    phase: String = "",
    processEnv: List<String>? = null,
): String? {
    if (hooks.isNullOrEmpty()) return null
    for ((index, hook) in hooks.withIndex()) {
        val result = execHook(hook, state, processEnv)
        if (!result.success) {
            // Format: "error running <phase> hook #<N>: <output>: exit status <N>"
            // This matches runc's format for hook error messages.
            val detail =
                when {
                    result.signaled -> {
                        val sigDesc = signalName(result.signal)
                        if (result.output.isNotEmpty()) {
                            "${result.output}: $sigDesc"
                        } else {
                            sigDesc
                        }
                    }
                    result.exitCode >= 0 -> {
                        if (result.output.isNotEmpty()) {
                            "${result.output}: exit status ${result.exitCode}"
                        } else {
                            "exit status ${result.exitCode}"
                        }
                    }
                    else -> {
                        if (result.output.isNotEmpty()) result.output else "unknown error"
                    }
                }
            return "error running $phase hook #$index: $detail"
        }
    }
    return null
}

/**
 * Run every hook in [hooks], stopping at the first failure. Returns true if all
 * hooks ran cleanly, false otherwise. On failure, the runc-compatible error
 * message is logged to stderr.
 *
 * @param phase The hook lifecycle phase name (e.g. "prestart", "startContainer")
 * @param processEnv Optional process env for startContainer hooks
 */
fun runHooks(
    hooks: List<Hook>?,
    state: State,
    phase: String = "",
    processEnv: List<String>? = null,
): Boolean {
    val err = runHooksGetError(hooks, state, phase, processEnv) ?: return true
    Logger.error(err)
    return false
}
