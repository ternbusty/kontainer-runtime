package rootfs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import spec.Namespace

class SysctlValidationTest :
    FunSpec({

        // Helper to build a namespace list with "new" namespaces (no path)
        fun newNs(vararg types: String) = types.map { Namespace(type = it) }

        // Helper to build a namespace that joins an existing one (has path)
        fun joinedNs(type: String) = Namespace(type = type, path = "/proc/1/ns/$type")

        test("null sysctls pass validation") {
            validateSysctls(null, newNs("network", "ipc"))
                .shouldBeEmpty()
        }

        test("empty sysctls pass validation") {
            validateSysctls(emptyMap(), newNs("network"))
                .shouldBeEmpty()
        }

        test("net.* allowed with new network namespace") {
            val errors =
                validateSysctls(
                    mapOf("net.ipv4.ip_forward" to "1", "net.core.somaxconn" to "128"),
                    newNs("network"),
                )
            errors.shouldBeEmpty()
        }

        test("net.* rejected without network namespace") {
            val errors = validateSysctls(mapOf("net.ipv4.ip_forward" to "1"), emptyList())
            errors shouldHaveSize 1
            errors[0] shouldContain "net.ipv4.ip_forward"
            errors[0] shouldContain "network namespace"
        }

        test("net.* rejected when network namespace is joined (has path)") {
            val errors =
                validateSysctls(
                    mapOf("net.ipv4.ip_forward" to "1"),
                    listOf(joinedNs("network")),
                )
            errors shouldHaveSize 1
            errors[0] shouldContain "network namespace"
        }

        test("fs.mqueue.* allowed with new IPC namespace") {
            val errors =
                validateSysctls(
                    mapOf("fs.mqueue.msg_max" to "10"),
                    newNs("ipc"),
                )
            errors.shouldBeEmpty()
        }

        test("fs.mqueue.* rejected without IPC namespace") {
            val errors =
                validateSysctls(
                    mapOf("fs.mqueue.msg_max" to "10"),
                    newNs("network"),
                )
            errors shouldHaveSize 1
            errors[0] shouldContain "IPC namespace"
        }

        test("IPC kernel sysctls allowed with new IPC namespace") {
            val ipcSysctls =
                mapOf(
                    "kernel.msgmax" to "8192",
                    "kernel.msgmnb" to "16384",
                    "kernel.msgmni" to "32000",
                    "kernel.sem" to "250 32000 32 128",
                    "kernel.shmall" to "2097152",
                    "kernel.shmmax" to "33554432",
                    "kernel.shmmni" to "4096",
                    "kernel.shm_rmid_forced" to "1",
                )
            val errors = validateSysctls(ipcSysctls, newNs("ipc"))
            errors.shouldBeEmpty()
        }

        test("IPC kernel sysctls rejected without IPC namespace") {
            val errors =
                validateSysctls(
                    mapOf("kernel.shmmax" to "33554432"),
                    newNs("network"),
                )
            errors shouldHaveSize 1
            errors[0] shouldContain "kernel.shmmax"
            errors[0] shouldContain "IPC namespace"
        }

        test("kernel.domainname allowed with new UTS namespace") {
            val errors =
                validateSysctls(
                    mapOf("kernel.domainname" to "example.com"),
                    newNs("uts"),
                )
            errors.shouldBeEmpty()
        }

        test("kernel.domainname rejected without UTS namespace") {
            val errors =
                validateSysctls(
                    mapOf("kernel.domainname" to "example.com"),
                    newNs("network"),
                )
            errors shouldHaveSize 1
            errors[0] shouldContain "UTS namespace"
        }

        test("kernel.hostname is never allowed") {
            val errors =
                validateSysctls(
                    mapOf("kernel.hostname" to "foo"),
                    newNs("uts"),
                )
            errors shouldHaveSize 1
            errors[0] shouldContain "not in the allowed list"
        }

        test("unknown sysctl key is rejected") {
            val errors =
                validateSysctls(
                    mapOf("vm.swappiness" to "60"),
                    newNs("network", "ipc", "uts"),
                )
            errors shouldHaveSize 1
            errors[0] shouldContain "vm.swappiness"
            errors[0] shouldContain "not in the allowed list"
        }

        test("multiple errors are collected") {
            val errors =
                validateSysctls(
                    mapOf(
                        "net.ipv4.ip_forward" to "1",
                        "kernel.hostname" to "evil",
                        "vm.overcommit_memory" to "1",
                    ),
                    emptyList(),
                )
            // net.* without network ns, hostname always rejected, vm.* always rejected
            errors shouldHaveSize 3
        }

        test("mixed valid and invalid keys") {
            val errors =
                validateSysctls(
                    mapOf(
                        "net.ipv4.ip_forward" to "1",
                        "kernel.shmmax" to "33554432",
                        "vm.swappiness" to "60",
                    ),
                    newNs("network", "ipc"),
                )
            // Only vm.swappiness should fail
            errors shouldHaveSize 1
            errors[0] shouldContain "vm.swappiness"
        }

        test("null namespaces rejects all namespace-gated sysctls") {
            val errors =
                validateSysctls(
                    mapOf("net.ipv4.ip_forward" to "1"),
                    null,
                )
            errors shouldHaveSize 1
            errors[0] shouldContain "network namespace"
        }
    })
