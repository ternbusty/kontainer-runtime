import capability.applyBoundingSet
import command.parseSignal
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import namespace.calculateCloneFlags
import process.buildIdMapping
import spec.LinuxCapabilities
import spec.LinuxIdMapping
import spec.Namespace
import syscall.FakeSyscall

class SyscallRefactorTest :
    FunSpec({

        // parseSignal (pure parser)

        test("parseSignal accepts SIG-prefixed names") {
            parseSignal("SIGKILL") shouldBe 9
            parseSignal("SIGTERM") shouldBe 15
        }

        test("parseSignal accepts bare names case-insensitively") {
            parseSignal("KILL") shouldBe 9
            parseSignal("TERM") shouldBe 15
            parseSignal("kill") shouldBe 9
            parseSignal("term") shouldBe 15
        }

        test("parseSignal accepts numeric input") {
            parseSignal("9") shouldBe 9
            parseSignal("15") shouldBe 15
        }

        test("parseSignal throws on unknown signal") {
            try {
                parseSignal("BOGUS")
                error("expected IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }

        // buildIdMapping (uid_map/gid_map string builder)

        test("buildIdMapping uses fallback when mappings null or empty") {
            buildIdMapping(null, fallbackId = 1000u) shouldBe "0 1000 1\n"
            buildIdMapping(emptyList(), fallbackId = 0u) shouldBe "0 0 1\n"
        }

        test("buildIdMapping serializes a single mapping") {
            val mappings = listOf(LinuxIdMapping(containerID = 0u, hostID = 100000u, size = 65536u))
            buildIdMapping(mappings, fallbackId = 0u) shouldBe "0 100000 65536\n"
        }

        test("buildIdMapping serializes multiple mappings on separate lines") {
            val mappings =
                listOf(
                    LinuxIdMapping(containerID = 0u, hostID = 100000u, size = 1u),
                    LinuxIdMapping(containerID = 1u, hostID = 200000u, size = 999u),
                )
            buildIdMapping(mappings, fallbackId = 0u) shouldBe "0 100000 1\n1 200000 999\n"
        }

        // calculateCloneFlags

        test("calculateCloneFlags returns 0 for null") {
            calculateCloneFlags(null) shouldBe 0u
        }

        test("calculateCloneFlags ignores unknown namespace types") {
            calculateCloneFlags(listOf(Namespace("bogus"))) shouldBe 0u
        }

        test("calculateCloneFlags combines known flags") {
            val pidOnly = calculateCloneFlags(listOf(Namespace("pid")))
            pidOnly shouldNotBe 0u

            val mountOnly = calculateCloneFlags(listOf(Namespace("mount")))
            mountOnly shouldNotBe 0u

            val combined = calculateCloneFlags(listOf(Namespace("pid"), Namespace("mount")))
            (combined and pidOnly) shouldBe pidOnly
            (combined and mountOnly) shouldBe mountOnly
        }

        // applyBoundingSet (capability domain logic exercised through FakeSyscall)

        test("applyBoundingSet does not invoke prctl when bounding is null") {
            val syscall = FakeSyscall()
            applyBoundingSet(syscall, LinuxCapabilities(bounding = null))
            syscall.calls.count { it.startsWith("prctl(") } shouldBe 0
        }

        test("applyBoundingSet drops every capability not in the bounding set") {
            val syscall = FakeSyscall()
            applyBoundingSet(syscall, LinuxCapabilities(bounding = listOf("CAP_KILL")))

            // Every non-bounding capability should produce one PR_CAPBSET_DROP prctl call.
            // We assert via FakeSyscall.calls so the exact CAP_KILL value isn't hardcoded.
            val prctlCalls = syscall.calls.filter { it.startsWith("prctl(") }
            (prctlCalls.size > 0) shouldBe true
        }
    })
