package com.vanu.giffaceswap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class GifException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class GifInfo(
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val durationMs: Int,
    val delaysMs: IntArray,
    val fileBytes: Long,
)

data class GifFrame(val bitmap: Bitmap, val delayMs: Int)

object GifPlan {
    const val MAX_SHORT_SIDE = 480
    const val MAX_FRAMES = 80
    const val MAX_DURATION_MS = 12_000
    const val MAX_FILE_BYTES = 25L * 1024 * 1024
    const val MIN_DELAY_MS = 40
    const val DEFAULT_DELAY_MS = 100

    fun outSize(w: Int, h: Int): Triple<Int, Int, Float> {
        val short = min(w, h).toFloat()
        val scale = if (short > MAX_SHORT_SIDE) MAX_SHORT_SIDE / short else 1f
        var ow = max(1, (w * scale).roundToInt())
        var oh = max(1, (h * scale).roundToInt())
        // keep even dims for nicer encode
        if (ow % 2 != 0) ow--
        if (oh % 2 != 0) oh--
        ow = max(2, ow); oh = max(2, oh)
        return Triple(ow, oh, ow.toFloat() / w)
    }

    /** Pick evenly spaced indices so count ≤ MAX_FRAMES and total duration ≤ MAX_DURATION_MS. */
    fun selectIndices(delays: IntArray): IntArray {
        val n = delays.size
        if (n == 0) return intArrayOf()
        var cum = IntArray(n)
        var t = 0
        for (i in 0 until n) { t += delays[i].coerceAtLeast(MIN_DELAY_MS); cum[i] = t }
        val total = cum.last().coerceAtMost(MAX_DURATION_MS)
        val maxByTime = delays.indices.filter { (if (it == 0) 0 else cum[it - 1]) < total }
        val pool = if (maxByTime.isEmpty()) listOf(0) else maxByTime
        if (pool.size <= MAX_FRAMES) return pool.toIntArray()
        val out = IntArray(MAX_FRAMES)
        for (i in 0 until MAX_FRAMES) out[i] = pool[(i * (pool.size - 1).toDouble() / (MAX_FRAMES - 1)).roundToInt()]
        return out.distinct().toIntArray()
    }
}

object GifDecoder {
    /**
     * Decode an animated GIF. Uses Android [Movie] for rasterization and a light parse of Graphic
     * Control Extensions for per-frame delays. Frames are returned at the GIF's native size.
     */
    fun decode(file: File): Pair<GifInfo, List<GifFrame>> {
        if (!file.isFile) throw GifException("GIF file missing.")
        if (file.length() > GifPlan.MAX_FILE_BYTES)
            throw GifException("That GIF is too large (max ${GifPlan.MAX_FILE_BYTES / (1024 * 1024)} MB).")
        val bytes = file.readBytes()
        if (bytes.size < 13 || bytes[0] != 'G'.code.toByte() || bytes[1] != 'I'.code.toByte() || bytes[2] != 'F'.code.toByte())
            throw GifException("That file isn't a GIF.")
        val delays = parseDelays(bytes)
        if (delays.isEmpty()) throw GifException("No frames found in that GIF.")
        val movie = Movie.decodeByteArray(bytes, 0, bytes.size)
            ?: throw GifException("Couldn't decode that GIF.")
        val w = movie.width(); val h = movie.height()
        if (w <= 0 || h <= 0) throw GifException("Invalid GIF dimensions.")
        val duration = movie.duration().let { if (it > 0) it else delays.sum().coerceAtLeast(GifPlan.DEFAULT_DELAY_MS) }
        // If Movie didn't expose duration, pad/trim delays to match frame count we can sample.
        val frameDelays = if (delays.size >= 1) delays else intArrayOf(GifPlan.DEFAULT_DELAY_MS)
        val frames = ArrayList<GifFrame>(frameDelays.size)
        var t = 0
        for (i in frameDelays.indices) {
            val delay = frameDelays[i].coerceAtLeast(GifPlan.MIN_DELAY_MS)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            movie.setTime(t.coerceAtMost(duration.coerceAtLeast(1) - 1).coerceAtLeast(0))
            movie.draw(canvas, 0f, 0f)
            frames.add(GifFrame(bmp, delay))
            t += delay
            if (duration > 0 && t >= duration && i < frameDelays.size - 1) {
                // Movie ended early — keep remaining as copies of last frame with their delays
            }
        }
        val info = GifInfo(w, h, frames.size, frames.sumOf { it.delayMs }, frames.map { it.delayMs }.toIntArray(), file.length())
        return info to frames
    }

    /** Collect disposal-delay values (in ms) from every Graphic Control Extension. */
    fun parseDelays(data: ByteArray): IntArray {
        val out = ArrayList<Int>()
        var i = 13 // skip header + LSD
        if (data.size < 13) return intArrayOf()
        val packed = data[10].toInt() and 0xff
        if (packed and 0x80 != 0) {
            val gctSize = 3 * (1 shl ((packed and 0x07) + 1))
            i += gctSize
        }
        var pendingDelay = GifPlan.DEFAULT_DELAY_MS
        while (i < data.size) {
            when (data[i].toInt() and 0xff) {
                0x3B -> break // trailer
                0x21 -> { // extension
                    if (i + 2 >= data.size) break
                    val label = data[i + 1].toInt() and 0xff
                    i += 2
                    if (label == 0xF9 && i + 5 < data.size) {
                        // Graphic Control Extension: block size, packed, delay (LE, 1/100s), trans, terminator
                        val blockSize = data[i].toInt() and 0xff
                        if (blockSize >= 4) {
                            val centi = (data[i + 2].toInt() and 0xff) or ((data[i + 3].toInt() and 0xff) shl 8)
                            pendingDelay = (centi * 10).coerceAtLeast(GifPlan.MIN_DELAY_MS)
                        }
                        i++ // block size byte
                        while (i < data.size) {
                            val sz = data[i].toInt() and 0xff; i++
                            if (sz == 0) break
                            i += sz
                        }
                    } else {
                        while (i < data.size) {
                            val sz = data[i].toInt() and 0xff; i++
                            if (sz == 0) break
                            i += sz
                        }
                    }
                }
                0x2C -> { // image descriptor
                    if (i + 10 >= data.size) break
                    out.add(pendingDelay)
                    pendingDelay = GifPlan.DEFAULT_DELAY_MS
                    val localPacked = data[i + 9].toInt() and 0xff
                    i += 10
                    if (localPacked and 0x80 != 0) {
                        val lct = 3 * (1 shl ((localPacked and 0x07) + 1))
                        i += lct
                    }
                    if (i >= data.size) break
                    i++ // LZW min code size
                    while (i < data.size) {
                        val sz = data[i].toInt() and 0xff; i++
                        if (sz == 0) break
                        i += sz
                    }
                }
                else -> i++ // resync
            }
        }
        return out.toIntArray()
    }
}

/**
 * GIF89a encoder: median-cut palette (≤256) + LZW. Good enough for face-swap GIFs without a
 * native library. Based on the classic AnimatedGifEncoder approach (public-domain lineage).
 */
object GifEncoder {
    fun encode(frames: List<Pair<Bitmap, Int>>, outFile: File, loop: Boolean = true) {
        if (frames.isEmpty()) throw GifException("No frames to encode.")
        val tmp = File(outFile.parentFile, outFile.name + ".part")
        FileOutputStream(tmp).use { os ->
            write(frames, os, loop)
        }
        if (!tmp.renameTo(outFile)) {
            tmp.copyTo(outFile, overwrite = true)
            tmp.delete()
        }
    }

    fun write(frames: List<Pair<Bitmap, Int>>, os: OutputStream, loop: Boolean) {
        val w = frames[0].first.width
        val h = frames[0].first.height
        // header
        os.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShort(os, w); writeShort(os, h)
        os.write(0x70) // GCT flag off for now; we'll write local tables per frame for better quality
        os.write(0); os.write(0) // bg index, aspect

        if (loop) {
            // Netscape application extension
            os.write(0x21); os.write(0xFF); os.write(11)
            os.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
            os.write(3); os.write(1); writeShort(os, 0); os.write(0)
        }

        for ((bmp, delayMs) in frames) {
            require(bmp.width == w && bmp.height == h) { "frame size mismatch" }
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            val (indexed, palette) = quantize(pixels, 256)
            // Graphic Control Extension
            os.write(0x21); os.write(0xF9); os.write(4)
            os.write(0x00) // no transparency, disposal 0
            writeShort(os, (delayMs / 10).coerceAtLeast(2))
            os.write(0); os.write(0)
            // Image Descriptor + local color table
            os.write(0x2C)
            writeShort(os, 0); writeShort(os, 0); writeShort(os, w); writeShort(os, h)
            val palSize = paletteSizeBits(palette.size)
            os.write(0x80 or palSize) // local color table
            for (i in 0 until (1 shl (palSize + 1))) {
                if (i < palette.size) {
                    val c = palette[i]
                    os.write((c shr 16) and 0xff); os.write((c shr 8) and 0xff); os.write(c and 0xff)
                } else {
                    os.write(0); os.write(0); os.write(0)
                }
            }
            val minCode = (palSize + 1).coerceAtLeast(2)
            os.write(minCode)
            lzwEncode(indexed, 1 shl minCode, os)
        }
        os.write(0x3B) // trailer
        os.flush()
    }

    private fun paletteSizeBits(n: Int): Int {
        var bits = 0
        var size = 2
        while (size < n && bits < 7) { size *= 2; bits++ }
        return bits
    }

    private fun writeShort(os: OutputStream, v: Int) {
        os.write(v and 0xff); os.write((v shr 8) and 0xff)
    }

    /** Median-cut color quantization → indexed pixels + RGB palette (packed 0x00RRGGBB). */
    fun quantize(pixels: IntArray, maxColors: Int): Pair<ByteArray, IntArray> {
        // sample unique-ish colors
        val colors = ArrayList<Int>(min(pixels.size, 4096))
        val step = max(1, pixels.size / 4000)
        var i = 0
        while (i < pixels.size) {
            val p = pixels[i]
            colors.add(p and 0x00FFFFFF)
            i += step
        }
        if (colors.isEmpty()) colors.add(0)
        val palette = medianCut(colors, maxColors.coerceIn(2, 256))
        // map each pixel to nearest palette entry
        val index = ByteArray(pixels.size)
        val cache = HashMap<Int, Int>(4096)
        for (pi in pixels.indices) {
            val rgb = pixels[pi] and 0x00FFFFFF
            val mapped = cache.getOrPut(rgb) { nearest(rgb, palette) }
            index[pi] = mapped.toByte()
        }
        return index to palette
    }

    private fun nearest(rgb: Int, palette: IntArray): Int {
        val r = (rgb shr 16) and 0xff; val g = (rgb shr 8) and 0xff; val b = rgb and 0xff
        var best = 0; var bestD = Int.MAX_VALUE
        for (i in palette.indices) {
            val c = palette[i]
            val dr = r - ((c shr 16) and 0xff)
            val dg = g - ((c shr 8) and 0xff)
            val db = b - (c and 0xff)
            val d = dr * dr + dg * dg + db * db
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    private fun medianCut(colors: ArrayList<Int>, maxColors: Int): IntArray {
        data class Box(val list: MutableList<Int>) {
            fun channelRange(): Triple<Int, Int, Int> { // channel, min, max span
                var r0 = 255; var r1 = 0; var g0 = 255; var g1 = 0; var b0 = 255; var b1 = 0
                for (c in list) {
                    val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
                    if (r < r0) r0 = r; if (r > r1) r1 = r
                    if (g < g0) g0 = g; if (g > g1) g1 = g
                    if (b < b0) b0 = b; if (b > b1) b1 = b
                }
                val rs = r1 - r0; val gs = g1 - g0; val bs = b1 - b0
                return when {
                    rs >= gs && rs >= bs -> Triple(0, r0, rs)
                    gs >= rs && gs >= bs -> Triple(1, g0, gs)
                    else -> Triple(2, b0, bs)
                }
            }
            fun average(): Int {
                if (list.isEmpty()) return 0
                var r = 0L; var g = 0L; var b = 0L
                for (c in list) { r += (c shr 16) and 0xff; g += (c shr 8) and 0xff; b += c and 0xff }
                val n = list.size.toLong()
                return (((r / n).toInt() and 0xff) shl 16) or (((g / n).toInt() and 0xff) shl 8) or ((b / n).toInt() and 0xff)
            }
        }
        val boxes = ArrayList<Box>()
        boxes.add(Box(colors.toMutableList()))
        while (boxes.size < maxColors) {
            var bi = -1; var bestSpan = -1
            for (i in boxes.indices) {
                if (boxes[i].list.size < 2) continue
                val span = boxes[i].channelRange().third
                if (span > bestSpan) { bestSpan = span; bi = i }
            }
            if (bi < 0) break
            val box = boxes.removeAt(bi)
            val ch = box.channelRange().first
            box.list.sortBy { c -> when (ch) { 0 -> (c shr 16) and 0xff; 1 -> (c shr 8) and 0xff; else -> c and 0xff } }
            val mid = box.list.size / 2
            boxes.add(Box(box.list.subList(0, mid).toMutableList()))
            boxes.add(Box(box.list.subList(mid, box.list.size).toMutableList()))
        }
        return IntArray(boxes.size) { boxes[it].average() }
    }

    /** GIF LZW encode of indexed pixels; writes sub-blocks ending with a 0-size block. */
    fun lzwEncode(index: ByteArray, clearSize: Int, os: OutputStream) {
        val clear = clearSize
        val eof = clear + 1
        // Caller already wrote minCodeSize; clear = 1<<minCodeSize; initial code width = minCodeSize+1
        val initWidth = Integer.numberOfTrailingZeros(clear) + 1
        val table = HashMap<Long, Int>(4096)
        fun key(prefix: Int, k: Int) = (prefix.toLong() shl 12) or (k.toLong() and 0xfff)

        var nextCode = eof + 1
        var width = initWidth

        val baos = ByteArrayOutputStream()
        var acc = 0
        var accBits = 0
        fun emit(code: Int, nbits: Int) {
            acc = acc or (code shl accBits)
            accBits += nbits
            while (accBits >= 8) {
                baos.write(acc and 0xff)
                acc = acc ushr 8
                accBits -= 8
            }
        }
        fun flushBlocks() {
            if (accBits > 0) { baos.write(acc and 0xff); acc = 0; accBits = 0 }
            val data = baos.toByteArray(); baos.reset()
            var off = 0
            while (off < data.size) {
                val n = min(255, data.size - off)
                os.write(n); os.write(data, off, n); off += n
            }
            os.write(0)
        }
        fun resetTable() {
            table.clear()
            nextCode = eof + 1
            width = initWidth
            emit(clear, width)
        }

        resetTable()
        if (index.isEmpty()) {
            emit(eof, width)
            flushBlocks()
            return
        }

        var prefix = index[0].toInt() and 0xff
        var i = 1
        while (i < index.size) {
            val k = index[i].toInt() and 0xff
            val kk = key(prefix, k)
            val existing = table[kk]
            if (existing != null) {
                prefix = existing
            } else {
                emit(prefix, width)
                if (nextCode < 4096) {
                    table[kk] = nextCode
                    if (nextCode == (1 shl width) && width < 12) width++
                    nextCode++
                } else {
                    resetTable()
                }
                prefix = k
            }
            i++
        }
        emit(prefix, width)
        emit(eof, width)
        flushBlocks()
    }
}