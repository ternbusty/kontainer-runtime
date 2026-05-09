package process

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import spec.LinuxIdMapping

class MainProcessTest :
    FunSpec({

        test("buildIdMapping uses the fallback id for null mappings") {
            buildIdMapping(null, fallbackId = 1000u) shouldBe "0 1000 1\n"
        }

        test("buildIdMapping uses the fallback id for empty mappings") {
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

        test("buildIdMapping output always ends with a newline") {
            buildIdMapping(null, fallbackId = 5u).endsWith("\n") shouldBe true
            buildIdMapping(
                listOf(LinuxIdMapping(0u, 0u, 1u)),
                fallbackId = 0u,
            ).endsWith("\n") shouldBe true
        }
    })
