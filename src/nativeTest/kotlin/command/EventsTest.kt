package command

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import utils.FakeFileSystem

class EventsTest :
    FunSpec({

        val cgroupDir = "/sys/fs/cgroup/kontainer-runtime/ct1"

        fun seedCgroup(fs: FakeFileSystem) {
            fs.files["$cgroupDir/memory.current"] = "524288"
            fs.files["$cgroupDir/memory.max"] = "1048576"
            fs.files["$cgroupDir/memory.events"] =
                "low 0\nhigh 0\nmax 0\noom 0\noom_kill 0\noom_group_kill 0"
            fs.files["$cgroupDir/cpu.stat"] =
                "usage_usec 123456\nuser_usec 100000\nsystem_usec 23456"
            fs.files["$cgroupDir/pids.current"] = "3"
            fs.files["$cgroupDir/pids.max"] = "100"
        }

        test("buildSnapshot reads memory stats") {
            val fs = FakeFileSystem()
            seedCgroup(fs)

            val snap = buildSnapshot(fs, cgroupDir, "ct1")

            snap.id shouldBe "ct1"
            snap.type shouldBe "stats"
            snap.data.memory.usage shouldBe 524288L
            snap.data.memory.limit shouldBe "1048576"
        }

        test("buildSnapshot reads cpu stats") {
            val fs = FakeFileSystem()
            seedCgroup(fs)

            val snap = buildSnapshot(fs, cgroupDir, "ct1")

            snap.data.cpu.stat shouldNotBe null
            snap.data.cpu.stat!!["usage_usec"] shouldBe 123456L
            snap.data.cpu.stat!!["user_usec"] shouldBe 100000L
        }

        test("buildSnapshot reads pids stats") {
            val fs = FakeFileSystem()
            seedCgroup(fs)

            val snap = buildSnapshot(fs, cgroupDir, "ct1")

            snap.data.pids.current shouldBe 3L
            snap.data.pids.limit shouldBe "100"
        }

        test("buildSnapshot handles missing files gracefully") {
            val fs = FakeFileSystem()
            // No cgroup files seeded

            val snap = buildSnapshot(fs, cgroupDir, "ct1")

            snap.data.memory.usage shouldBe null
            snap.data.memory.limit shouldBe null
            snap.data.cpu.stat shouldBe null
            snap.data.pids.current shouldBe null
        }

        test("buildSnapshot still works when memory.events file exists") {
            val fs = FakeFileSystem()
            fs.files["$cgroupDir/memory.events"] = "low 0\nhigh 5\nmax 1\noom 0"
            fs.files["$cgroupDir/memory.current"] = "1024"
            fs.files["$cgroupDir/memory.max"] = "2048"

            val snap = buildSnapshot(fs, cgroupDir, "ct1")

            // memory.events is NOT included in stats output (to avoid
            // grep false positives in runc bats tests), but the snapshot
            // should still build without errors.
            snap.data.memory.usage shouldBe 1024L
            snap.data.memory.limit shouldBe "2048"
        }

        test("buildSnapshot handles max as string in pids.max") {
            val fs = FakeFileSystem()
            fs.files["$cgroupDir/pids.max"] = "max"

            val snap = buildSnapshot(fs, cgroupDir, "ct1")

            snap.data.pids.limit shouldBe "max"
            snap.data.pids.current shouldBe null
        }
    })
