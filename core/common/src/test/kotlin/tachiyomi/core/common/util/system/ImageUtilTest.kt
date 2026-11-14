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
    fun `contrastLut clips the ends, never decreases and darkens the midtones`() {
        val lut = ImageUtil.contrastLut()
        lut[0] shouldBe 0
        lut[255] shouldBe 255
        lut[12] shouldBe 0
        lut[243] shouldBe 255
        (1 until 256).all { lut[it] >= lut[it - 1] } shouldBe true
        (lut[128] < 128) shouldBe true
    }

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test
    fun `enhancePixels leaves a flat area flat`() {
        val out = ImageUtil.enhancePixels(IntArray(25) { gray(128) }, 5, 5)
        out.distinct().size shouldBe 1
        out[12] shouldBe gray(ImageUtil.contrastLut()[128])
    }

    @Test
    fun `enhancePixels sharpens both sides of an edge`() {
        val dark = ImageUtil.contrastLut()[60]
        val light = ImageUtil.contrastLut()[200]
        val pixels = IntArray(18) { if (it % 6 < 3) gray(60) else gray(200) }
        val out = ImageUtil.enhancePixels(pixels, 6, 3)
        ((out[8] and 0xFF) < dark) shouldBe true
        ((out[9] and 0xFF) > light) shouldBe true
    }

    @Test
    fun `sampleSizeFor keeps the decode at least target size`() {
        ImageUtil.sampleSizeFor(5000, 7000, 1264, 1680) shouldBe 2
        ImageUtil.sampleSizeFor(1264, 1680, 1264, 1680) shouldBe 1
        ImageUtil.sampleSizeFor(2528, 3360, 1264, 1680) shouldBe 2
    }
}
