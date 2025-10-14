import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValuesOf
import kotlinx.cinterop.memScoped
import platform.linux.__NR_unshare
import platform.posix.*

private const val CLONE_NEWPID: Int = 0x20000000

@OptIn(ExperimentalForeignApi::class)
fun main() = memScoped {
    fprintf(stderr, "parent getpid=%d getppid=%d\n", getpid(), getppid())
    val rc = syscall(__NR_unshare.toLong(), CLONE_NEWPID)
    if (rc != 0L) {
        perror("unshare")
        exit(1)
    }
    val pid = fork()
    if (pid == -1) {
        perror("fork")
        exit(1)
    }
    if (pid == 0) {
        fprintf(stderr, "child getpid=%d getppid=%d\n", getpid(), getppid())
        _exit(0)
    } else {
        var status = 0
        if (waitpid(pid, cValuesOf(status), 0) == -1) {
            perror("waitpid")
            exit(1)
        }
    }
}
