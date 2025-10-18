import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class Test :
    FunSpec({

        test("my first test") {
            1 + 2 shouldBe 3
        }
    })
