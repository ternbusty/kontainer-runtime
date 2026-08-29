package network

import kotlinx.cinterop.*
import logger.Logger
import platform.linux._CLONE_NEWNET
import platform.linux.setns_wrapper
import platform.posix.*
import spec.LinuxNetDevice

/**
 * Network device operations using raw rtnetlink sockets.
 *
 * Supports moving host network interfaces into a container's network namespace
 * (by PID) and renaming them inside the container. Uses the netlink protocol
 * directly — no libnl dependency required.
 *
 * When a device moves between namespaces the kernel strips all IP addresses.
 * This module preserves permanent global-scope addresses by dumping them
 * before the move (RTM_GETADDR) and re-adding them afterwards (RTM_NEWADDR).
 */

// Netlink constants
private const val AF_NETLINK = 16
private const val NETLINK_ROUTE = 0

// Netlink message types
private const val RTM_SETLINK: Short = 19
private const val RTM_NEWADDR: Short = 20
private const val RTM_GETADDR: Short = 22

// Netlink message flags
private const val NLM_F_REQUEST: Short = 1
private const val NLM_F_ACK: Short = 4
private const val NLM_F_DUMP: Short = 0x300
private const val NLM_F_CREATE: Short = 0x400
private const val NLM_F_EXCL: Short = 0x200
private const val NLMSG_DONE: Short = 3
private const val NLMSG_ERROR: Short = 2

// Netlink attribute types (link)
private const val IFLA_IFNAME: Short = 3
private const val IFLA_NET_NS_PID: Short = 19

// Netlink attribute types (address)
private const val IFA_ADDRESS: Short = 1
private const val IFA_LOCAL: Short = 2
private const val IFA_FLAGS: Short = 8

// Address flags
private const val IFA_F_PERMANENT = 0x80

// Scope
private const val RT_SCOPE_UNIVERSE = 0

// Interface flags
private const val IFF_UP_FLAG = 1

// Struct sizes
private const val NLMSG_HDRLEN = 16
private const val IFINFOMSG_LEN = 16
private const val IFADDRMSG_LEN = 8

// ioctl for getting interface index
private const val SIOCGIFINDEX = 0x8933uL

/** A saved IPv4/IPv6 address to re-add after moving a device across namespaces. */
private data class SavedAddr(
    val family: Int,
    val prefixLen: Int,
    val address: ByteArray,
)

/**
 * Move host network devices into the container namespace identified by [nsPid].
 * Called from the main process (host namespace) after the init process has
 * created its namespaces.
 */
@OptIn(ExperimentalForeignApi::class)
fun moveDevices(
    devices: Map<String, LinuxNetDevice>,
    nsPid: Int,
) {
    if (devices.isEmpty()) return

    memScoped {
        for ((hostName, _) in devices) {
            val ifindex = getIfIndex(hostName)
            if (ifindex <= 0) {
                throw Exception("network device '$hostName' not found")
            }

            // Save permanent global-scope addresses BEFORE the move.
            // The kernel strips all addresses when a device moves
            // between network namespaces.
            val savedAddrs = listPermanentGlobalAddrs(ifindex)

            moveToNamespace(ifindex, nsPid)
            Logger.debug("moved device '$hostName' (ifindex=$ifindex) to pid=$nsPid")

            // Re-add saved addresses and bring the interface UP
            // in the container's network namespace.
            reAddAddressesAndLinkUp(nsPid, hostName, savedAddrs)
        }
    }
}

/**
 * Rename network devices inside the container namespace.
 * Called from the init process after namespace setup, before loopback bringup.
 */
@OptIn(ExperimentalForeignApi::class)
fun renameDevices(devices: Map<String, LinuxNetDevice>) {
    if (devices.isEmpty()) return

    for ((hostName, dev) in devices) {
        val newName = dev.name
        if (newName.isNullOrEmpty() || newName == hostName) continue

        val ifindex = getIfIndex(hostName)
        if (ifindex <= 0) {
            Logger.warn("rename: device '$hostName' not found, skipping")
            continue
        }
        renameDevice(ifindex, newName)
        Logger.debug("renamed '$hostName' to '$newName'")
    }
}

/**
 * Get the interface index for a named network device.
 */
@OptIn(ExperimentalForeignApi::class)
private fun getIfIndex(name: String): Int =
    memScoped {
        val fd = socket(AF_INET, SOCK_DGRAM, 0)
        if (fd < 0) return -1

        // struct ifreq: first IFNAMSIZ (16) bytes are the name,
        // then various union members. Total size varies but 40 bytes is enough.
        val ifr = allocArray<ByteVar>(40)
        for (i in 0 until 40) ifr[i] = 0

        // Copy interface name (max 15 chars + null)
        val nameBytes = name.encodeToByteArray()
        val copyLen = minOf(nameBytes.size, 15)
        for (i in 0 until copyLen) ifr[i] = nameBytes[i]
        ifr[copyLen] = 0

        val rc = ioctl(fd, SIOCGIFINDEX, ifr)
        close(fd)
        if (rc < 0) return -1

        // ifr_ifindex is at offset 16 in struct ifreq
        ifr.reinterpret<IntVar>()[4]
    }

/**
 * Move a network device to the namespace of [nsPid] via RTM_SETLINK + IFLA_NET_NS_PID.
 */
@OptIn(ExperimentalForeignApi::class)
private fun moveToNamespace(
    ifindex: Int,
    nsPid: Int,
) = memScoped {
    val nlaLen = 4 + 4 // rta header (2+2) + u32 payload
    val nlaPadded = align4(nlaLen)
    val totalLen = NLMSG_HDRLEN + IFINFOMSG_LEN + nlaPadded

    val msg = allocArray<ByteVar>(totalLen)
    for (i in 0 until totalLen) msg[i] = 0

    // nlmsghdr
    msg.reinterpret<IntVar>()[0] = totalLen // nlmsg_len
    msg.reinterpret<ShortVar>()[2] = RTM_SETLINK // nlmsg_type
    msg.reinterpret<ShortVar>()[3] = (NLM_F_REQUEST.toInt() or NLM_F_ACK.toInt()).toShort() // nlmsg_flags
    msg.reinterpret<IntVar>()[2] = 1 // nlmsg_seq
    msg.reinterpret<IntVar>()[3] = 0 // nlmsg_pid

    // ifinfomsg: ifi_family at byte NLMSG_HDRLEN, ifi_index at NLMSG_HDRLEN+4
    msg[NLMSG_HDRLEN] = 0 // ifi_family
    (msg + NLMSG_HDRLEN + 4)!!.reinterpret<IntVar>()[0] = ifindex // ifi_index

    // rtattr IFLA_NET_NS_PID
    val attrOff = NLMSG_HDRLEN + IFINFOMSG_LEN
    (msg + attrOff)!!.reinterpret<ShortVar>()[0] = nlaLen.toShort() // rta_len
    (msg + attrOff)!!.reinterpret<ShortVar>()[1] = IFLA_NET_NS_PID // rta_type
    (msg + attrOff + 4)!!.reinterpret<IntVar>()[0] = nsPid // pid value

    sendNetlink(msg, totalLen)
}

/**
 * Rename a network device via RTM_SETLINK + IFLA_IFNAME.
 */
@OptIn(ExperimentalForeignApi::class)
private fun renameDevice(
    ifindex: Int,
    newName: String,
) = memScoped {
    val nameBytes = newName.encodeToByteArray()
    val nlaLen = 4 + nameBytes.size + 1 // rta header + name + null
    val nlaPadded = align4(nlaLen)
    val totalLen = NLMSG_HDRLEN + IFINFOMSG_LEN + nlaPadded

    val msg = allocArray<ByteVar>(totalLen)
    for (i in 0 until totalLen) msg[i] = 0

    // nlmsghdr
    msg.reinterpret<IntVar>()[0] = totalLen
    msg.reinterpret<ShortVar>()[2] = RTM_SETLINK
    msg.reinterpret<ShortVar>()[3] = (NLM_F_REQUEST.toInt() or NLM_F_ACK.toInt()).toShort()
    msg.reinterpret<IntVar>()[2] = 1 // nlmsg_seq
    msg.reinterpret<IntVar>()[3] = 0 // nlmsg_pid

    // ifinfomsg
    msg[NLMSG_HDRLEN] = 0 // ifi_family
    (msg + NLMSG_HDRLEN + 4)!!.reinterpret<IntVar>()[0] = ifindex

    // rtattr IFLA_IFNAME
    val attrOff = NLMSG_HDRLEN + IFINFOMSG_LEN
    (msg + attrOff)!!.reinterpret<ShortVar>()[0] = nlaLen.toShort()
    (msg + attrOff)!!.reinterpret<ShortVar>()[1] = IFLA_IFNAME
    for (i in nameBytes.indices) {
        msg[attrOff + 4 + i] = nameBytes[i]
    }
    // null terminator is already there from zeroing

    sendNetlink(msg, totalLen)
}

/**
 * List permanent global-scope addresses on the given interface.
 * Called before a netns move so the addresses can be re-added afterwards.
 */
@OptIn(ExperimentalForeignApi::class)
private fun listPermanentGlobalAddrs(ifindex: Int): List<SavedAddr> {
    val result = mutableListOf<SavedAddr>()
    val sock = socket(AF_NETLINK, SOCK_DGRAM, NETLINK_ROUTE)
    if (sock < 0) return result
    try {
        memScoped {
            // Bind
            val sa = allocArray<ByteVar>(12)
            for (i in 0 until 12) sa[i] = 0
            sa.reinterpret<ShortVar>()[0] = AF_NETLINK.toShort()
            if (bind(sock, sa.reinterpret(), 12u) < 0) return result

            // RTM_GETADDR dump request
            val reqLen = NLMSG_HDRLEN + IFADDRMSG_LEN
            val req = allocArray<ByteVar>(reqLen)
            for (i in 0 until reqLen) req[i] = 0
            req.reinterpret<IntVar>()[0] = reqLen // nlmsg_len
            req.reinterpret<ShortVar>()[2] = RTM_GETADDR // nlmsg_type
            req.reinterpret<ShortVar>()[3] = (NLM_F_REQUEST.toInt() or NLM_F_DUMP.toInt()).toShort()
            req.reinterpret<IntVar>()[2] = 1 // nlmsg_seq
            if (send(sock, req, reqLen.toULong(), 0) < 0) return result

            // Read multipart response
            val bufSize = 65536
            val buf = allocArray<ByteVar>(bufSize)
            var done = false
            while (!done) {
                val n = recv(sock, buf, bufSize.toULong(), 0)
                if (n <= 0) break

                var offset = 0
                while (offset + NLMSG_HDRLEN <= n.toInt()) {
                    val msgLen = (buf + offset)!!.reinterpret<IntVar>()[0]
                    if (msgLen < NLMSG_HDRLEN || offset + msgLen > n.toInt()) break
                    val msgType = (buf + offset + 4)!!.reinterpret<ShortVar>()[0]

                    if (msgType == NLMSG_DONE || msgType == NLMSG_ERROR) {
                        done = true
                        break
                    }

                    if (msgType == RTM_NEWADDR && msgLen >= NLMSG_HDRLEN + IFADDRMSG_LEN) {
                        val base = offset + NLMSG_HDRLEN
                        val family = buf[base].toInt() and 0xFF
                        val prefLen = buf[base + 1].toInt() and 0xFF
                        val ifaFlags = buf[base + 2].toInt() and 0xFF
                        val scope = buf[base + 3].toInt() and 0xFF
                        val index = (buf + base + 4)!!.reinterpret<IntVar>()[0]

                        if (index == ifindex && scope == RT_SCOPE_UNIVERSE) {
                            var addrBytes: ByteArray? = null
                            var extFlags = ifaFlags
                            var attrOff = base + IFADDRMSG_LEN
                            val attrEnd = offset + align4(msgLen)

                            while (attrOff + 4 <= attrEnd) {
                                val rtaLen = (buf + attrOff)!!.reinterpret<ShortVar>()[0].toInt() and 0xFFFF
                                val rtaType = (buf + attrOff + 2)!!.reinterpret<ShortVar>()[0].toInt() and 0xFFFF
                                if (rtaLen < 4) break
                                val dataLen = rtaLen - 4

                                if (rtaType == IFA_LOCAL.toInt() ||
                                    (rtaType == IFA_ADDRESS.toInt() && addrBytes == null)
                                ) {
                                    addrBytes = ByteArray(dataLen)
                                    for (i in 0 until dataLen) {
                                        addrBytes[i] = buf[attrOff + 4 + i]
                                    }
                                } else if (rtaType == IFA_FLAGS.toInt() && dataLen >= 4) {
                                    extFlags = (buf + attrOff + 4)!!.reinterpret<IntVar>()[0]
                                }
                                attrOff += align4(rtaLen)
                            }

                            if (addrBytes != null && (extFlags and IFA_F_PERMANENT) != 0) {
                                result.add(SavedAddr(family, prefLen, addrBytes))
                                Logger.debug("saved addr on ifindex $ifindex family=$family /$prefLen")
                            }
                        }
                    }
                    offset += align4(msgLen)
                }
            }
        }
    } finally {
        close(sock)
    }
    return result
}

/**
 * Re-add saved addresses and bring the interface UP in the container's
 * network namespace. Creates a netlink socket inside the target namespace
 * via a temporary setns call, then operates through that socket from the
 * host namespace.
 */
@OptIn(ExperimentalForeignApi::class)
private fun reAddAddressesAndLinkUp(
    nsPid: Int,
    deviceName: String,
    savedAddrs: List<SavedAddr>,
) {
    if (savedAddrs.isEmpty()) return

    // Open namespace fds for the host and the container.
    val hostNsFd = open("/proc/self/ns/net", O_RDONLY)
    if (hostNsFd < 0) {
        Logger.warn("cannot open host netns: ${strerror(errno)?.toKString()}")
        return
    }
    val containerNsFd = open("/proc/$nsPid/ns/net", O_RDONLY)
    if (containerNsFd < 0) {
        Logger.warn("cannot open container netns for pid $nsPid")
        close(hostNsFd)
        return
    }

    // Enter the container's netns, create sockets there, then return.
    if (setns_wrapper(containerNsFd, _CLONE_NEWNET()) != 0) {
        Logger.warn("setns to container netns failed: ${strerror(errno)?.toKString()}")
        close(hostNsFd)
        close(containerNsFd)
        return
    }
    val nlSock = socket(AF_NETLINK, SOCK_DGRAM, NETLINK_ROUTE)
    val newIfindex = getIfIndex(deviceName)
    // Restore the host netns immediately.
    setns_wrapper(hostNsFd, _CLONE_NEWNET())
    close(hostNsFd)
    close(containerNsFd)

    if (nlSock < 0 || newIfindex <= 0) {
        Logger.warn("failed to create netlink socket or find '$deviceName' in container netns")
        if (nlSock >= 0) close(nlSock)
        return
    }

    try {
        memScoped {
            // Bind the netlink socket (created in container's netns).
            val sa = allocArray<ByteVar>(12)
            for (i in 0 until 12) sa[i] = 0
            sa.reinterpret<ShortVar>()[0] = AF_NETLINK.toShort()
            if (bind(nlSock, sa.reinterpret(), 12u) < 0) {
                Logger.warn("netlink bind in container ns failed")
                return
            }

            // Re-add each saved address.
            for (addr in savedAddrs) {
                addAddress(nlSock, newIfindex, addr)
            }

            // Bring the interface UP.
            setLinkUp(nlSock, newIfindex)
        }
    } finally {
        close(nlSock)
    }
}

/**
 * Send RTM_NEWADDR to add an address to the interface.
 */
@OptIn(ExperimentalForeignApi::class)
private fun MemScope.addAddress(
    sock: Int,
    ifindex: Int,
    addr: SavedAddr,
) {
    val addrLen = addr.address.size
    val nlaLocalLen = 4 + addrLen
    val nlaLocalPad = align4(nlaLocalLen)
    val nlaAddrLen = 4 + addrLen
    val nlaAddrPad = align4(nlaAddrLen)
    val totalLen = NLMSG_HDRLEN + IFADDRMSG_LEN + nlaLocalPad + nlaAddrPad

    val msg = allocArray<ByteVar>(totalLen)
    for (i in 0 until totalLen) msg[i] = 0

    // nlmsghdr
    msg.reinterpret<IntVar>()[0] = totalLen
    msg.reinterpret<ShortVar>()[2] = RTM_NEWADDR
    msg.reinterpret<ShortVar>()[3] =
        (NLM_F_REQUEST.toInt() or NLM_F_ACK.toInt() or NLM_F_CREATE.toInt() or NLM_F_EXCL.toInt()).toShort()
    msg.reinterpret<IntVar>()[2] = 1 // nlmsg_seq

    // ifaddrmsg
    val base = NLMSG_HDRLEN
    msg[base] = addr.family.toByte() // ifa_family
    msg[base + 1] = addr.prefixLen.toByte() // ifa_prefixlen
    msg[base + 2] = 0 // ifa_flags
    msg[base + 3] = RT_SCOPE_UNIVERSE.toByte() // ifa_scope
    (msg + base + 4)!!.reinterpret<IntVar>()[0] = ifindex // ifa_index

    // IFA_LOCAL
    var off = NLMSG_HDRLEN + IFADDRMSG_LEN
    (msg + off)!!.reinterpret<ShortVar>()[0] = nlaLocalLen.toShort()
    (msg + off + 2)!!.reinterpret<ShortVar>()[0] = IFA_LOCAL
    for (i in 0 until addrLen) {
        msg[off + 4 + i] = addr.address[i]
    }

    // IFA_ADDRESS
    off += nlaLocalPad
    (msg + off)!!.reinterpret<ShortVar>()[0] = nlaAddrLen.toShort()
    (msg + off + 2)!!.reinterpret<ShortVar>()[0] = IFA_ADDRESS
    for (i in 0 until addrLen) {
        msg[off + 4 + i] = addr.address[i]
    }

    if (send(sock, msg, totalLen.toULong(), 0) < 0) {
        Logger.warn("RTM_NEWADDR send failed: ${strerror(errno)?.toKString()}")
        return
    }
    val ack = allocArray<ByteVar>(256)
    val n = recv(sock, ack, 256u, 0)
    if (n >= 20) {
        val err = (ack + NLMSG_HDRLEN)!!.reinterpret<IntVar>()[0]
        if (err != 0) {
            Logger.warn("RTM_NEWADDR error: ${strerror(-err)?.toKString()}")
        }
    }
}

/**
 * Set the interface link state to UP via RTM_SETLINK.
 */
@OptIn(ExperimentalForeignApi::class)
private fun MemScope.setLinkUp(
    sock: Int,
    ifindex: Int,
) {
    val totalLen = NLMSG_HDRLEN + IFINFOMSG_LEN
    val msg = allocArray<ByteVar>(totalLen)
    for (i in 0 until totalLen) msg[i] = 0

    msg.reinterpret<IntVar>()[0] = totalLen
    msg.reinterpret<ShortVar>()[2] = RTM_SETLINK
    msg.reinterpret<ShortVar>()[3] = (NLM_F_REQUEST.toInt() or NLM_F_ACK.toInt()).toShort()
    msg.reinterpret<IntVar>()[2] = 1 // nlmsg_seq

    msg[NLMSG_HDRLEN] = 0 // ifi_family
    (msg + NLMSG_HDRLEN + 4)!!.reinterpret<IntVar>()[0] = ifindex // ifi_index
    // ifi_flags = IFF_UP, ifi_change = IFF_UP (change only the UP bit)
    (msg + NLMSG_HDRLEN + 8)!!.reinterpret<IntVar>()[0] = IFF_UP_FLAG // ifi_flags
    (msg + NLMSG_HDRLEN + 12)!!.reinterpret<IntVar>()[0] = IFF_UP_FLAG // ifi_change

    if (send(sock, msg, totalLen.toULong(), 0) < 0) {
        Logger.warn("RTM_SETLINK UP send failed: ${strerror(errno)?.toKString()}")
        return
    }
    val ack = allocArray<ByteVar>(256)
    val n = recv(sock, ack, 256u, 0)
    if (n >= 20) {
        val err = (ack + NLMSG_HDRLEN)!!.reinterpret<IntVar>()[0]
        if (err != 0) {
            Logger.warn("RTM_SETLINK UP error: ${strerror(-err)?.toKString()}")
        }
    }
}

/**
 * Send a netlink message and wait for the ACK.
 * Throws on any error.
 */
@OptIn(ExperimentalForeignApi::class)
private fun MemScope.sendNetlink(
    msg: CPointer<ByteVar>,
    len: Int,
) {
    val sock = socket(AF_NETLINK, SOCK_DGRAM, NETLINK_ROUTE)
    if (sock < 0) {
        throw Exception("netlink socket: ${strerror(errno)?.toKString()}")
    }
    try {
        // Bind to netlink
        val sa = allocArray<ByteVar>(12)
        for (i in 0 until 12) sa[i] = 0
        sa.reinterpret<ShortVar>()[0] = AF_NETLINK.toShort() // sa_family

        if (bind(sock, sa.reinterpret(), 12u) < 0) {
            throw Exception("netlink bind: ${strerror(errno)?.toKString()}")
        }

        if (send(sock, msg, len.toULong(), 0) < 0) {
            throw Exception("netlink send: ${strerror(errno)?.toKString()}")
        }

        // Read ACK
        val ack = allocArray<ByteVar>(1024)
        val n = recv(sock, ack, 1024u, 0)
        if (n < 20) {
            throw Exception("netlink: no ack received")
        }

        // Error code is at offset NLMSG_HDRLEN (16) in the response
        val err = (ack + NLMSG_HDRLEN)!!.reinterpret<IntVar>()[0]
        if (err != 0) {
            throw Exception("netlink: ${strerror(-err)?.toKString()}")
        }
    } finally {
        close(sock)
    }
}

/** Round up to next multiple of 4 (netlink attribute alignment). */
private fun align4(n: Int): Int = (n + 3) and 3.inv()
