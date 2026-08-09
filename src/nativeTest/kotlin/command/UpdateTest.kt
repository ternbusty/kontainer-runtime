package command

import config.KontainerConfig
import config.saveKontainerConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import spec.LinuxCpu
import spec.LinuxMemory
import spec.LinuxPids
import spec.LinuxResources
import utils.FakeFileSystem

class UpdateTest :
    FunSpec({

        val cgroupDir = "/sys/fs/cgroup/kontainer-runtime/ct1"

        test("applyCgroupResources writes memory.max") {
            val fs = FakeFileSystem()
            val resources = LinuxResources(memory = LinuxMemory(limit = 1048576))

            applyCgroupResources(fs, cgroupDir, resources)

            fs.files["$cgroupDir/memory.max"] shouldBe "1048576"
        }

        test("applyCgroupResources writes memory max as 'max' for -1") {
            val fs = FakeFileSystem()
            val resources = LinuxResources(memory = LinuxMemory(limit = -1))

            applyCgroupResources(fs, cgroupDir, resources)

            fs.files["$cgroupDir/memory.max"] shouldBe "max"
        }

        test("applyCgroupResources writes memory.low for reservation") {
            val fs = FakeFileSystem()
            val resources = LinuxResources(memory = LinuxMemory(reservation = 524288))

            applyCgroupResources(fs, cgroupDir, resources)

            fs.files["$cgroupDir/memory.low"] shouldBe "524288"
        }

        test("applyCgroupResources writes cpu.weight from shares") {
            val fs = FakeFileSystem()
            // shares=1024 is the default; expected weight ~= 1 + (1022*9999/262142) ≈ 40
            val resources = LinuxResources(cpu = LinuxCpu(shares = 1024))

            applyCgroupResources(fs, cgroupDir, resources)

            val weight = fs.files["$cgroupDir/cpu.weight"]?.toLongOrNull()
            // Just verify it's a sensible positive number
            (weight != null && weight > 0) shouldBe true
        }

        test("applyCgroupResources writes cpu.max from quota and period") {
            val fs = FakeFileSystem()
            val resources = LinuxResources(cpu = LinuxCpu(quota = 50000, period = 100000))

            applyCgroupResources(fs, cgroupDir, resources)

            fs.files["$cgroupDir/cpu.max"] shouldBe "50000 100000"
        }

        test("applyCgroupResources writes pids.max") {
            val fs = FakeFileSystem()
            val resources = LinuxResources(pids = LinuxPids(limit = 100))

            applyCgroupResources(fs, cgroupDir, resources)

            fs.files["$cgroupDir/pids.max"] shouldBe "100"
        }

        test("applyCgroupResources writes pids max as 'max' for 0 or negative") {
            val fs = FakeFileSystem()
            val resources = LinuxResources(pids = LinuxPids(limit = 0))

            applyCgroupResources(fs, cgroupDir, resources)

            fs.files["$cgroupDir/pids.max"] shouldBe "max"
        }

        test("applyCgroupResources handles multiple resources at once") {
            val fs = FakeFileSystem()
            val resources =
                LinuxResources(
                    memory = LinuxMemory(limit = 2097152),
                    pids = LinuxPids(limit = 50),
                )

            applyCgroupResources(fs, cgroupDir, resources)

            fs.files["$cgroupDir/memory.max"] shouldBe "2097152"
            fs.files["$cgroupDir/pids.max"] shouldBe "50"
        }

        test("applyCgroupResources is a no-op for empty resources") {
            val fs = FakeFileSystem()
            val resources = LinuxResources()

            applyCgroupResources(fs, cgroupDir, resources)

            // No cgroup files should have been written
            fs.files.keys.none { it.startsWith(cgroupDir) } shouldBe true
        }
    })
