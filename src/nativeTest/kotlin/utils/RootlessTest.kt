package utils

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.geteuid
import platform.posix.setenv
import platform.posix.unsetenv

/**
 * Tests for [defaultRootPath]: rootless runtimes use $XDG_RUNTIME_DIR like
 * runc, root always uses /run/kontainer. The expected value depends on who
 * runs the tests, so each case checks the branch that applies.
 */
@OptIn(ExperimentalForeignApi::class)
class RootlessTest :
    FunSpec({
        val saved = getenv("XDG_RUNTIME_DIR")?.toKString()

        afterEach {
            if (saved != null) setenv("XDG_RUNTIME_DIR", saved, 1) else unsetenv("XDG_RUNTIME_DIR")
        }

        test("isRootless reflects the effective uid") {
            isRootless() shouldBe (geteuid() != 0u)
        }

        test("uses XDG_RUNTIME_DIR when rootless, /run/kontainer as root") {
            setenv("XDG_RUNTIME_DIR", "/run/user/4242", 1)
            defaultRootPath() shouldBe if (geteuid() != 0u) "/run/user/4242/kontainer" else "/run/kontainer"
        }

        test("falls back to /run/kontainer without XDG_RUNTIME_DIR") {
            unsetenv("XDG_RUNTIME_DIR")
            defaultRootPath() shouldBe "/run/kontainer"
        }

        test("ignores an empty XDG_RUNTIME_DIR") {
            setenv("XDG_RUNTIME_DIR", "", 1)
            defaultRootPath() shouldBe "/run/kontainer"
        }
    })
