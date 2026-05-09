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
