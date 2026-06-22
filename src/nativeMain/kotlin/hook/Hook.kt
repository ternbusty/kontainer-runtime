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

            val args = listOf(hook.path) + (hook.args ?: emptyList())
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

        // Wait for the hook to finish. waitpid is blocking; honour timeout by
        // alarm(2) for now — coarse but matches the test's expectations.
        val timeout = hook.timeout ?: 0
        if (timeout > 0) alarm(timeout.toUInt())
        val status = alloc<IntVar>()
        val rc = waitpid(pid, status.ptr, 0)
        if (timeout > 0) alarm(0u)
        if (rc < 0) {
            Logger.warn("hook ${hook.path}: waitpid failed (errno=$errno)")
            return@memScoped false
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
