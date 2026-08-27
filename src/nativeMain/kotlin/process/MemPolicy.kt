package process

import kotlinx.cinterop.*
import logger.Logger
import platform.linux.*
import platform.posix.*
import spec.LinuxMemoryPolicy

/**
 * NUMA memory policy modes (linux/mempolicy.h).
 */
private const val MPOL_DEFAULT = 0
private const val MPOL_PREFERRED = 1
private const val MPOL_BIND = 2
private const val MPOL_INTERLEAVE = 3
private const val MPOL_LOCAL = 4

/**
 * NUMA memory policy flags.
 */
private const val MPOL_F_STATIC_NODES = 1 shl 15 // 0x8000
private const val MPOL_F_RELATIVE_NODES = 1 shl 14 // 0x4000

/**
 * Apply the OCI linux.memoryPolicy via set_mempolicy(2).
 * Called from both init and exec paths, before privilege drop.
 */
@OptIn(ExperimentalForeignApi::class)
fun applyMemoryPolicy(policy: LinuxMemoryPolicy?) {
    if (policy == null) return

    val mode = parseMode(policy.mode)
    val flags = parseFlags(policy.flags)

    // Parse node bitmask
    val nodeMask = if (!policy.nodes.isNullOrEmpty()) parseNodeMask(policy.nodes) else null
    val maxNode: Long

    if (nodeMask != null) {
        maxNode = nodeMask.size.toLong() * 64L
    } else {
        maxNode = 0L
    }

    // Validate: MPOL_DEFAULT must not specify nodes
    if (mode == MPOL_DEFAULT && nodeMask != null && maxNode > 0) {
        var total = 0L
        for (m in nodeMask) total += m.countOneBits()
        if (total > 0) {
            throw Exception("invalid memory policy: MPOL_DEFAULT mode requires 0 nodes but got $total")
        }
    }

    val modeWithFlags = mode or flags

    memScoped {
        val nodemaskAddr: Long
        if (nodeMask != null && nodeMask.isNotEmpty()) {
            val seg = allocArray<LongVar>(nodeMask.size)
            for (i in nodeMask.indices) {
                seg[i] = nodeMask[i]
            }
            nodemaskAddr = seg.toLong()
        } else {
            nodemaskAddr = 0L
        }

        val rc =
            syscall(
                _NR_set_mempolicy(),
                modeWithFlags.toLong(),
                nodemaskAddr,
                maxNode,
            )
        if (rc != 0L) {
            val err = strerror(errno)?.toKString() ?: "unknown"
            throw Exception("set_mempolicy failed: $err")
        }
        Logger.debug("set_mempolicy mode=${policy.mode} nodes=${policy.nodes}")
    }
}

private fun parseMode(mode: String?): Int {
    if (mode.isNullOrEmpty()) throw Exception("invalid memory policy mode: (empty)")
    return when (mode) {
        "MPOL_DEFAULT" -> MPOL_DEFAULT
        "MPOL_PREFERRED" -> MPOL_PREFERRED
        "MPOL_BIND" -> MPOL_BIND
        "MPOL_INTERLEAVE" -> MPOL_INTERLEAVE
        "MPOL_LOCAL" -> MPOL_LOCAL
        else -> throw Exception("invalid memory policy mode: $mode")
    }
}

private fun parseFlags(flags: List<String>?): Int {
    if (flags.isNullOrEmpty()) return 0
    var result = 0
    for (f in flags) {
        result = result or
            when (f) {
                "MPOL_F_STATIC_NODES" -> MPOL_F_STATIC_NODES
                "MPOL_F_RELATIVE_NODES" -> MPOL_F_RELATIVE_NODES
                else -> throw Exception("invalid memory policy flag: $f")
            }
    }
    return result
}

/**
 * Parse a Linux node-list string (e.g. "0", "0-3", "0,2,4") into a
 * bitmask represented as a LongArray. Each Long covers 64 bits.
 */
internal fun parseNodeMask(nodes: String): LongArray {
    val mask = LongArray(16) // up to 1024 nodes
    for (part in nodes.split(",")) {
        val trimmed = part.trim()
        if (trimmed.isEmpty()) continue
        val dash = trimmed.indexOf('-')
        if (dash >= 0) {
            val from =
                trimmed.substring(0, dash).toLongOrNull()
                    ?: throw Exception("invalid memory policy node: $trimmed")
            val to =
                trimmed.substring(dash + 1).toLongOrNull()
                    ?: throw Exception("invalid memory policy node: $trimmed")
            if (from < 0 || to < 0 || from > 8192 || to > 8192) {
                throw Exception("invalid memory policy node: $trimmed")
            }
            for (n in from..minOf(to, mask.size.toLong() * 64 - 1)) {
                mask[(n / 64).toInt()] = mask[(n / 64).toInt()] or (1L shl (n % 64).toInt())
            }
        } else {
            val n =
                trimmed.toLongOrNull()
                    ?: throw Exception("invalid memory policy node: $trimmed")
            if (n < 0 || n > 8192) throw Exception("invalid memory policy node: $trimmed")
            if (n < mask.size * 64L) {
                mask[(n / 64).toInt()] = mask[(n / 64).toInt()] or (1L shl (n % 64).toInt())
            }
        }
    }
    // Trim trailing zero longs
    var last = mask.size - 1
    while (last > 0 && mask[last] == 0L) last--
    return mask.copyOfRange(0, last + 1)
}
