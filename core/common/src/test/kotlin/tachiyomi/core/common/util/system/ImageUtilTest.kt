package tachiyomi.core.common.util.system

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ImageUtilTest {

    @Test
    fun `fitWithin scales down to the limiting side`() {
        ImageUtil.fitWithin(2528, 1680, 1264, 1680) shouldBe (1264 to 840)
        ImageUtil.fitWithin(1500, 3000, 1264, 1680) shouldBe (840 to 1680)
    }

    @Test
    fun `fitWithin never upscales`() {
        ImageUtil.fitWithin(800, 1200, 1264, 1680) shouldBe (800 to 1200)
    }

    @Test
    fun `sampleSizeFor keeps the decode at least target size`() {
        ImageUtil.sampleSizeFor(5000, 7000, 1264, 1680) shouldBe 2
        ImageUtil.sampleSizeFor(1264, 1680, 1264, 1680) shouldBe 1
        ImageUtil.sampleSizeFor(2528, 3360, 1264, 1680) shouldBe 2
    }
}
