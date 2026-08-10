package command

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import state.ContainerStatus
import state.State
import state.save
import utils.FakeFileSystem

class ListTest :
    FunSpec({

        val rootPath = "/run/kontainer"

        fun stateFixture(
            id: String,
            status: ContainerStatus = ContainerStatus.STOPPED,
            pid: Int? = null,
            bundle: String = "/bundles/$id",
            created: String = "2026-01-01T00:00:00Z",
        ) = State(
            ociVersion = "1.0.0",
            id = id,
            status = status,
            pid = pid,
            bundle = bundle,
            created = created,
        )

        test("list with no containers prints only the header") {
            val fs = FakeFileSystem()
            fs.createDirectories(rootPath)

            val output = formatContainerList(fs, rootPath, "table", quiet = false)
            output.trimEnd() shouldBe "ID  PID  STATUS  BUNDLE  CREATED"
        }

        test("list in table format shows all containers") {
            val fs = FakeFileSystem()
            stateFixture("alpha", bundle = "/b/alpha").save(fs, rootPath)
            stateFixture("beta", bundle = "/b/beta").save(fs, rootPath)

            val output = formatContainerList(fs, rootPath, "table", quiet = false)
            output shouldContain "alpha"
            output shouldContain "beta"
            output shouldContain "stopped"
        }

        test("list in quiet mode prints only IDs") {
            val fs = FakeFileSystem()
            stateFixture("c1").save(fs, rootPath)
            stateFixture("c2").save(fs, rootPath)

            val output = formatContainerList(fs, rootPath, "table", quiet = true)
            val lines = output.trim().lines()
            lines.toSet() shouldBe setOf("c1", "c2")
        }

        test("list in json format prints a JSON array") {
            val fs = FakeFileSystem()
            stateFixture("j1", bundle = "/b/j1").save(fs, rootPath)
            // Provide a config.json so toListEntry can resolve rootfs
            fs.createDirectories("/b/j1")
            fs.writeTextFile("/b/j1/config.json", """{"ociVersion":"1.0.0","root":{"path":"rootfs"}}""")

            val output = formatContainerList(fs, rootPath, "json", quiet = false)
            output shouldContain "\"id\""
            output shouldContain "\"j1\""
            output shouldContain "\"status\""
            output shouldContain "\"rootfs\""
        }

        test("list skips containers with broken state.json") {
            val fs = FakeFileSystem()
            stateFixture("good").save(fs, rootPath)
            // Create a directory with garbage state
            fs.createDirectories("$rootPath/broken")
            fs.writeTextFile("$rootPath/broken/state.json", "not valid json {{{")

            val output = formatContainerList(fs, rootPath, "table", quiet = false)
            output shouldContain "good"
            output shouldNotContain "broken"
        }

        test("list handles missing root directory gracefully") {
            val fs = FakeFileSystem()

            val output = formatContainerList(fs, "/nonexistent", "table", quiet = false)
            output.trimEnd() shouldBe "ID  PID  STATUS  BUNDLE  CREATED"
        }

        test("list table shows PID and created time") {
            val fs = FakeFileSystem()
            stateFixture("x", pid = 42, created = "2026-08-09T12:00:00Z").save(fs, rootPath)

            val output = formatContainerList(fs, rootPath, "table", quiet = false)
            output shouldContain "42"
            output shouldContain "2026-08-09T12:00:00Z"
        }
    })
