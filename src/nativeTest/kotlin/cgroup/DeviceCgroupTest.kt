package cgroup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import spec.LinuxDeviceCgroup

/**
 * Exercise the eBPF program emitter in isolation. We never load these
 * programs into the kernel from the test suite — we pin the byte-level
 * output so that changes to the emitter that alter rule semantics fail
 * loudly. BPF instructions are 8 bytes each.
 */
class DeviceCgroupTest :
    FunSpec({

        fun rule(
            allow: Boolean,
            type: String? = null,
            major: Long? = null,
            minor: Long? = null,
            access: String? = null,
        ) = LinuxDeviceCgroup(allow = allow, type = type, major = major, minor = minor, access = access)

        test("program is always a multiple of 8 bytes") {
            val cases =
                listOf(
                    emptyList(),
                    listOf(rule(false, access = "rwm")),
                    listOf(rule(true, "c", 1L, 3L, "rwm")),
                    listOf(
                        rule(true, "c", 1L, 3L, "rwm"),
                        rule(false, access = "rwm"),
                    ),
                )
            for (rules in cases) {
                val prog = DeviceCgroup.buildProgram(rules)
                (prog.size % 8) shouldBe 0
                prog.size shouldBeGreaterThan 0
            }
        }

        test("program grows monotonically with more rules") {
            val one =
                DeviceCgroup.buildProgram(
                    listOf(rule(true, "c", 1L, 3L, "rwm")),
                )
            val two =
                DeviceCgroup.buildProgram(
                    listOf(
                        rule(true, "c", 1L, 3L, "rwm"),
                        rule(true, "c", 5L, 0L, "rwm"),
                    ),
                )
            (two.size > one.size) shouldBe true
        }

        test("prelude is the same regardless of rules") {
            val empty = DeviceCgroup.buildProgram(emptyList())
            val withRule =
                DeviceCgroup.buildProgram(
                    listOf(rule(true, "c", 1L, 3L, "rwm")),
                )
            // The first 48 bytes (6 insns × 8) are the prelude
            empty.take(48) shouldBe withRule.take(48)
        }

        test("empty rule set emits deny-all tail") {
            val prog = DeviceCgroup.buildProgram(emptyList())
            // prelude (48) + tail (16 = mov R0,0 + exit)
            prog.size shouldBe 64
        }

        test("allow flag ends up in the return instruction") {
            val allow =
                DeviceCgroup.buildProgram(
                    listOf(rule(true, "c", 1L, 3L, "rwm")),
                )
            val deny =
                DeviceCgroup.buildProgram(
                    listOf(rule(false, "c", 1L, 3L, "rwm")),
                )
            // Both programs end with a default tail (16 bytes: mov R0,0; exit).
            // The rule's own "mov R0, allow" + exit is the 16 bytes before that.
            // The imm field starts 4 bytes into the insn.
            val allowImmOffset = allow.size - 16 - 16 + 4
            val denyImmOffset = deny.size - 16 - 16 + 4
            (allow[allowImmOffset].toInt() and 0xFF) shouldBe 1
            (deny[denyImmOffset].toInt() and 0xFF) shouldBe 0
        }

        test("wildcard type emits no type-check instruction") {
            // type=null (or "a") means any device type, so no JNE for R2
            val wildcard =
                DeviceCgroup.buildProgram(
                    listOf(rule(true, null, 1L, 3L, "rwm")),
                )
            val typed =
                DeviceCgroup.buildProgram(
                    listOf(rule(true, "c", 1L, 3L, "rwm")),
                )
            // The typed version should have one extra JNE instruction (8 bytes)
            (typed.size - wildcard.size) shouldBe 8
        }

        test("wildcard major/minor emits fewer instructions") {
            val full =
                DeviceCgroup.buildProgram(
                    listOf(rule(true, "c", 1L, 3L, "rwm")),
                )
            val noMinor =
                DeviceCgroup.buildProgram(
                    listOf(rule(true, "c", 1L, null, "rwm")),
                )
            val neither =
                DeviceCgroup.buildProgram(
                    listOf(rule(true, "c", null, null, "rwm")),
                )
            // Each wildcard field drops one JNE (8 bytes)
            (full.size - noMinor.size) shouldBe 8
            (noMinor.size - neither.size) shouldBe 8
        }

        test("null access emits no access-check instructions") {
            val withAccess =
                DeviceCgroup.buildProgram(
                    listOf(rule(true, "c", 1L, 3L, "rwm")),
                )
            val noAccess =
                DeviceCgroup.buildProgram(
                    listOf(rule(true, "c", 1L, 3L, null)),
                )
            // Access check is 3 instructions (mov R7,R3; and R7,~bits; jne R7,0) = 24 bytes
            (withAccess.size - noAccess.size) shouldBe 24
        }
    })
