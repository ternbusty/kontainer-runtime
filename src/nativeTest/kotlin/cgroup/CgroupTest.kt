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

        // setupCgroup: subtree_control propagation across all ancestors
        // (the bug fixed in PR #17 — must enable controllers at every parent
        // of the leaf, not just root, otherwise leaf opens fail with EACCES)

        test("setupCgroup enables controllers at every ancestor for nested cgroupsPath") {
            val fs = FakeFileSystem()
            setupCgroup(
                fs,
                pid = 1234,
                cgroupPath = "default/test-verify",
                resources = LinuxResources(memory = LinuxMemory(limit = 134217728L)),
            )

            // Leaf cgroup directory created
            fs.directories shouldContain "/sys/fs/cgroup/default/test-verify"

            // Controllers enabled at root AND at the parent of the leaf
            fs.files["/sys/fs/cgroup/cgroup.subtree_control"] shouldBe "+memory"
            fs.files["/sys/fs/cgroup/default/cgroup.subtree_control"] shouldBe "+memory"

            // Process placed in the leaf cgroup
            fs.files["/sys/fs/cgroup/default/test-verify/cgroup.procs"] shouldBe "1234"

            // Memory limit applied to leaf
            fs.files["/sys/fs/cgroup/default/test-verify/memory.max"] shouldBe "134217728"
        }

        test("setupCgroup enables controllers only at root for top-level cgroupsPath") {
            val fs = FakeFileSystem()
            setupCgroup(
                fs,
                pid = 42,
                cgroupPath = "kontainer-42",
                resources = LinuxResources(memory = LinuxMemory(limit = 1024L)),
            )

            fs.files["/sys/fs/cgroup/cgroup.subtree_control"] shouldBe "+memory"
            // No intermediate parent — only the root level needs +memory
            fs.files.keys shouldNotContain "/sys/fs/cgroup/kontainer-42/cgroup.subtree_control"
            fs.files["/sys/fs/cgroup/kontainer-42/memory.max"] shouldBe "1024"
        }

        test("setupCgroup short-circuits when both cgroupPath and resources are null") {
            val fs = FakeFileSystem()
            setupCgroup(fs, pid = 1, cgroupPath = null, resources = null)
            fs.calls.isEmpty() shouldBe true
        }

        // Resource value translation

        test("setupCgroup writes 'max' for limit = -1") {
            val fs = FakeFileSystem()
            setupCgroup(
                fs,
                pid = 1,
                cgroupPath = "x",
                resources = LinuxResources(memory = LinuxMemory(limit = -1L)),
            )
            fs.files["/sys/fs/cgroup/x/memory.max"] shouldBe "max"
        }

        test("setupCgroup writes cpu.max as 'quota period' when both are set") {
            val fs = FakeFileSystem()
            setupCgroup(
                fs,
                pid = 1,
                cgroupPath = "x",
                resources = LinuxResources(cpu = LinuxCpu(quota = 50000L, period = 100000L)),
            )
            fs.files["/sys/fs/cgroup/x/cpu.max"] shouldBe "50000 100000"
        }

        test("setupCgroup writes cpu.max as 'max' when quota is non-positive") {
            val fs = FakeFileSystem()
            setupCgroup(
                fs,
                pid = 1,
                cgroupPath = "x",
                resources = LinuxResources(cpu = LinuxCpu(quota = -1L, period = 100000L)),
            )
            fs.files["/sys/fs/cgroup/x/cpu.max"] shouldBe "max 100000"
        }

        test("setupCgroup converts cgroup v1 shares to v2 cpu.weight") {
            val fs = FakeFileSystem()
            // shares=1024 → weight = 1 + ((1024 - 2) * 9999) / 262142 = 1 + 38.99 ~ 39
            setupCgroup(
                fs,
                pid = 1,
                cgroupPath = "x",
                resources = LinuxResources(cpu = LinuxCpu(shares = 1024L)),
            )
            fs.files["/sys/fs/cgroup/x/cpu.weight"] shouldBe "39"
        }

        test("setupCgroup writes memory.swap.max as swap minus limit (cgroup v2 semantics)") {
            val fs = FakeFileSystem()
            setupCgroup(
                fs,
                pid = 1,
                cgroupPath = "x",
                resources = LinuxResources(memory = LinuxMemory(limit = 1024L, swap = 2048L)),
            )
            fs.files["/sys/fs/cgroup/x/memory.swap.max"] shouldBe "1024"
        }

        // getCgroupPids

        test("getCgroupPids returns the PIDs read from cgroup.procs") {
            val fs = FakeFileSystem()
            fs.files["/sys/fs/cgroup/x/cgroup.procs"] = "100\n200\n300\n"

            getCgroupPids(fs, "x") shouldBe listOf(100, 200, 300)
        }

        test("getCgroupPids accepts a leading slash in the path") {
            val fs = FakeFileSystem()
            fs.files["/sys/fs/cgroup/x/cgroup.procs"] = "1\n"

            getCgroupPids(fs, "/x") shouldBe listOf(1)
        }

        test("getCgroupPids skips blank and non-numeric lines") {
            val fs = FakeFileSystem()
            fs.files["/sys/fs/cgroup/x/cgroup.procs"] = "1\n\nabc\n2\n"

            getCgroupPids(fs, "x") shouldBe listOf(1, 2)
        }
    })
