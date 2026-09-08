package namespace

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import spec.Namespace
import spec.NamespaceType

class NamespaceFlagsTest :
    FunSpec({

        context("calculateCloneFlags") {
            test("returns 0 for null") {
                calculateCloneFlags(null) shouldBe 0u
            }

            test("returns 0 for empty list") {
                calculateCloneFlags(emptyList()) shouldBe 0u
            }

            for (type in NamespaceType.entries) {
                test("produces a non-zero flag for ${type.value}") {
                    calculateCloneFlags(listOf(Namespace(type))) shouldNotBe 0u
                }
            }

            test("ORs flags together") {
                val pidOnly = calculateCloneFlags(listOf(Namespace(NamespaceType.PID)))
                val mountOnly = calculateCloneFlags(listOf(Namespace(NamespaceType.MOUNT)))
                val combined =
                    calculateCloneFlags(
                        listOf(Namespace(NamespaceType.PID), Namespace(NamespaceType.MOUNT)),
                    )

                (combined and pidOnly) shouldBe pidOnly
                (combined and mountOnly) shouldBe mountOnly
            }

            test("is order-independent") {
                val a =
                    calculateCloneFlags(
                        listOf(Namespace(NamespaceType.PID), Namespace(NamespaceType.MOUNT)),
                    )
                val b =
                    calculateCloneFlags(
                        listOf(Namespace(NamespaceType.MOUNT), Namespace(NamespaceType.PID)),
                    )
                a shouldBe b
            }

            test("combines all known namespaces") {
                val all =
                    calculateCloneFlags(
                        NamespaceType.entries.map { Namespace(it) },
                    )
                val pidOnly = calculateCloneFlags(listOf(Namespace(NamespaceType.PID)))
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
                val joins =
                    nsJoinList(
                        listOf(Namespace(NamespaceType.MOUNT), Namespace(NamespaceType.PID)),
                    )
                joins.map { it.ociType } shouldBe listOf(NamespaceType.MOUNT, NamespaceType.PID)
            }

            test("orders user first and pid last regardless of spec order") {
                val joins =
                    nsJoinList(
                        listOf(
                            Namespace(NamespaceType.PID),
                            Namespace(NamespaceType.MOUNT),
                            Namespace(NamespaceType.USER),
                            Namespace(NamespaceType.NETWORK),
                        ),
                    )
                joins.first().ociType shouldBe NamespaceType.USER
                joins.last().ociType shouldBe NamespaceType.PID
            }

            test("maps OCI names to /proc names") {
                val joins =
                    nsJoinList(
                        listOf(
                            Namespace(NamespaceType.MOUNT),
                            Namespace(NamespaceType.NETWORK),
                            Namespace(NamespaceType.UTS),
                        ),
                    )
                joins.associate { it.ociType to it.procName } shouldBe
                    mapOf(
                        NamespaceType.MOUNT to "mnt",
                        NamespaceType.NETWORK to "net",
                        NamespaceType.UTS to "uts",
                    )
            }

            test("carries a non-zero clone flag for every namespace") {
                val joins =
                    nsJoinList(
                        listOf(
                            Namespace(NamespaceType.MOUNT),
                            Namespace(NamespaceType.NETWORK),
                            Namespace(NamespaceType.UTS),
                            Namespace(NamespaceType.IPC),
                            Namespace(NamespaceType.PID),
                            Namespace(NamespaceType.USER),
                            Namespace(NamespaceType.CGROUP),
                        ),
                    )
                joins.size shouldBe 7
                joins.forEach { it.cloneFlag shouldNotBe 0 }
            }
        }
    })
