package com.vanu.giffaceswap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GifEncoderTest {

    @Test
    fun encode_noisyFrames_producesDecodableGif89a() {
        val rnd = Random(42)
        val w = 160
        val h = 120
        val frames = (0 until 4).map {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val px = IntArray(w * h) {
                Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
            }
            bmp.setPixels(px, 0, w, 0, 0, w, h)
            bmp to 80
        }
        // Stable path so we can also validate externally with Pillow after the unit test.
        val out = File("/tmp/gif_encoder_unit_test.gif")
        try {
            GifEncoder.encode(frames, out, loop = true)
            assertTrue("file too small: ${out.length()}", out.length() > 64)
            val bytes = out.readBytes()
            assertEquals('G'.code.toByte(), bytes[0])
            assertEquals('I'.code.toByte(), bytes[1])
            assertEquals('F'.code.toByte(), bytes[2])
            assertEquals('8'.code.toByte(), bytes[3])
            assertEquals('9'.code.toByte(), bytes[4])
            assertEquals('a'.code.toByte(), bytes[5])
            // GCT flag must be set (gallery-friendly)
            assertTrue("GCT flag missing", (bytes[10].toInt() and 0x80) != 0)
            // Structural parse: Graphic Control + Image Descriptor pairs
            val delays = GifDecoder.parseDelays(bytes)
            assertEquals("expected 4 frames, got ${delays.size}", 4, delays.size)
            // BitmapFactory should decode at least the first frame
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertNotNull("BitmapFactory failed to decode first frame", decoded)
            assertEquals(w, decoded!!.width)
            assertEquals(h, decoded.height)
            GifEncoder.verifyGifFile(out)
        } finally {
            frames.forEach { it.first.recycle() }
        }
    }

    @Test
    fun verifyGifFile_rejectsTinyOrNonGif() {
        val bad = File.createTempFile("bad_", ".gif")
        try {
            bad.writeBytes(byteArrayOf(1, 2, 3))
            try {
                GifEncoder.verifyGifFile(bad)
                fail("expected GifException")
            } catch (_: GifException) { }
            bad.writeBytes("NOTGIF............".toByteArray())
            try {
                GifEncoder.verifyGifFile(bad)
                fail("expected GifException")
            } catch (_: GifException) { }
        } finally {
            bad.delete()
        }
    }
}
