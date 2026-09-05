package command

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.shouldBe

class KillTest :
    FunSpec({

        withTests(
            nameFn = { (input, expected) -> "parseSignal(\"$input\") == $expected" },
            ts =
                listOf(
                    // SIG-prefixed names
                    "SIGKILL" to 9,
                    "SIGTERM" to 15,
                    "SIGHUP" to 1,
                    // bare names, case-insensitive
                    "KILL" to 9,
                    "TERM" to 15,
                    "kill" to 9,
                    "term" to 15,
                    "Kill" to 9,
                    // numeric input
                    "9" to 9,
                    "15" to 15,
                    "1" to 1,
                ),
        ) { (input, expected) ->
            parseSignal(input) shouldBe expected
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
