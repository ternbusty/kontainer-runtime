package command

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class KillTest :
    FunSpec({

        test("parseSignal accepts SIG-prefixed names") {
            parseSignal("SIGKILL") shouldBe 9
            parseSignal("SIGTERM") shouldBe 15
            parseSignal("SIGHUP") shouldBe 1
        }

        test("parseSignal accepts bare names case-insensitively") {
            parseSignal("KILL") shouldBe 9
            parseSignal("TERM") shouldBe 15
            parseSignal("kill") shouldBe 9
            parseSignal("term") shouldBe 15
            parseSignal("Kill") shouldBe 9
        }

        test("parseSignal accepts numeric input") {
            parseSignal("9") shouldBe 9
            parseSignal("15") shouldBe 15
            parseSignal("1") shouldBe 1
        }

        test("parseSignal returns numeric input as-is even outside known signals") {
            // The numeric path bypasses validation against known signals.
            parseSignal("99") shouldBe 99
        }

        test("parseSignal throws on unknown name") {
            shouldThrow<IllegalArgumentException> {
                parseSignal("BOGUS")
            }
        }

        test("parseSignal throws on empty string") {
            shouldThrow<IllegalArgumentException> {
                parseSignal("")
            }
        }
    })
