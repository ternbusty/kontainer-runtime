package exeseal

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import peekRootPath

/**
 * Tests for [peekRootPath] — extracts the `--root` flag from raw CLI args
 * before Clikt parsing, so exeseal can create its overlayfs dummy dir
 * under the correct state root.
 */
class PeekRootPathTest :
    FunSpec({

        test("returns default when no --root is present") {
            peekRootPath(arrayOf("create", "--bundle", "/b", "mycontainer")) shouldBe "/run/kontainer"
        }

        test("returns default for empty args") {
            peekRootPath(emptyArray()) shouldBe "/run/kontainer"
        }

        test("extracts --root with separate value") {
            peekRootPath(arrayOf("--root", "/custom/state", "create", "c1")) shouldBe "/custom/state"
        }

        test("extracts --root=value form") {
            peekRootPath(arrayOf("--root=/custom/state", "run", "--bundle", ".", "c1")) shouldBe "/custom/state"
        }

        test("extracts --root even after subcommand token") {
            // peekRootPath scans all args, not just those before the subcommand
            peekRootPath(arrayOf("create", "--root", "/late/root", "c1")) shouldBe "/late/root"
        }

        test("returns default when --root has no following value") {
            // --root at end with no value — falls through without returning
            peekRootPath(arrayOf("create", "--root")) shouldBe "/run/kontainer"
        }

        test("--root=value with colons in path") {
            // Paths with colons are unusual but valid on Linux
            peekRootPath(arrayOf("--root=/run/a:b", "create")) shouldBe "/run/a:b"
        }
    })
