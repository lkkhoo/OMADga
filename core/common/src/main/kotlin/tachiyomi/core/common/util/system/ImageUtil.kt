package tachiyomi.core.common.util.system

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import androidx.annotation.ColorInt
import androidx.core.graphics.alpha
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.blue
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.get
import androidx.core.graphics.green
import androidx.core.graphics.red
import androidx.core.graphics.scale
import ca.mpreg.imagedecoder.ImageDecoder
import com.hippo.unifile.UniFile
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import java.io.InputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ImageUtil {

    private const val WEBP_MAX_DIMENSION = 16383

    private const val BLANK_THRESHOLD = 240
    private const val CROP_PADDING_FRACTION = 0.01f
    private const val MAX_CROP_FRACTION = 0.15f
    private const val MIN_CROP_GAIN_KEEP = 0.98

    /**
     * Scales an image down to fit the given box (never up) and re-encodes it as lossy WebP.
     *
     * @return the encoded bytes, or null when the original should be kept: animated, undecodable, or a
     * strip taller than twice the box, which fitting would shrink to an unreadable width.
     */
    fun reencodeToWebp(
        imageSource: BufferedSource,
        maxWidth: Int,
        maxHeight: Int,
        quality: Int,
        grayscale: Boolean = false,
        cropMargins: Boolean = false,
    ): ByteArray? {
        if (isAnimatedAndSupported(imageSource)) return null

        val bounds = extractImageOptions(imageSource)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outHeight > maxHeight * 2) return null

        val (width, height) = fitWithin(bounds.outWidth, bounds.outHeight, maxWidth, maxHeight)
        if (max(width, height) > WEBP_MAX_DIMENSION) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, width, height)
        }
        val decoded = BitmapFactory.decodeStream(imageSource.peek().inputStream(), null, options) ?: return null
        val cropped = if (cropMargins) decoded.cropMargins() else decoded
        val (targetWidth, targetHeight) = fitWithin(cropped.width, cropped.height, maxWidth, maxHeight)
        val scaled = if (cropped.width == targetWidth && cropped.height == targetHeight) {
            cropped
        } else {
            cropped.scale(targetWidth, targetHeight)
        }
        if (scaled !== cropped) cropped.recycle()
        val result = if (grayscale) scaled.toGrayscale().also { scaled.recycle() } else scaled

        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        val output = Buffer()
        result.compress(format, quality, output.outputStream())
        result.recycle()
        return output.readByteArray()
    }

    internal data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** Bounding box of everything that isn't near-white or transparent, or null for a blank image. */
    internal fun findContentBounds(width: Int, height: Int, readRow: (y: Int, row: IntArray) -> Unit): Bounds? {
        val row = IntArray(width)
        var left = width
        var right = -1
        var top = -1
        var bottom = -1
        for (y in 0 until height) {
            readRow(y, row)
            val first = row.indexOfFirst { !it.isBackground() }
            if (first < 0) continue
            if (top < 0) top = y
            bottom = y
            left = min(left, first)
            right = max(right, row.indexOfLast { !it.isBackground() })
        }
        return if (top < 0) null else Bounds(left, top, right + 1, bottom + 1)
    }

    /**
     * Turns [content] into the crop rectangle: a small margin is kept, no side loses more than
     * [MAX_CROP_FRACTION], and null means the crop would save too little to be worth it.
     */
    internal fun cropBounds(width: Int, height: Int, content: Bounds?): Bounds? {
        if (content == null) return null
        val pad = max(2, (min(width, height) * CROP_PADDING_FRACTION).roundToInt())
        val maxX = (width * MAX_CROP_FRACTION).toInt()
        val maxY = (height * MAX_CROP_FRACTION).toInt()
        val crop = Bounds(
            left = (content.left - pad).coerceIn(0, maxX),
            top = (content.top - pad).coerceIn(0, maxY),
            right = (content.right + pad).coerceIn(width - maxX, width),
            bottom = (content.bottom + pad).coerceIn(height - maxY, height),
        )
        val area = (crop.right - crop.left).toLong() * (crop.bottom - crop.top)
        return if (area > width.toLong() * height * MIN_CROP_GAIN_KEEP) null else crop
    }

    private fun Int.isBackground(): Boolean {
        return alpha < 16 || (red >= BLANK_THRESHOLD && green >= BLANK_THRESHOLD && blue >= BLANK_THRESHOLD)
    }

    private fun Bitmap.cropMargins(): Bitmap {
        val content = findContentBounds(width, height) { y, row -> getPixels(row, 0, width, 0, y, width, 1) }
        val crop = cropBounds(width, height, content) ?: return this
        return Bitmap.createBitmap(this, crop.left, crop.top, crop.right - crop.left, crop.bottom - crop.top)
            .also { recycle() }
    }

    private fun Bitmap.toGrayscale(): Bitmap {
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        return createBitmap(width, height).applyCanvas { drawBitmap(this@toGrayscale, 0f, 0f, paint) }
    }

    internal fun fitWithin(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        val scale = min(1f, min(maxWidth / width.toFloat(), maxHeight / height.toFloat()))
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /** Largest power-of-two subsampling that still decodes at least the target size. */
    internal fun sampleSizeFor(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        var sampleSize = 1
        while (width / (sampleSize * 2) >= targetWidth && height / (sampleSize * 2) >= targetHeight) {
            sampleSize *= 2
        }
        return sampleSize
    }

    fun isImage(name: String?, openStream: (() -> InputStream)? = null): Boolean {
        if (name == null) return false

        val extension = name.substringAfterLast('.')
        return ImageType.entries.any { it.extension == extension } || openStream?.let { findImageType(it) } != null
    }

    fun findImageType(openStream: () -> InputStream): ImageType? {
        return openStream().use { findImageType(it) }
    }

    fun findImageType(stream: InputStream): ImageType? {
        return try {
            val format = ImageDecoder.open(stream).use { dec -> dec.format }
            when (format) {
                ImageDecoder.Format.JPEG -> ImageType.JPEG
                ImageDecoder.Format.PNG -> ImageType.PNG
                ImageDecoder.Format.WEBP -> ImageType.WEBP
                ImageDecoder.Format.GIF -> ImageType.GIF
                ImageDecoder.Format.AVIF -> ImageType.AVIF
                ImageDecoder.Format.HEIF -> ImageType.HEIF
                ImageDecoder.Format.JXL -> ImageType.JXL
                ImageDecoder.Format.JP2 -> ImageType.JP2
                else -> null
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "findImageType: ${e.message}" }
            null
        }
    }

    fun getExtensionFromMimeType(mime: String?, openStream: () -> InputStream): String {
        val type = mime?.let { ImageType.entries.find { it.mime == mime } } ?: findImageType(openStream)
        return type?.extension ?: "jpg"
    }

    fun isAnimatedAndSupported(source: BufferedSource): Boolean {
        return try {
            val type = findImageType(source.peek().inputStream()) ?: return false
            when (type) {
                ImageType.GIF -> true
                ImageType.WEBP, ImageType.HEIF -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
                    ImageDecoder.open(source.peek().inputStream()).use { dec -> dec.pages > 1 }
                }

                else -> false
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "isAnimatedAndSupported: ${e.message}" }
            false
        }
    }

    enum class ImageType(val mime: String, val extension: String) {
        AVIF("image/avif", "avif"),
        GIF("image/gif", "gif"),
        HEIF("image/heif", "heif"),
        JPEG("image/jpeg", "jpg"),
        JXL("image/jxl", "jxl"),
        PNG("image/png", "png"),
        WEBP("image/webp", "webp"),
        JP2("image/jp2", "jp2"),
        JPX("image/jpx", "jpx"),
    }

    /**
     * Check whether the image is wide (which we consider a double-page spread).
     *
     * @return true if the width is greater than the height
     */
    fun isWideImage(imageSource: BufferedSource): Boolean {
        val options = extractImageOptions(imageSource)
        return options.outWidth > options.outHeight
    }

    /**
     * Extract the 'side' part from [BufferedSource] and return it as [BufferedSource].
     */
    fun splitInHalf(imageSource: BufferedSource, side: Side): BufferedSource {
        val imageBitmap = BitmapFactory.decodeStream(imageSource.inputStream())
        val height = imageBitmap.height
        val width = imageBitmap.width

        val singlePage = Rect(0, 0, width / 2, height)

        val half = createBitmap(width / 2, height)
        val part = when (side) {
            Side.RIGHT -> Rect(width - width / 2, 0, width, height)
            Side.LEFT -> Rect(0, 0, width / 2, height)
        }
        half.applyCanvas {
            drawBitmap(imageBitmap, part, singlePage, null)
        }
        val output = Buffer()
        half.compress(Bitmap.CompressFormat.JPEG, 100, output.outputStream())

        return output
    }

    fun rotateImage(imageSource: BufferedSource, degrees: Float): BufferedSource {
        val imageBitmap = BitmapFactory.decodeStream(imageSource.inputStream())
        val rotated = rotateBitMap(imageBitmap, degrees)

        val output = Buffer()
        rotated.compress(Bitmap.CompressFormat.JPEG, 100, output.outputStream())

        return output
    }

    private fun rotateBitMap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Split the image into left and right parts, then merge them into a new image.
     */
    fun splitAndMerge(imageSource: BufferedSource, upperSide: Side): BufferedSource {
        val imageBitmap = BitmapFactory.decodeStream(imageSource.inputStream())
        val height = imageBitmap.height
        val width = imageBitmap.width

        val result = createBitmap(width / 2, height * 2)
        result.applyCanvas {
            // right -> upper
            val rightPart = when (upperSide) {
                Side.RIGHT -> Rect(width - width / 2, 0, width, height)
                Side.LEFT -> Rect(0, 0, width / 2, height)
            }
            val upperPart = Rect(0, 0, width / 2, height)
            drawBitmap(imageBitmap, rightPart, upperPart, null)
            // left -> bottom
            val leftPart = when (upperSide) {
                Side.LEFT -> Rect(width - width / 2, 0, width, height)
                Side.RIGHT -> Rect(0, 0, width / 2, height)
            }
            val bottomPart = Rect(0, height, width / 2, height * 2)
            drawBitmap(imageBitmap, leftPart, bottomPart, null)
        }

        val output = Buffer()
        result.compress(Bitmap.CompressFormat.JPEG, 100, output.outputStream())
        return output
    }

    enum class Side {
        RIGHT,
        LEFT,
    }

    /**
     * Algorithm for determining what background to accompany a comic/manga page
     */
    fun chooseBackground(context: Context, imageStream: InputStream): Drawable {
        val image = try {
            ImageDecoder.open(imageStream).use { it.decodeNext() to it.isHdr }.let { (res, hdr) ->
                res.use {
                    val config = if (hdr) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888
                    createBitmap(res.width, res.height, config).also { bitmap ->
                        res.image.rewind()
                        bitmap.copyPixelsFromBuffer(res.image)
                    }
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "chooseBackground: ${e.message}" }
            null
        }

        if (image == null) return Color.WHITE.toDrawable()
        return try {
            chooseBackground(context, image)
        } finally {
            image.recycle()
        }
    }

    private fun chooseBackground(context: Context, image: Bitmap): Drawable {
        val whiteColor = Color.WHITE
        if (image.width < 50 || image.height < 50) {
            return whiteColor.toDrawable()
        }

        val top = 5
        val bot = image.height - 5
        val left = (image.width * 0.0275).toInt()
        val right = image.width - left
        val midX = image.width / 2
        val midY = image.height / 2
        val offsetX = (image.width * 0.01).toInt()
        val leftOffsetX = left - offsetX
        val rightOffsetX = right + offsetX

        val topLeftPixel = image[left, top]
        val topRightPixel = image[right, top]
        val midLeftPixel = image[left, midY]
        val midRightPixel = image[right, midY]
        val topCenterPixel = image[midX, top]
        val botLeftPixel = image[left, bot]
        val bottomCenterPixel = image[midX, bot]
        val botRightPixel = image[right, bot]

        val topLeftIsDark = topLeftPixel.isDark()
        val topRightIsDark = topRightPixel.isDark()
        val midLeftIsDark = midLeftPixel.isDark()
        val midRightIsDark = midRightPixel.isDark()
        val topMidIsDark = topCenterPixel.isDark()
        val botLeftIsDark = botLeftPixel.isDark()
        val botRightIsDark = botRightPixel.isDark()

        var darkBG =
            (topLeftIsDark && (botLeftIsDark || botRightIsDark || topRightIsDark || midLeftIsDark || topMidIsDark)) ||
                (topRightIsDark && (botRightIsDark || botLeftIsDark || midRightIsDark || topMidIsDark))

        val topAndBotPixels =
            listOf(topLeftPixel, topCenterPixel, topRightPixel, botRightPixel, bottomCenterPixel, botLeftPixel)
        val isNotWhiteAndCloseTo = topAndBotPixels.mapIndexed { index, color ->
            val other = topAndBotPixels[(index + 1) % topAndBotPixels.size]
            !color.isWhite() && color.isCloseTo(other)
        }
        if (isNotWhiteAndCloseTo.all { it }) {
            return topLeftPixel.toDrawable()
        }

        val cornerPixels = listOf(topLeftPixel, topRightPixel, botLeftPixel, botRightPixel)
        val numberOfWhiteCorners = cornerPixels.map { cornerPixel -> cornerPixel.isWhite() }
            .filter { it }
            .size
        if (numberOfWhiteCorners > 2) {
            darkBG = false
        }

        var blackColor = when {
            topLeftIsDark -> topLeftPixel
            topRightIsDark -> topRightPixel
            botLeftIsDark -> botLeftPixel
            botRightIsDark -> botRightPixel
            else -> whiteColor
        }

        var overallWhitePixels = 0
        var overallBlackPixels = 0
        var topBlackStreak = 0
        var topWhiteStreak = 0
        var botBlackStreak = 0
        var botWhiteStreak = 0
        outer@ for (x in intArrayOf(left, right, leftOffsetX, rightOffsetX)) {
            var whitePixelsStreak = 0
            var whitePixels = 0
            var blackPixelsStreak = 0
            var blackPixels = 0
            var blackStreak = false
            var whiteStreak = false
            val notOffset = x == left || x == right
            inner@ for ((index, y) in (0..<image.height step image.height / 25).withIndex()) {
                val pixel = image[x, y]
                val pixelOff = image[x + (if (x < image.width / 2) -offsetX else offsetX), y]
                if (pixel.isWhite()) {
                    whitePixelsStreak++
                    whitePixels++
                    if (notOffset) {
                        overallWhitePixels++
                    }
                    if (whitePixelsStreak > 14) {
                        whiteStreak = true
                    }
                    if (whitePixelsStreak > 6 && whitePixelsStreak >= index - 1) {
                        topWhiteStreak = whitePixelsStreak
                    }
                } else {
                    whitePixelsStreak = 0
                    if (pixel.isDark() && pixelOff.isDark()) {
                        blackPixels++
                        if (notOffset) {
                            overallBlackPixels++
                        }
                        blackPixelsStreak++
                        if (blackPixelsStreak >= 14) {
                            blackStreak = true
                        }
                        continue@inner
                    }
                }
                if (blackPixelsStreak > 6 && blackPixelsStreak >= index - 1) {
                    topBlackStreak = blackPixelsStreak
                }
                blackPixelsStreak = 0
            }
            if (blackPixelsStreak > 6) {
                botBlackStreak = blackPixelsStreak
            } else if (whitePixelsStreak > 6) {
                botWhiteStreak = whitePixelsStreak
            }
            when {
                blackPixels > 22 -> {
                    if (x == right || x == rightOffsetX) {
                        blackColor = when {
                            topRightIsDark -> topRightPixel
                            botRightIsDark -> botRightPixel
                            else -> blackColor
                        }
                    }
                    darkBG = true
                    overallWhitePixels = 0
                    break@outer
                }
                blackStreak -> {
                    darkBG = true
                    if (x == right || x == rightOffsetX) {
                        blackColor = when {
                            topRightIsDark -> topRightPixel
                            botRightIsDark -> botRightPixel
                            else -> blackColor
                        }
                    }
                    if (blackPixels > 18) {
                        overallWhitePixels = 0
                        break@outer
                    }
                }
                whiteStreak || whitePixels > 22 -> darkBG = false
            }
        }

        val topIsBlackStreak = topBlackStreak > topWhiteStreak
        val bottomIsBlackStreak = botBlackStreak > botWhiteStreak
        if (overallWhitePixels > 9 && overallWhitePixels > overallBlackPixels) {
            darkBG = false
        }
        if (topIsBlackStreak && bottomIsBlackStreak) {
            darkBG = true
        }

        val isLandscape = context.resources.configuration?.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (isLandscape) {
            return when {
                darkBG -> blackColor.toDrawable()
                else -> whiteColor.toDrawable()
            }
        }

        val botCornersIsWhite = botLeftPixel.isWhite() && botRightPixel.isWhite()
        val topCornersIsWhite = topLeftPixel.isWhite() && topRightPixel.isWhite()

        val topCornersIsDark = topLeftIsDark && topRightIsDark
        val botCornersIsDark = botLeftIsDark && botRightIsDark

        val topOffsetCornersIsDark = image[leftOffsetX, top].isDark() && image[rightOffsetX, top].isDark()
        val botOffsetCornersIsDark = image[leftOffsetX, bot].isDark() && image[rightOffsetX, bot].isDark()

        val gradient = when {
            darkBG && botCornersIsWhite -> {
                intArrayOf(blackColor, blackColor, whiteColor, whiteColor)
            }
            darkBG && topCornersIsWhite -> {
                intArrayOf(whiteColor, whiteColor, blackColor, blackColor)
            }
            darkBG -> {
                return blackColor.toDrawable()
            }
            topIsBlackStreak ||
                (
                    topCornersIsDark &&
                        topOffsetCornersIsDark &&
                        (topMidIsDark || overallBlackPixels > 9)
                    ) -> {
                intArrayOf(blackColor, blackColor, whiteColor, whiteColor)
            }
            bottomIsBlackStreak ||
                (
                    botCornersIsDark &&
                        botOffsetCornersIsDark &&
                        (bottomCenterPixel.isDark() || overallBlackPixels > 9)
                    ) -> {
                intArrayOf(whiteColor, whiteColor, blackColor, blackColor)
            }
            else -> {
                return whiteColor.toDrawable()
            }
        }

        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            gradient,
        )
    }

    private fun @receiver:ColorInt Int.isDark(): Boolean =
        red < 40 && blue < 40 && green < 40 && alpha > 200

    private fun @receiver:ColorInt Int.isCloseTo(other: Int): Boolean =
        abs(red - other.red) < 30 && abs(green - other.green) < 30 && abs(blue - other.blue) < 30

    private fun @receiver:ColorInt Int.isWhite(): Boolean =
        red + blue + green > 740

    /**
     * Used to check an image's dimensions without loading it in the memory.
     */
    private fun extractImageOptions(imageSource: BufferedSource): BitmapFactory.Options {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(imageSource.peek().inputStream(), null, options)
        return options
    }
}
