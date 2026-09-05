package seccomp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import spec.LinuxSeccomp
import spec.LinuxSyscall

class SeccompActionTest :
    FunSpec({

        withTests(
            nameFn = { (action, expected) -> "parse maps $action to $expected" },
            ts =
                listOf(
                    "SCMP_ACT_ALLOW" to SeccompAction.Allow,
                    "SCMP_ACT_LOG" to SeccompAction.Log,
                    "SCMP_ACT_NOTIFY" to SeccompAction.Notify,
                    "SCMP_ACT_TRAP" to SeccompAction.Trap,
                    "SCMP_ACT_KILL_THREAD" to SeccompAction.KillThread,
                    "SCMP_ACT_KILL_PROCESS" to SeccompAction.KillProcess,
                    // SCMP_ACT_KILL is an alias for KILL_THREAD
                    "SCMP_ACT_KILL" to SeccompAction.KillThread,
                ),
        ) { (action, expected) ->
            SeccompAction.parse(action, null) shouldBe expected
        }

        test("ERRNO and TRACE carry errnoRet") {
            SeccompAction.parse("SCMP_ACT_ERRNO", 38u) shouldBe SeccompAction.Errno(38u)
            SeccompAction.parse("SCMP_ACT_TRACE", 7u) shouldBe SeccompAction.Trace(7u)
        }

        test("ERRNO and TRACE default to 1 (EPERM) when errnoRet is absent") {
            SeccompAction.parse("SCMP_ACT_ERRNO", null) shouldBe SeccompAction.Errno(1u)
            SeccompAction.parse("SCMP_ACT_TRACE", null) shouldBe SeccompAction.Trace(1u)
        }

        test("errnoRet is ignored for actions that do not use it") {
            SeccompAction.parse("SCMP_ACT_ALLOW", 13u) shouldBe SeccompAction.Allow
        }

        test("unknown action throws") {
            val e = shouldThrow<Exception> { SeccompAction.parse("SCMP_ACT_BOGUS", null) }
            e.message shouldContain "SCMP_ACT_BOGUS"
        }

        test("LinuxSyscall.parsedAction combines action and errnoRet") {
            LinuxSyscall(names = listOf("open"), action = "SCMP_ACT_ERRNO", errnoRet = 2u).parsedAction shouldBe
                SeccompAction.Errno(2u)
        }

        test("LinuxSeccomp.parsedDefaultAction combines defaultAction and defaultErrnoRet") {
            LinuxSeccomp(defaultAction = "SCMP_ACT_ERRNO", defaultErrnoRet = 38u).parsedDefaultAction shouldBe
                SeccompAction.Errno(38u)
            LinuxSeccomp(defaultAction = "SCMP_ACT_ALLOW").parsedDefaultAction shouldBe SeccompAction.Allow
        }

        test("seccompUsesNotify detects SCMP_ACT_NOTIFY rules") {
            val notify =
                LinuxSeccomp(
                    defaultAction = "SCMP_ACT_ALLOW",
                    syscalls =
                        listOf(
                            LinuxSyscall(names = listOf("mkdir"), action = "SCMP_ACT_ERRNO"),
                            LinuxSyscall(names = listOf("chmod"), action = "SCMP_ACT_NOTIFY"),
                        ),
                )
            seccompUsesNotify(notify) shouldBe true

            val plain =
                LinuxSeccomp(
                    defaultAction = "SCMP_ACT_ALLOW",
                    syscalls = listOf(LinuxSyscall(names = listOf("mkdir"), action = "SCMP_ACT_ERRNO")),
                )
            seccompUsesNotify(plain) shouldBe false
            seccompUsesNotify(LinuxSeccomp(defaultAction = "SCMP_ACT_ALLOW")) shouldBe false
        }
    })
