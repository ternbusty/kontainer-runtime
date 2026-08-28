package process

import kotlinx.cinterop.*
import logger.Logger
import platform.linux.*
import platform.posix.*

/**
 * Send a pidfd for the current process over an already-connected Unix socket.
 *
 * The pidfd socket protocol (used by runc and compatible container managers):
 * 1. Open a pidfd for the current process via pidfd_open(getpid(), 0)
 * 2. Send the pidfd via SCM_RIGHTS over the pre-connected socket
 * 3. The message body is the init type: "standard" for init, "setns" for exec
 *
 * The caller (e.g. pidfd-kill) receives the pidfd and can use
 * pidfd_send_signal() to signal the container process.
 *
 * @param sockFd the already-connected Unix stream socket fd
 * @param initType the message body: "standard" for init processes, "setns" for exec processes
 */
@OptIn(ExperimentalForeignApi::class)
fun sendPidfd(
    sockFd: Int,
    initType: String,
) {
    // Open a pidfd for ourselves
    val pidfd = syscall(_NR_pidfd_open(), getpid(), 0).toInt()
    if (pidfd < 0) {
        Logger.warn("pidfd_open failed (errno=$errno)")
        close(sockFd)
        return
    }

    // Send the pidfd over SCM_RIGHTS with initType as the message body
    memScoped {
        val payload = initType.encodeToByteArray()
        val iov = alloc<iovec>()
        iov.iov_base = payload.refTo(0).getPointer(this)
        iov.iov_len = payload.size.toULong()

        val cmsgSpace = _CMSG_SPACE(sizeOf<IntVar>().toULong())
        val cmsgBuf = allocArray<ByteVar>(cmsgSpace.toInt())

        val msg = alloc<msghdr>()
        msg.msg_iov = iov.ptr
        msg.msg_iovlen = 1u
        msg.msg_control = cmsgBuf
        msg.msg_controllen = cmsgSpace

        val cmsg = _CMSG_FIRSTHDR(msg.ptr)
        if (cmsg == null) {
            Logger.warn("sendPidfd: _CMSG_FIRSTHDR returned null")
            close(pidfd)
            close(sockFd)
            return
        }
        cmsg.pointed.cmsg_level = SOL_SOCKET
        cmsg.pointed.cmsg_type = SCM_RIGHTS
        cmsg.pointed.cmsg_len = _CMSG_LEN(sizeOf<IntVar>().toULong())

        val fdPtr = _CMSG_DATA(cmsg)!!.reinterpret<IntVar>()
        fdPtr.pointed.value = pidfd

        val sent = sendmsg(sockFd, msg.ptr, 0)
        if (sent < 0) {
            Logger.warn("sendPidfd: sendmsg failed (errno=$errno)")
        } else {
            Logger.debug("sent pidfd (fd=$pidfd) via SCM_RIGHTS with initType=$initType")
        }
    }

    close(pidfd)
    close(sockFd)
}
