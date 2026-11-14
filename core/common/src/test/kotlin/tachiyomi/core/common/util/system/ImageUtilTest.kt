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

    private fun boundsOf(width: Int, height: Int, isInk: (x: Int, y: Int) -> Boolean) =
        ImageUtil.findContentBounds(width, height) { y, row ->
            for (x in row.indices) row[x] = if (isInk(x, y)) 0xFF202020.toInt() else 0xFFFFFFFF.toInt()
        }

    @Test
    fun `findContentBounds finds the inked area`() {
        boundsOf(100, 200) { x, y -> x in 20..59 && y in 30..149 } shouldBe ImageUtil.Bounds(20, 30, 60, 150)
    }

    @Test
    fun `findContentBounds returns null for a blank page`() {
        boundsOf(100, 200) { _, _ -> false } shouldBe null
    }

    @Test
    fun `cropBounds keeps a margin and skips tiny crops`() {
        val crop = ImageUtil.cropBounds(1000, 1000, ImageUtil.Bounds(100, 100, 900, 900))
        crop shouldBe ImageUtil.Bounds(90, 90, 910, 910)
        ImageUtil.cropBounds(1000, 1000, ImageUtil.Bounds(5, 5, 995, 995)) shouldBe null
    }

    @Test
    fun `cropBounds never removes more than 15 percent per side`() {
        val crop = ImageUtil.cropBounds(1000, 1000, ImageUtil.Bounds(450, 450, 550, 550))
        crop shouldBe ImageUtil.Bounds(150, 150, 850, 850)
    }

    @Test
    fun `sampleSizeFor keeps the decode at least target size`() {
        ImageUtil.sampleSizeFor(5000, 7000, 1264, 1680) shouldBe 2
        ImageUtil.sampleSizeFor(1264, 1680, 1264, 1680) shouldBe 1
        ImageUtil.sampleSizeFor(2528, 3360, 1264, 1680) shouldBe 2
    }
}
