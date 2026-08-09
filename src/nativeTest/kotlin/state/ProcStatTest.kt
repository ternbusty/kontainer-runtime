package state

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class ProcStatTest :
    FunSpec({

        // A realistic /proc/<pid>/stat line: pid (comm) state ppid pgrp
        // session tty_nr tpgid flags minflt cminflt majflt cmajflt utime
        // stime cutime cstime priority nice num_threads itrealvalue
        // starttime(22) vsize rss ...
        val statLine =
            "4242 (sh) S 1 4242 4242 0 -1 4194560 100 0 0 0 1 2 0 0 " +
                "20 0 1 0 987654321 2318336 180 18446744073709551615 1 1 0 0 0 0 0 0 0 0 0 0 17 3 0 0 0 0 0\n"

        test("parses state and starttime from a stat line") {
            val stat = parseProcStat(statLine)
            stat shouldBe ProcStat(state = 'S', startTime = 987654321L)
        }

        test("locates fields after the LAST paren when comm contains ') ' ") {
            // comm can contain arbitrary bytes including ") R " sequences
            val tricky =
                "77 (a) R (b) Z 1 77 77 0 -1 4194560 100 0 0 0 1 2 0 0 " +
                    "20 0 1 0 42 2318336 180 0 1 1 0 0 0 0 0 0 0 0 0 0 17 3 0 0 0 0 0\n"
            val stat = parseProcStat(tricky)
            stat shouldBe ProcStat(state = 'Z', startTime = 42L)
        }

        test("returns null when the line is too short to hold starttime") {
            parseProcStat("4242 (sh) S 1 4242").shouldBeNull()
        }

        test("returns null on garbage") {
            parseProcStat("").shouldBeNull()
            parseProcStat("not a stat line").shouldBeNull()
            parseProcStat("4242 (sh)").shouldBeNull()
        }

        test("zombie state is parsed as Z") {
            val zombie = statLine.replaceFirst(" S ", " Z ")
            parseProcStat(zombie)?.state shouldBe 'Z'
        }
    })
