package cgroup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import spec.LinuxCpu
import spec.LinuxMemory
import spec.LinuxResources
import utils.FakeFileSystem

class CgroupTest :
    FunSpec({

        // setup: subtree_control propagation across all ancestors
        // (the bug fixed in PR #17 — must enable controllers at every parent
        // of the leaf, not just root, otherwise leaf opens fail with EACCES)

        test("setup enables controllers at every ancestor for nested cgroupsPath") {
            val fs = FakeFileSystem()
            CgroupV2(fs).setup(
                pid = 1234,
                cgroupPath = "default/test-verify",
                resources = LinuxResources(memory = LinuxMemory(limit = 134217728L)),
            )

            fs.directories shouldContain "/sys/fs/cgroup/default/test-verify"

            fs.files["/sys/fs/cgroup/cgroup.subtree_control"] shouldBe "+memory"
            fs.files["/sys/fs/cgroup/default/cgroup.subtree_control"] shouldBe "+memory"

            fs.files["/sys/fs/cgroup/default/test-verify/cgroup.procs"] shouldBe "1234"
            fs.files["/sys/fs/cgroup/default/test-verify/memory.max"] shouldBe "134217728"
        }

        test("setup enables controllers only at root for top-level cgroupsPath") {
            val fs = FakeFileSystem()
            CgroupV2(fs).setup(
                pid = 42,
                cgroupPath = "kontainer-42",
                resources = LinuxResources(memory = LinuxMemory(limit = 1024L)),
            )

            fs.files["/sys/fs/cgroup/cgroup.subtree_control"] shouldBe "+memory"
            // No intermediate parent — only root needs +memory
            fs.files.keys shouldNotContain "/sys/fs/cgroup/kontainer-42/cgroup.subtree_control"
            fs.files["/sys/fs/cgroup/kontainer-42/memory.max"] shouldBe "1024"
        }

        test("setup short-circuits when both cgroupPath and resources are null") {
            val fs = FakeFileSystem()
            CgroupV2(fs).setup(pid = 1, cgroupPath = null, resources = null)
            fs.calls.isEmpty() shouldBe true
        }

        // Resource value translation

        test("setup writes 'max' for limit = -1") {
            val fs = FakeFileSystem()
            CgroupV2(fs).setup(
                pid = 1,
                cgroupPath = "x",
                resources = LinuxResources(memory = LinuxMemory(limit = -1L)),
            )
            fs.files["/sys/fs/cgroup/x/memory.max"] shouldBe "max"
        }

        test("setup writes cpu.max as 'quota period' when both are set") {
            val fs = FakeFileSystem()
            CgroupV2(fs).setup(
                pid = 1,
                cgroupPath = "x",
                resources = LinuxResources(cpu = LinuxCpu(quota = 50000L, period = 100000L)),
            )
            fs.files["/sys/fs/cgroup/x/cpu.max"] shouldBe "50000 100000"
        }

        test("setup writes cpu.max as 'max' when quota is non-positive") {
            val fs = FakeFileSystem()
            CgroupV2(fs).setup(
                pid = 1,
                cgroupPath = "x",
                resources = LinuxResources(cpu = LinuxCpu(quota = -1L, period = 100000L)),
            )
            fs.files["/sys/fs/cgroup/x/cpu.max"] shouldBe "max 100000"
        }

        test("setup converts cgroup v1 shares to v2 cpu.weight") {
            val fs = FakeFileSystem()
            // shares=1024 → weight = 1 + ((1024 - 2) * 9999) / 262142 = 1 + 38.99 ~ 39
            CgroupV2(fs).setup(
                pid = 1,
                cgroupPath = "x",
                resources = LinuxResources(cpu = LinuxCpu(shares = 1024L)),
            )
            fs.files["/sys/fs/cgroup/x/cpu.weight"] shouldBe "39"
        }

        test("setup writes memory.swap.max as swap minus limit (cgroup v2 semantics)") {
            val fs = FakeFileSystem()
            CgroupV2(fs).setup(
                pid = 1,
                cgroupPath = "x",
                resources = LinuxResources(memory = LinuxMemory(limit = 1024L, swap = 2048L)),
            )
            fs.files["/sys/fs/cgroup/x/memory.swap.max"] shouldBe "1024"
        }

        // getPids

        test("getPids returns the PIDs read from cgroup.procs") {
            val fs = FakeFileSystem()
            fs.files["/sys/fs/cgroup/x/cgroup.procs"] = "100\n200\n300\n"

            CgroupV2(fs).getPids("x") shouldBe listOf(100, 200, 300)
        }

        test("getPids accepts a leading slash in the path") {
            val fs = FakeFileSystem()
            fs.files["/sys/fs/cgroup/x/cgroup.procs"] = "1\n"

            CgroupV2(fs).getPids("/x") shouldBe listOf(1)
        }

        test("getPids skips blank and non-numeric lines") {
            val fs = FakeFileSystem()
            fs.files["/sys/fs/cgroup/x/cgroup.procs"] = "1\n\nabc\n2\n"

            CgroupV2(fs).getPids("x") shouldBe listOf(1, 2)
        }

        // cleanup

        test("cleanup is a no-op for null path") {
            val fs = FakeFileSystem()
            CgroupV2(fs).cleanup(null)
            fs.calls.isEmpty() shouldBe true
        }

        test("cleanup removes the directory when present") {
            val fs = FakeFileSystem()
            fs.directories += "/sys/fs/cgroup/x"

            CgroupV2(fs).cleanup("x")

            ("/sys/fs/cgroup/x" in fs.directories) shouldBe false
        }

        test("cleanup tolerates missing directory") {
            val fs = FakeFileSystem()
            CgroupV2(fs).cleanup("never-existed")
            // Did not throw; the FakeFileSystem just returns false for the remove
            ("/sys/fs/cgroup/never-existed" in fs.directories) shouldBe false
        }
    })
