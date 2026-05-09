package state

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import utils.FakeFileSystem

class StateRoundtripTest :
    FunSpec({

        test("save then loadState round-trips the State object") {
            val fs = FakeFileSystem()
            val original =
                State(
                    ociVersion = "1.0.0",
                    id = "abc-123",
                    status = ContainerStatus.CREATED,
                    pid = 4242,
                    bundle = "/path/to/bundle",
                    annotations = mapOf("k" to "v"),
                    created = "2026-05-10T00:00:00Z",
                )

            original.save(fs, rootPath = "/run/kontainer")
            val loaded = loadState(fs, rootPath = "/run/kontainer", containerId = "abc-123")

            loaded shouldBe original
        }

        test("save creates the container directory") {
            val fs = FakeFileSystem()
            val state =
                State(
                    ociVersion = "1.0.0",
                    id = "x",
                    status = ContainerStatus.CREATING,
                    pid = null,
                    bundle = "/b",
                    annotations = null,
                    created = null,
                )

            state.save(fs, rootPath = "/run/kontainer")

            (("/run/kontainer/x" in fs.directories)) shouldBe true
        }

        test("save writes state.json under {rootPath}/{containerId}/") {
            val fs = FakeFileSystem()
            val state =
                State(
                    ociVersion = "1.0.0",
                    id = "x",
                    status = ContainerStatus.CREATING,
                    pid = null,
                    bundle = "/b",
                    annotations = null,
                    created = null,
                )

            state.save(fs, rootPath = "/run/kontainer")

            (("/run/kontainer/x/state.json" in fs.files)) shouldBe true
        }

        test("loadState throws when state.json does not exist") {
            val fs = FakeFileSystem()
            shouldThrow<Exception> {
                loadState(fs, rootPath = "/run/kontainer", containerId = "missing")
            }
        }

        test("containerExists is true after save and false otherwise") {
            val fs = FakeFileSystem()
            val state =
                State(
                    ociVersion = "1.0.0",
                    id = "x",
                    status = ContainerStatus.CREATING,
                    pid = null,
                    bundle = "/b",
                    annotations = null,
                    created = null,
                )

            containerExists(fs, "/run/kontainer", "x") shouldBe false
            state.save(fs, "/run/kontainer")
            containerExists(fs, "/run/kontainer", "x") shouldBe true
        }
    })
