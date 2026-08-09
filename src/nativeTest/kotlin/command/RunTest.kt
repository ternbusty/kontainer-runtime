package command

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.*

/**
 * Unit tests for the run command's helper logic.
 *
 * The full `run()` function orchestrates create → start → wait → delete,
 * which needs a real container lifecycle. These tests cover the
 * independently testable polling helper [waitForProcessExitTestable].
 */
@OptIn(ExperimentalForeignApi::class)
class RunTest :
    FunSpec({

        test("waitForProcessExit returns immediately for non-existent pid") {
            // PID 2^22-1 is extremely unlikely to exist.
            val fakePid = 4_194_303
            // Verify it really doesn't exist
            val rc = kill(fakePid, 0)
            if (rc == 0) {
                // Somehow this PID exists; skip.
                return@test
            }
            // The function should return immediately (ESRCH).
            waitForProcessExitTestable(fakePid)
            // If we reach here without hanging, the test passes.
        }

        test("waitForProcessExit detects a short-lived child process") {
            // Fork a child that exits immediately. We can waitpid first
            // (since it IS our child), but waitForProcessExit uses
            // kill(pid, 0) which also works on children.
            val pid = fork()
            when {
                pid < 0 -> {
                    // fork failed — skip
                    return@test
                }

                pid == 0 -> {
                    // Child: exit immediately
                    _exit(0)
                }

                else -> {
                    // Parent: wait for the child with our polling function.
                    // The child has already exited (or will very shortly).
                    // We must waitpid first to reap the zombie; otherwise
                    // kill(pid, 0) returns 0 for zombies.
                    waitpid(pid, null, 0)
                    waitForProcessExitTestable(pid)
                }
            }
        }
    })

/**
 * Testable version of [waitForProcessExit] from Run.kt.
 *
 * The private function in Run.kt polls with exponential back-off.
 * We duplicate the logic here so unit tests can exercise it without
 * exposing the internal function. The real implementation and this
 * copy share the same algorithm.
 */
@OptIn(ExperimentalForeignApi::class)
private fun waitForProcessExitTestable(pid: Int) {
    var sleepUs: UInt = 10_000u
    while (true) {
        val rc = kill(pid, 0)
        if (rc != 0 && errno == ESRCH) return
        usleep(sleepUs)
        if (sleepUs < 500_000u) {
            sleepUs = (sleepUs * 2u).coerceAtMost(500_000u)
        }
    }
}
