package channel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class FakeChannelTest :
    FunSpec({

        // FakeMainSender records every call

        test("FakeMainSender records each kind of message") {
            val sender = FakeMainSender()

            sender.identifierMappingRequest()
            sender.initReady()
            sender.seccompNotifyRequest(fd = 7)
            sender.execFailed("boom")
            sender.sendError("nope")
            sender.close()

            sender.calls shouldBe
                listOf(
                    "identifierMappingRequest()",
                    "initReady()",
                    "seccompNotifyRequest(fd=7)",
                    "execFailed(error=boom)",
                    "sendError(error=nope)",
                    "close()",
                )
        }

        // FakeMainReceiver pulls preseeded messages from queues

        test("FakeMainReceiver returns preseeded mapping requests in order") {
            val receiver = FakeMainReceiver()
            receiver.mappingRequests.addLast(Message.WriteMapping)
            receiver.mappingRequests.addLast(Message.WriteMapping)

            receiver.waitForMappingRequest() shouldBe Message.WriteMapping
            receiver.waitForMappingRequest() shouldBe Message.WriteMapping
            shouldThrow<IllegalStateException> { receiver.waitForMappingRequest() }
        }

        test("FakeMainReceiver returns preseeded seccomp request FDs") {
            val receiver = FakeMainReceiver()
            receiver.seccompRequestFds.addLast(42)

            receiver.waitForSeccompRequest() shouldBe 42
        }

        test("FakeMainReceiver throws when init-ready was not preseeded") {
            val receiver = FakeMainReceiver()
            shouldThrow<IllegalStateException> { receiver.waitForInitReady() }
        }

        // FakeInitSender / FakeInitReceiver

        test("FakeInitSender records mapping/seccomp acks") {
            val sender = FakeInitSender()
            sender.mappingWritten()
            sender.seccompNotifyDone()

            sender.calls shouldContain "mappingWritten()"
            sender.calls shouldContain "seccompNotifyDone()"
        }

        test("FakeInitReceiver returns the preseeded seccomp-done signal") {
            val receiver = FakeInitReceiver()
            receiver.seccompDoneSignals.addLast(Unit)

            receiver.waitForSeccompRequestDone()

            receiver.calls shouldBe listOf("waitForSeccompRequestDone()")
        }

        // Notify pair

        test("FakeNotifySocket records notifyContainerStart") {
            val sock = FakeNotifySocket()
            sock.notifyContainerStart()
            sock.calls shouldBe listOf("notifyContainerStart()")
        }

        test("FakeNotifyListener consumes preseeded start signals") {
            val listener = FakeNotifyListener()
            listener.startSignals.addLast(Unit)

            listener.waitForContainerStart()

            listener.calls shouldBe listOf("waitForContainerStart()")
        }
    })
