import kotlinx.cinterop.*
import platform.linux.__NR_unshare
import platform.posix.*

private const val CLONE_NEWPID: Int = 0x20000000

@OptIn(ExperimentalForeignApi::class)
fun main(): kotlin.Unit = memScoped {
    fprintf(stderr, "parent getpid=%d getppid=%d\n", getpid(), getppid())

    val sv = IntArray(2)
    if (socketpair(AF_UNIX, SOCK_SEQPACKET, 0, sv.refTo(0)) != 0) {
        perror("socketpair")
        exit(1)
    }

    val rc = syscall(__NR_unshare.toLong(), CLONE_NEWPID)
    if (rc == -1L) {
        perror("unshare")
        exit(1)
    }

    val pid = fork()
    when (pid) {
        -1 -> {
            perror("fork")
            exit(1)
        }

        0 -> {
            close(sv[0])
            fprintf(stderr, "child getpid=%d getppid=%d\n", getpid(), getppid())

            val msg = "namespace:ready\n"
            val sent = send(sv[1], msg.cstr.ptr, msg.length.toULong(), 0)
            if (sent == -1L) {
                perror("send")
            }
            close(sv[1])
            _exit(0)
        }

        else -> {
            close(sv[1])

            val buf = allocArray<ByteVar>(256)
            val n = recv(sv[0], buf, 255.toULong(), 0)
            if (n == -1L) {
                perror("recv")
                close(sv[0])
            } else {
                buf.set(n, 0.toByte())
                val s = buf.toKString()
                fprintf(stderr, "parent received: %s", s)
            }
            close(sv[0])

            val status = alloc<IntVar>()
            if (waitpid(pid, status.ptr, 0) == -1) {
                perror("waitpid")
                exit(1)
            }
        }
    }
}
