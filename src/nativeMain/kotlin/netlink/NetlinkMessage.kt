package netlink

import kotlinx.cinterop.*

// Message types (random values like runc)
const val INIT_MSG: UShort = 62000u

// Attribute types
const val CLONE_FLAGS_ATTR: UShort = 27281u
const val UIDMAP_ATTR: UShort = 27283u
const val GIDMAP_ATTR: UShort = 27284u
const val ROOTFS_PATH_ATTR: UShort = 27285u
const val BUNDLE_PATH_ATTR: UShort = 27286u
const val CONTAINER_ID_ATTR: UShort = 27287u
const val USER_NS_ATTR: UShort = 27288u

/**
 * Netlink message builder for bootstrap data
 * Similar to runc's message_linux.go
 */
@OptIn(ExperimentalForeignApi::class)
class NetlinkMessage(
    private val msgType: UShort,
) {
    private val attributes = mutableListOf<ByteArray>()

    /**
     * Add a 32-bit integer attribute
     * Format: | len(2) | type(2) | value(4) |
     */
    fun addInt32(
        attrType: UShort,
        value: UInt,
    ) {
        val buf = ByteArray(8) // NLA_HDRLEN(4) + value(4)
        // Length (2 bytes, little-endian)
        buf[0] = 8.toByte()
        buf[1] = 0
        // Type (2 bytes, little-endian)
        buf[2] = attrType.toByte()
        buf[3] = (attrType.toInt() shr 8).toByte()
        // Value (4 bytes, little-endian)
        buf[4] = value.toByte()
        buf[5] = (value shr 8).toByte()
        buf[6] = (value shr 16).toByte()
        buf[7] = (value shr 24).toByte()
        attributes.add(buf)
    }

    /**
     * Add a string attribute (null-terminated)
     * Format: | len(2) | type(2) | data... | \0 | padding |
     */
    fun addString(
        attrType: UShort,
        value: String,
    ) {
        val bytes = value.encodeToByteArray()
        val len = 4 + bytes.size + 1 // header + data + null terminator
        val aligned = (len + 3) and 3.inv() // NLA_ALIGN to 4-byte boundary
        val buf = ByteArray(aligned)

        // Length (2 bytes, little-endian)
        buf[0] = len.toByte()
        buf[1] = (len shr 8).toByte()
        // Type (2 bytes, little-endian)
        buf[2] = attrType.toByte()
        buf[3] = (attrType.toInt() shr 8).toByte()
        // Data
        bytes.copyInto(buf, 4)
        // Null terminator
        buf[4 + bytes.size] = 0
        // Padding is already zero-initialized

        attributes.add(buf)
    }

    /**
     * Serialize the message into a byte array
     * Format: | nlmsghdr | attributes... |
     */
    fun serialize(): ByteArray {
        val payloadSize = attributes.sumOf { it.size }
        val totalSize = 16 + payloadSize // NLMSG_HDRLEN(16) + payload

        val result = ByteArray(totalSize)

        // Netlink header (16 bytes)
        // nlmsg_len (4 bytes, little-endian)
        result[0] = totalSize.toByte()
        result[1] = (totalSize shr 8).toByte()
        result[2] = (totalSize shr 16).toByte()
        result[3] = (totalSize shr 24).toByte()
        // nlmsg_type (2 bytes, little-endian)
        result[4] = msgType.toByte()
        result[5] = (msgType.toInt() shr 8).toByte()
        // nlmsg_flags (2 bytes) = 0
        result[6] = 0
        result[7] = 0
        // nlmsg_seq (4 bytes) = 0
        result[8] = 0
        result[9] = 0
        result[10] = 0
        result[11] = 0
        // nlmsg_pid (4 bytes) = 0
        result[12] = 0
        result[13] = 0
        result[14] = 0
        result[15] = 0

        // Copy attributes
        var offset = 16
        for (attr in attributes) {
            attr.copyInto(result, offset)
            offset += attr.size
        }

        return result
    }
}
