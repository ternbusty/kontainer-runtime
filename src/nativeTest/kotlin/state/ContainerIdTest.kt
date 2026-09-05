package state

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ContainerIdTest :
    FunSpec({
        test("accepts runc-compatible ids") {
            listOf("bench", "abc-123", "a.b", "A_B+C", "0", "x".repeat(200)).forEach { id ->
                isValidContainerId(id) shouldBe true
            }
        }

        test("rejects ids that are not a safe single path component") {
            listOf(
                "",
                ".",
                "..",
                "../etc",
                "a/b",
                "x;touch /tmp/pwned",
                "with space",
                "tab\tid",
                "new\nline",
                "\$HOME",
                "id*",
                "ユニコード",
            ).forEach { id ->
                isValidContainerId(id) shouldBe false
            }
        }

        test("getContainerDir refuses to build a path from an invalid id") {
            getContainerDir("/run/kontainer", "ok-id") shouldBe "/run/kontainer/ok-id"
            shouldThrow<IllegalArgumentException> { getContainerDir("/run/kontainer", "../escape") }
            shouldThrow<IllegalArgumentException> { getContainerDir("/run/kontainer", "x;touch /tmp/pwned") }
        }
    })
