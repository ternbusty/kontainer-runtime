package state

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ContainerStatusTest :
    FunSpec({

        // canStart: only CREATED

        test("only CREATED can start") {
            ContainerStatus.CREATING.canStart() shouldBe false
            ContainerStatus.CREATED.canStart() shouldBe true
            ContainerStatus.RUNNING.canStart() shouldBe false
            ContainerStatus.STOPPED.canStart() shouldBe false
        }

        // canKill: CREATED and RUNNING

        test("only CREATED and RUNNING can be killed") {
            ContainerStatus.CREATING.canKill() shouldBe false
            ContainerStatus.CREATED.canKill() shouldBe true
            ContainerStatus.RUNNING.canKill() shouldBe true
            ContainerStatus.STOPPED.canKill() shouldBe false
        }

        // canDelete: only STOPPED

        test("only STOPPED can be deleted without force") {
            ContainerStatus.CREATING.canDelete() shouldBe false
            ContainerStatus.CREATED.canDelete() shouldBe false
            ContainerStatus.RUNNING.canDelete() shouldBe false
            ContainerStatus.STOPPED.canDelete() shouldBe true
        }

        // fromString

        test("fromString round-trips every enum value") {
            for (status in ContainerStatus.entries) {
                ContainerStatus.fromString(status.value) shouldBe status
            }
        }

        test("fromString throws on unknown status") {
            shouldThrow<IllegalArgumentException> {
                ContainerStatus.fromString("garbage")
            }
        }

        test("fromString is case-sensitive") {
            shouldThrow<IllegalArgumentException> {
                ContainerStatus.fromString("CREATED")
            }
        }
    })
