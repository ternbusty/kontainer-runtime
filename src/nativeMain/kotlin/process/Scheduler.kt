package process

import kotlinx.cinterop.*
import logger.Logger
import platform.linux.*
import platform.posix.*
import spec.LinuxScheduler

/**
 * Apply the OCI process.scheduler to the current process via sched_setattr(2).
 * Called before privilege drop (needs CAP_SYS_ADMIN for RT/deadline policies).
 *
 * struct sched_attr layout (48 bytes):
 *   u32  size           offset  0
 *   u32  sched_policy   offset  4
 *   u64  sched_flags    offset  8
 *   s32  sched_nice     offset 16
 *   u32  sched_priority offset 20
 *   u64  sched_runtime  offset 24
 *   u64  sched_deadline offset 32
 *   u64  sched_period   offset 40
 */
private const val SCHED_ATTR_SIZE = 48

@OptIn(ExperimentalForeignApi::class)
fun applyScheduler(scheduler: LinuxScheduler?) {
    if (scheduler == null) return

    memScoped {
        val attr = allocArray<ByteVar>(SCHED_ATTR_SIZE)

        // Zero the entire struct first
        for (i in 0 until SCHED_ATTR_SIZE) attr[i] = 0

        // size (u32 at offset 0)
        attr.reinterpret<IntVar>()[0] = SCHED_ATTR_SIZE

        // sched_policy (u32 at offset 4)
        attr.reinterpret<IntVar>()[1] = scheduler.policyValue()

        // sched_flags (u64 at offset 8)
        attr.reinterpret<LongVar>()[1] = scheduler.flagBits()

        // sched_nice (s32 at offset 16)
        attr.reinterpret<IntVar>()[4] = scheduler.nice ?: 0

        // sched_priority (u32 at offset 20)
        attr.reinterpret<IntVar>()[5] = scheduler.priority ?: 0

        // sched_runtime (u64 at offset 24)
        attr.reinterpret<LongVar>()[3] = scheduler.runtime ?: 0L

        // sched_deadline (u64 at offset 32)
        attr.reinterpret<LongVar>()[4] = scheduler.deadline ?: 0L

        // sched_period (u64 at offset 40)
        attr.reinterpret<LongVar>()[5] = scheduler.period ?: 0L

        // sched_setattr(pid=0 /*this thread*/, attr, flags=0)
        val rc = syscall(_NR_sched_setattr(), 0L, attr.toLong(), 0L)
        if (rc != 0L) {
            val err = strerror(errno)?.toKString() ?: "unknown"
            Logger.warn("sched_setattr failed: $err")
        } else {
            Logger.debug("sched_setattr policy=${scheduler.policy}")
        }
    }
}
