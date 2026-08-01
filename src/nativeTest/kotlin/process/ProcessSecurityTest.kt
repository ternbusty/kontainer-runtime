package process

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import platform.linux.PR_CAPBSET_DROP
import platform.linux.PR_SET_KEEPCAPS
import spec.LinuxCapabilities
import spec.LinuxSeccomp
import spec.Process
import spec.User
import syscall.FakeSyscall

/**
 * Verifies the mandatory ordering of the shared spec.process security
 * sequence used by both the init (create) path and exec. The ordering
 * constraints are load-bearing (see applyProcessSecurity docs): seccomp
 * before cap drop, setgid before setuid, keepcaps around the uid change.
 */
class ProcessSecurityTest :
    FunSpec({

        fun fullProcess() =
            Process(
                args = listOf("sh"),
                cwd = "/",
                noNewPrivileges = true,
                user = User(uid = 1000u, gid = 1000u, additionalGids = listOf(10u, 20u)),
                capabilities =
                    LinuxCapabilities(
                        bounding = listOf("CAP_KILL"),
                        effective = listOf("CAP_KILL"),
                        inheritable = emptyList(),
                        permitted = listOf("CAP_KILL"),
                        ambient = emptyList(),
                    ),
            )

        test("applyProcessSecurity applies every step in the mandatory order") {
            val fake = FakeSyscall()

            applyProcessSecurity(
                fake,
                fullProcess(),
                LinuxSeccomp(defaultAction = "SCMP_ACT_ALLOW"),
                applySeccomp = {
                    fake.calls += "loadSeccomp()"
                    null
                },
                onSeccompNotifyFd = { error("no notify FD expected") },
            )

            fun idx(prefix: String): Int {
                val i = fake.calls.indexOfFirst { it.startsWith(prefix) }
                (i >= 0) shouldBe true
                return i
            }

            idx("umask") shouldBeLessThan idx("setNoNewPrivileges")
            idx("setNoNewPrivileges") shouldBeLessThan idx("loadSeccomp")
            idx("loadSeccomp") shouldBeLessThan idx("prctl(option=$PR_CAPBSET_DROP")
            idx("prctl(option=$PR_CAPBSET_DROP") shouldBeLessThan idx("prctl(option=$PR_SET_KEEPCAPS, arg2=1")
            idx("prctl(option=$PR_SET_KEEPCAPS, arg2=1") shouldBeLessThan idx("setAdditionalGroups")
            idx("setAdditionalGroups") shouldBeLessThan idx("setgid")
            idx("setgid") shouldBeLessThan idx("setuid")
            idx("setuid") shouldBeLessThan idx("prctl(option=$PR_SET_KEEPCAPS, arg2=0")
            idx("prctl(option=$PR_SET_KEEPCAPS, arg2=0") shouldBeLessThan idx("setCapabilities")
        }

        test("applyProcessSecurity sets the spec uid and gid") {
            val fake = FakeSyscall()

            applyProcessSecurity(fake, fullProcess(), seccomp = null) { }

            fake.calls.any { it == "setgid(gid=1000)" } shouldBe true
            fake.calls.any { it == "setuid(uid=1000)" } shouldBe true
        }

        test("applyProcessSecurity does not apply rlimits (callers apply them dead-last)") {
            val fake = FakeSyscall()

            applyProcessSecurity(fake, fullProcess(), seccomp = null) { }

            fake.calls.any { it.startsWith("applyRlimits") } shouldBe false
        }

        test("onSeccompNotifyFd is invoked with the notify FD when seccomp returns one") {
            val fake = FakeSyscall()
            var notified: Int? = null

            applyProcessSecurity(
                fake,
                fullProcess(),
                LinuxSeccomp(defaultAction = "SCMP_ACT_ALLOW"),
                applySeccomp = { 5 },
                onSeccompNotifyFd = { notified = it },
            )

            notified shouldBe 5
        }

        test("onSeccompNotifyFd is not invoked when seccomp returns no notify FD") {
            val fake = FakeSyscall()
            var notified = false

            applyProcessSecurity(
                fake,
                fullProcess(),
                LinuxSeccomp(defaultAction = "SCMP_ACT_ALLOW"),
                applySeccomp = { null },
                onSeccompNotifyFd = { notified = true },
            )

            notified shouldBe false
        }

        test("setgid failure throws and setuid is never reached") {
            val fake = FakeSyscall()
            fake.returnValues["setgid"] = -1

            shouldThrow<Exception> {
                applyProcessSecurity(fake, fullProcess(), seccomp = null) { }
            }

            fake.calls.any { it.startsWith("setuid") } shouldBe false
        }

        test("minimal spec triggers no capability or seccomp calls") {
            val fake = FakeSyscall()

            applyProcessSecurity(
                fake,
                Process(args = listOf("sh")),
                seccomp = null,
                applySeccomp = {
                    fake.calls += "loadSeccomp()"
                    null
                },
                onSeccompNotifyFd = { error("no notify FD expected") },
            )

            fake.calls.any { it.startsWith("prctl") } shouldBe false
            fake.calls.any { it.startsWith("setCapabilities") } shouldBe false
            fake.calls.any { it == "loadSeccomp()" } shouldBe false
            fake.calls.any { it.startsWith("setAdditionalGroups") } shouldBe false
            // uid/gid still applied (defaults to 0)
            fake.calls.any { it == "setgid(gid=0)" } shouldBe true
            fake.calls.any { it == "setuid(uid=0)" } shouldBe true
        }
    })
