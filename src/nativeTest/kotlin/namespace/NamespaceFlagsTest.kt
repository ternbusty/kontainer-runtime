package namespace

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import spec.Namespace

class NamespaceFlagsTest :
    FunSpec({

        test("calculateCloneFlags returns 0 for null") {
            calculateCloneFlags(null) shouldBe 0u
        }

        test("calculateCloneFlags returns 0 for empty list") {
            calculateCloneFlags(emptyList()) shouldBe 0u
        }

        test("calculateCloneFlags ignores unknown namespace types") {
            calculateCloneFlags(listOf(Namespace("bogus"))) shouldBe 0u
        }

        test("each known namespace produces a non-zero flag") {
            for (type in listOf("mount", "network", "uts", "ipc", "pid", "user")) {
                calculateCloneFlags(listOf(Namespace(type))) shouldNotBe 0u
            }
        }

        test("calculateCloneFlags ORs flags together") {
            val pidOnly = calculateCloneFlags(listOf(Namespace("pid")))
            val mountOnly = calculateCloneFlags(listOf(Namespace("mount")))
            val combined = calculateCloneFlags(listOf(Namespace("pid"), Namespace("mount")))

            (combined and pidOnly) shouldBe pidOnly
            (combined and mountOnly) shouldBe mountOnly
        }

        test("calculateCloneFlags is order-independent") {
            val a = calculateCloneFlags(listOf(Namespace("pid"), Namespace("mount")))
            val b = calculateCloneFlags(listOf(Namespace("mount"), Namespace("pid")))
            a shouldBe b
        }

        test("nsJoinList returns empty for null and empty specs") {
            nsJoinList(null) shouldBe emptyList()
            nsJoinList(emptyList()) shouldBe emptyList()
        }

        test("nsJoinList returns only spec-defined namespaces") {
            val joins = nsJoinList(listOf(Namespace("mount"), Namespace("pid")))
            joins.map { it.ociType } shouldBe listOf("mount", "pid")
        }

        test("nsJoinList ignores unknown namespace types") {
            nsJoinList(listOf(Namespace("bogus"))) shouldBe emptyList()
        }

        test("nsJoinList orders user first and pid last regardless of spec order") {
            val joins =
                nsJoinList(
                    listOf(
                        Namespace("pid"),
                        Namespace("mount"),
                        Namespace("user"),
                        Namespace("network"),
                    ),
                )
            joins.first().ociType shouldBe "user"
            joins.last().ociType shouldBe "pid"
        }

        test("nsJoinList maps OCI names to /proc names") {
            val joins = nsJoinList(listOf(Namespace("mount"), Namespace("network"), Namespace("uts")))
            joins.associate { it.ociType to it.procName } shouldBe
                mapOf("mount" to "mnt", "network" to "net", "uts" to "uts")
        }

        test("nsJoinList carries a non-zero clone flag for every namespace") {
            val joins =
                nsJoinList(
                    listOf(
                        Namespace("mount"),
                        Namespace("network"),
                        Namespace("uts"),
                        Namespace("ipc"),
                        Namespace("pid"),
                        Namespace("user"),
                        Namespace("cgroup"),
                    ),
                )
            joins.size shouldBe 7
            joins.forEach { it.cloneFlag shouldNotBe 0 }
        }

        test("calculateCloneFlags combines all six known namespaces") {
            val all =
                calculateCloneFlags(
                    listOf(
                        Namespace("mount"),
                        Namespace("network"),
                        Namespace("uts"),
                        Namespace("ipc"),
                        Namespace("pid"),
                        Namespace("user"),
                    ),
                )
            val pidOnly = calculateCloneFlags(listOf(Namespace("pid")))
            (all and pidOnly) shouldBe pidOnly
            (all > pidOnly) shouldBe true
        }
    })
