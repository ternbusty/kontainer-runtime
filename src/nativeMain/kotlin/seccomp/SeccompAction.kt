package seccomp

import logger.Logger
import spec.LinuxSeccomp
import spec.LinuxSyscall

/**
 * OCI seccomp action, parsed from the spec's `action` string.
 *
 * `SCMP_ACT_ERRNO` and `SCMP_ACT_TRACE` carry a value that the OCI JSON keeps in a
 * separate `errnoRet` field; here it lives on the variant so the two cannot drift apart.
 */
sealed interface SeccompAction {
    data object Allow : SeccompAction

    data object Log : SeccompAction

    data object Notify : SeccompAction

    data object Trap : SeccompAction

    data object KillThread : SeccompAction

    data object KillProcess : SeccompAction

    data class Errno(
        val errno: UInt,
    ) : SeccompAction

    data class Trace(
        val id: UInt,
    ) : SeccompAction

    companion object {
        /**
         * @param errnoRet value for ERRNO/TRACE; defaults to 1 (EPERM) like runc when absent
         */
        fun parse(
            action: String,
            errnoRet: UInt?,
        ): SeccompAction =
            when (action) {
                "SCMP_ACT_KILL", "SCMP_ACT_KILL_THREAD" -> KillThread
                "SCMP_ACT_KILL_PROCESS" -> KillProcess
                "SCMP_ACT_TRAP" -> Trap
                "SCMP_ACT_ERRNO" -> Errno(errnoRet ?: 1u)
                "SCMP_ACT_TRACE" -> Trace(errnoRet ?: 1u)
                "SCMP_ACT_ALLOW" -> Allow
                "SCMP_ACT_LOG" -> Log
                "SCMP_ACT_NOTIFY" -> Notify
                else -> {
                    Logger.error("Unknown seccomp action: $action")
                    throw Exception("Unknown seccomp action: $action")
                }
            }
    }
}

val LinuxSyscall.parsedAction: SeccompAction
    get() = SeccompAction.parse(action, errnoRet)

val LinuxSeccomp.parsedDefaultAction: SeccompAction
    get() = SeccompAction.parse(defaultAction, defaultErrnoRet)
