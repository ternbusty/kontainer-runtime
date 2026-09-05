package namespace

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import spec.Namespace

class NamespaceFlagsTest :
    FunSpec({

        context("calculateCloneFlags") {
            test("returns 0 for null") {
                calculateCloneFlags(null) shouldBe 0u
            }

            test("returns 0 for empty list") {
                calculateCloneFlags(emptyList()) shouldBe 0u
            }

            test("ignores unknown namespace types") {
                calculateCloneFlags(listOf(Namespace("bogus"))) shouldBe 0u
            }

            withTests(
                nameFn = { "produces a non-zero flag for $it" },
                ts = listOf("mount", "network", "uts", "ipc", "pid", "user"),
            ) { type ->
                calculateCloneFlags(listOf(Namespace(type))) shouldNotBe 0u
            }

            test("ORs flags together") {
                val pidOnly = calculateCloneFlags(listOf(Namespace("pid")))
                val mountOnly = calculateCloneFlags(listOf(Namespace("mount")))
                val combined = calculateCloneFlags(listOf(Namespace("pid"), Namespace("mount")))

                (combined and pidOnly) shouldBe pidOnly
                (combined and mountOnly) shouldBe mountOnly
            }

            test("is order-independent") {
                val a = calculateCloneFlags(listOf(Namespace("pid"), Namespace("mount")))
                val b = calculateCloneFlags(listOf(Namespace("mount"), Namespace("pid")))
                a shouldBe b
            }

            test("combines all six known namespaces") {
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
        }

        context("nsJoinList") {
            test("returns empty for null and empty specs") {
                nsJoinList(null) shouldBe emptyList()
                nsJoinList(emptyList()) shouldBe emptyList()
            }

            test("returns only spec-defined namespaces") {
                val joins = nsJoinList(listOf(Namespace("mount"), Namespace("pid")))
                joins.map { it.ociType } shouldBe listOf("mount", "pid")
            }

            test("ignores unknown namespace types") {
                nsJoinList(listOf(Namespace("bogus"))) shouldBe emptyList()
            }

            test("orders user first and pid last regardless of spec order") {
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

            test("maps OCI names to /proc names") {
                val joins = nsJoinList(listOf(Namespace("mount"), Namespace("network"), Namespace("uts")))
                joins.associate { it.ociType to it.procName } shouldBe
                    mapOf("mount" to "mnt", "network" to "net", "uts" to "uts")
            }

            test("carries a non-zero clone flag for every namespace") {
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
        }
    })
