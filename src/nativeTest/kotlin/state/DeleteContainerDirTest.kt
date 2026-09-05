package state

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import utils.FakeFileSystem

class DeleteContainerDirTest :
    FunSpec({
        test("deleteContainerDir removes the container directory tree through the FileSystem") {
            val fs = FakeFileSystem()
            fs.createDirectories("/run/kontainer/abc/nested", 0x1EDu)
            fs.files["/run/kontainer/abc/state.json"] = "{}"
            fs.files["/run/kontainer/abc/nested/leftover"] = "x"
            fs.files["/run/kontainer/abcd/state.json"] = "sibling with common prefix"

            deleteContainerDir(fs, rootPath = "/run/kontainer", containerId = "abc")

            fs.files.keys shouldNotContain "/run/kontainer/abc/state.json"
            fs.files.keys shouldNotContain "/run/kontainer/abc/nested/leftover"
            fs.directories shouldNotContain "/run/kontainer/abc"
            fs.directories shouldNotContain "/run/kontainer/abc/nested"
            // A sibling whose name merely shares a prefix is untouched.
            fs.files.keys shouldContain "/run/kontainer/abcd/state.json"
            fs.directories shouldContain "/run/kontainer"
            fs.calls shouldContain "removeDirectoryRecursively(/run/kontainer/abc)"
        }

        test("deleteContainerDir is a no-op when the directory does not exist") {
            val fs = FakeFileSystem()
            deleteContainerDir(fs, rootPath = "/run/kontainer", containerId = "missing")
            fs.calls shouldBe listOf("removeDirectoryRecursively(/run/kontainer/missing)")
        }

        test("deleteContainerDir rejects an id with shell metacharacters before touching the file system") {
            // Regression guard for the former system("rm -rf $dir") implementation.
            val fs = FakeFileSystem()
            shouldThrow<IllegalArgumentException> {
                deleteContainerDir(fs, rootPath = "/run/kontainer", containerId = "x; touch /pwned")
            }
            fs.calls shouldBe emptyList()
        }
    })
