package capability

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.cinterop.ExperimentalForeignApi
import platform.linux.PR_CAP_AMBIENT
import platform.linux.PR_CAP_AMBIENT_CLEAR_ALL
import platform.linux.PR_CAP_AMBIENT_RAISE
import platform.linux.PR_CAPBSET_DROP
import platform.linux.PR_SET_KEEPCAPS
import spec.LinuxCapabilities
import syscall.CapabilitySets
import syscall.FakeSyscall

@OptIn(ExperimentalForeignApi::class)
class CapabilityTest :
    FunSpec({

        // parseCapabilities

        test("parseCapabilities returns empty set for null") {
            parseCapabilities(null) shouldBe emptySet()
        }

        test("parseCapabilities returns empty set for empty list") {
            parseCapabilities(emptyList()) shouldBe emptySet()
        }

        test("parseCapabilities maps OCI capability names to enum values") {
            val parsed = parseCapabilities(listOf("CAP_KILL", "CAP_NET_RAW"))
            parsed shouldContain Capability.KILL
            parsed shouldContain Capability.NET_RAW
            parsed.size shouldBe 2
        }

        test("parseCapabilities skips unknown names") {
            val parsed = parseCapabilities(listOf("CAP_KILL", "CAP_BOGUS_NONEXISTENT"))
            parsed shouldBe setOf(Capability.KILL)
        }

        // applyBoundingSet

        test("applyBoundingSet does not invoke prctl when bounding is null") {
            val syscall = FakeSyscall()
            applyBoundingSet(syscall, LinuxCapabilities(bounding = null))
            syscall.calls.count { it.startsWith("prctl(") } shouldBe 0
        }

        test("applyBoundingSet drops every capability not in the bounding set") {
            val syscall = FakeSyscall()
            applyBoundingSet(syscall, LinuxCapabilities(bounding = listOf("CAP_KILL")))

            val prctlDropCalls =
                syscall.calls.filter { it.startsWith("prctl(option=$PR_CAPBSET_DROP,") }
            (prctlDropCalls.size > 0) shouldBe true

            val keptValue = Capability.KILL.value.toULong()
            prctlDropCalls.forEach { call ->
                call shouldNotContain "arg2=$keptValue,"
            }
        }

        test("applyBoundingSet with empty list drops every capability") {
            val syscall = FakeSyscall()
            applyBoundingSet(syscall, LinuxCapabilities(bounding = emptyList()))

            val prctlDropCalls =
                syscall.calls.filter { it.startsWith("prctl(option=$PR_CAPBSET_DROP,") }
            (prctlDropCalls.size > 0) shouldBe true
        }

        // setKeepCaps / clearKeepCaps

        test("setKeepCaps invokes prctl(PR_SET_KEEPCAPS, 1, 0, 0, 0)") {
            val syscall = FakeSyscall()
            setKeepCaps(syscall)
            syscall.calls shouldContain "prctl(option=$PR_SET_KEEPCAPS, arg2=1, arg3=0, arg4=0, arg5=0)"
        }

        test("clearKeepCaps invokes prctl(PR_SET_KEEPCAPS, 0, 0, 0, 0)") {
            val syscall = FakeSyscall()
            clearKeepCaps(syscall)
            syscall.calls shouldContain "prctl(option=$PR_SET_KEEPCAPS, arg2=0, arg3=0, arg4=0, arg5=0)"
        }

        // applyCapabilities

        test("applyCapabilities calls setCapabilities with the requested masks") {
            val syscall = FakeSyscall()
            applyCapabilities(
                syscall,
                LinuxCapabilities(
                    effective = listOf("CAP_KILL"),
                    permitted = listOf("CAP_KILL", "CAP_NET_RAW"),
                    inheritable = emptyList(),
                ),
            )

            // Mask values: bit position equals the capability's value
            val killBit = 1UL shl Capability.KILL.value
            val netRawBit = 1UL shl Capability.NET_RAW.value
            val effective = killBit.toUInt()
            val permitted = (killBit or netRawBit).toUInt()
            val inheritable = 0u

            syscall.calls shouldContain
                "setCapabilities(effective=$effective, permitted=$permitted, inheritable=$inheritable)"
        }

        test("applyCapabilities does not touch ambient when not provided") {
            val syscall = FakeSyscall()
            applyCapabilities(
                syscall,
                LinuxCapabilities(
                    effective = listOf("CAP_KILL"),
                    ambient = null,
                ),
            )
            syscall.calls.count { it.startsWith("prctl(option=$PR_CAP_AMBIENT,") } shouldBe 0
        }

        test("applyCapabilities clears ambient and raises each requested capability") {
            val syscall = FakeSyscall()
            applyCapabilities(
                syscall,
                LinuxCapabilities(ambient = listOf("CAP_KILL")),
            )

            // First a clear-all, then a raise per ambient cap
            syscall.calls shouldContain
                "prctl(option=$PR_CAP_AMBIENT, arg2=${PR_CAP_AMBIENT_CLEAR_ALL.toULong()}, arg3=0, arg4=0, arg5=0)"
            val killValue = Capability.KILL.value.toULong()
            syscall.calls shouldContain
                "prctl(option=$PR_CAP_AMBIENT, arg2=${PR_CAP_AMBIENT_RAISE.toULong()}, arg3=$killValue, arg4=0, arg5=0)"
        }

        // resetEffective

        test("resetEffective copies permitted into effective and preserves inheritable") {
            val syscall =
                FakeSyscall().apply {
                    capabilities = CapabilitySets(effective = 0uL, permitted = 42uL, inheritable = 7uL)
                }

            resetEffective(syscall)

            syscall.calls shouldContain "getCapabilities()"
            syscall.calls shouldContain
                "setCapabilities(effective=42, permitted=42, inheritable=7)"

            // The fake's stored capabilities are also updated as a sanity check
            syscall.capabilities.effective shouldBe 42uL
            syscall.capabilities.permitted shouldBe 42uL
            syscall.capabilities.inheritable shouldBe 7uL
            syscall.capabilities shouldNotBe CapabilitySets(0uL, 42uL, 7uL)
        }
    })
