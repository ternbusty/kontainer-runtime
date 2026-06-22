package hook

import kotlinx.cinterop.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logger.Logger
import platform.posix.*
import spec.Hook
import state.State

/**
 * Execute one OCI hook program. Standard input is the container State JSON;
 * stdout/stderr inherit from the runtime.
 *
 * Returns true on success (hook exited 0), false if the hook failed, errored
 * out, or didn't finish within its timeout.
 */
@OptIn(ExperimentalForeignApi::class)
fun execHook(
    hook: Hook,
    state: State,
): Boolean {
    val stateJson = Json.encodeToString(State.serializer(), state)
    Logger.debug("running hook ${hook.path} args=${hook.args}")

    return memScoped {
        val pipeFds = allocArray<IntVar>(2)
        if (pipe(pipeFds) != 0) {
            Logger.warn("hook ${hook.path}: pipe() failed (errno=$errno)")
            return@memScoped false
        }
        val readEnd = pipeFds[0]
        val writeEnd = pipeFds[1]

        val pid = fork()
        if (pid < 0) {
            close(readEnd); close(writeEnd)
            Logger.warn("hook ${hook.path}: fork() failed (errno=$errno)")
            return@memScoped false
        }
        if (pid == 0) {
            // Child: wire stdin to read end of pipe and exec.
            close(writeEnd)
            dup2(readEnd, STDIN_FILENO)
            close(readEnd)

            // Per the OCI spec, hook.args is the FULL argv (including argv[0]).
            // Fall back to [hook.path] when args is omitted.
            val args = hook.args ?: listOf(hook.path)
            val argv = allocArray<CPointerVar<ByteVar>>(args.size + 1)
            args.forEachIndexed { i, a -> argv[i] = a.cstr.ptr }
            argv[args.size] = null

            val envList = hook.env
            if (envList != null) {
                val envp = allocArray<CPointerVar<ByteVar>>(envList.size + 1)
                envList.forEachIndexed { i, e -> envp[i] = e.cstr.ptr }
                envp[envList.size] = null
                execve(hook.path, argv, envp)
            } else {
                execv(hook.path, argv)
            }
            // execve only returns on failure.
            Logger.error("hook ${hook.path}: execve failed (errno=$errno)")
            _exit(127)
        }

        // Parent: write state JSON to pipe.
        close(readEnd)
        val bytes = stateJson.encodeToByteArray()
        bytes.usePinned { pinned ->
            val w = write(writeEnd, pinned.addressOf(0), bytes.size.toULong())
            if (w.toInt() != bytes.size) {
                Logger.warn("hook ${hook.path}: short write of state JSON ($w / ${bytes.size})")
            }
        }
        close(writeEnd)

        // Wait for the hook with a private timeout: WNOHANG-poll + sleep loop.
        // alarm(2) would work but is process-wide — a future caller running two
        // hooks concurrently, or with an unrelated SIGALRM handler, would see
        // bogus interruptions. The 50ms poll cadence keeps CPU cost negligible
        // while still firing the timeout within ~50ms of its budget.
        val status = alloc<IntVar>()
        val timeoutMs = (hook.timeout ?: 0) * 1000L
        val pollSleepMicros = 50_000u // 50ms
        val deadline = if (timeoutMs > 0) monotonicMillis() + timeoutMs else 0L
        while (true) {
            val rc = waitpid(pid, status.ptr, WNOHANG)
            if (rc == pid) break // hook finished
            if (rc < 0) {
                Logger.warn("hook ${hook.path}: waitpid failed (errno=$errno)")
                return@memScoped false
            }
            // rc == 0: still running
            if (deadline != 0L && monotonicMillis() >= deadline) {
                Logger.warn("hook ${hook.path}: timed out after ${hook.timeout}s; killing")
                kill(pid, SIGKILL)
                waitpid(pid, status.ptr, 0)
                return@memScoped false
            }
            usleep(pollSleepMicros)
        }
        val exited = (status.value and 0x7f) == 0
        val code = (status.value shr 8) and 0xff
        if (!exited || code != 0) {
            Logger.warn("hook ${hook.path}: exited with status ${status.value} (code=$code)")
            return@memScoped false
        }
        Logger.debug("hook ${hook.path}: completed successfully")
        true
    }
}

/**
 * Read the monotonic clock and return milliseconds. CLOCK_MONOTONIC is immune
 * to wall-clock changes (e.g. NTP adjustments) which would otherwise let a
 * hook's deadline drift.
 */
@OptIn(ExperimentalForeignApi::class)
private fun monotonicMillis(): Long =
    memScoped {
        val ts = alloc<timespec>()
        clock_gettime(CLOCK_MONOTONIC.toInt(), ts.ptr)
        ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
    }

/**
 * Run every hook in [hooks], stopping at the first failure. Returns true if all
 * hooks ran cleanly.
 */
fun runHooks(
    hooks: List<Hook>?,
    state: State,
): Boolean {
    if (hooks.isNullOrEmpty()) return true
    for (hook in hooks) {
        if (!execHook(hook, state)) return false
    }
    return true
}
