package command

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import preprocessExecArgs

class PreprocessExecArgsTest :
    FunSpec({

        fun run(vararg args: String): Pair<List<String>, List<String>> {
            val cmdArgs = mutableListOf<String>()
            val kept = preprocessExecArgs(arrayOf(*args), cmdArgs)
            return kept.toList() to cmdArgs
        }

        test("no exec subcommand — returns args unchanged") {
            val (kept, cmd) = run("create", "--bundle", "/b", "mycontainer")
            kept shouldBe listOf("create", "--bundle", "/b", "mycontainer")
            cmd shouldBe emptyList()
        }

        test("exec with simple positional command") {
            val (kept, cmd) = run("exec", "mycontainer", "ls", "-la")
            kept shouldBe listOf("exec", "mycontainer")
            cmd shouldBe listOf("ls", "-la")
        }

        test("exec with -- separator") {
            val (kept, cmd) = run("exec", "mycontainer", "--", "sh", "-c", "echo hello")
            kept shouldBe listOf("exec", "mycontainer")
            cmd shouldBe listOf("sh", "-c", "echo hello")
        }

        test("exec with --process flag") {
            val (kept, cmd) = run("exec", "-p", "proc.json", "mycontainer")
            kept shouldBe listOf("exec", "-p", "proc.json", "mycontainer")
            cmd shouldBe emptyList()
        }

        test("exec with --process long form") {
            val (kept, cmd) = run("exec", "--process", "proc.json", "mycontainer")
            kept shouldBe listOf("exec", "--process", "proc.json", "mycontainer")
            cmd shouldBe emptyList()
        }

        test("exec with --pid-file") {
            val (kept, cmd) = run("exec", "--pid-file", "/run/pid", "mycontainer", "--", "ls")
            kept shouldBe listOf("exec", "--pid-file", "/run/pid", "mycontainer")
            cmd shouldBe listOf("ls")
        }

        test("exec with --detach and --pid-file") {
            val (kept, cmd) = run("exec", "-d", "--pid-file", "/run/pid", "mycontainer", "--", "sleep", "999")
            kept shouldBe listOf("exec", "-d", "--pid-file", "/run/pid", "mycontainer")
            cmd shouldBe listOf("sleep", "999")
        }

        test("exec with global options before subcommand") {
            val (kept, cmd) = run("--root", "/var/run/k", "exec", "mycontainer", "--", "id")
            kept shouldBe listOf("--root", "/var/run/k", "exec", "mycontainer")
            cmd shouldBe listOf("id")
        }

        test("exec with no command args") {
            val (kept, cmd) = run("exec", "-p", "proc.json", "mycontainer")
            kept shouldBe listOf("exec", "-p", "proc.json", "mycontainer")
            cmd shouldBe emptyList()
        }

        test("exec container-only, no command") {
            val (kept, cmd) = run("exec", "mycontainer")
            kept shouldBe listOf("exec", "mycontainer")
            cmd shouldBe emptyList()
        }

        test("-- before container ID captures everything after as command") {
            val (kept, cmd) = run("exec", "--", "mycontainer", "ls")
            kept shouldBe listOf("exec")
            cmd shouldBe listOf("mycontainer", "ls")
        }

        test("--detach short form -d is recognized") {
            val (kept, cmd) = run("exec", "-d", "mycontainer", "sleep", "10")
            kept shouldBe listOf("exec", "-d", "mycontainer")
            cmd shouldBe listOf("sleep", "10")
        }

        test("all exec flags combined") {
            val (kept, cmd) =
                run("exec", "-d", "-p", "p.json", "--pid-file", "/tmp/p", "ctr1")
            kept shouldBe listOf("exec", "-d", "-p", "p.json", "--pid-file", "/tmp/p", "ctr1")
            cmd shouldBe emptyList()
        }
    })
