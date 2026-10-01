package com.vanu.giffaceswap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.util.Log
import com.vanu.faceswap.core.*
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.min
import kotlin.math.roundToInt

enum class EnhanceMode { OFF, LIGHT, HQ }

data class GifJobParams(
    val gifPath: String,
    val facesPath: String,
    val rotation: Int,
    val enhance: EnhanceMode,
    val accelerator: Boolean,
    val outputPath: String,
)

data class GifJobResult(
    val file: File,
    val frames: Int,
    val seconds: Double,
    val width: Int,
    val height: Int,
    val swappedFaces: Int,
    val sourceFaces: Int,
    val canFlip: Boolean,
    val skippedNoFace: Int,
)

class EtaEstimator(private val smoothing: Double = 0.15) {
    private var avg = 0.0
    fun add(sample: Double) { avg = if (avg == 0.0) sample else avg * (1 - smoothing) + sample * smoothing }
    fun remaining(left: Int): Double? = if (avg <= 0) null else avg * left
    companion object {
        fun format(s: Double): String {
            val t = s.roundToInt()
            return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60)
            else "%d:%02d".format(t / 60, t % 60)
        }
    }
}

class GifProcessor(
    private val context: Context,
    private val models: AiModelFiles,
    private val lightEnhancer: File?,
    private val log: (String) -> Unit = { Log.i(ImageUtils.TAG, it) },
) {
    fun interface Progress { fun update(stage: Int, done: Int, total: Int, etaSeconds: Double?) }

    fun run(p: GifJobParams, source: RgbImage, sourceFaces: List<FaceData>,
            progress: Progress, cancelled: () -> Boolean): GifJobResult {
        val t0 = System.nanoTime()
        val gifFile = File(p.gifPath)
        val (info, rawFrames) = GifDecoder.decode(gifFile)
        try {
            val indices = GifPlan.selectIndices(info.delaysMs)
            if (indices.isEmpty()) throw GifException("No usable frames in that GIF.")
            val (W, H, scale) = GifPlan.outSize(info.width, info.height)
            log("gif ${info.width}x${info.height} ${info.frameCount}f ${info.durationMs}ms -> ${W}x$H ${indices.size} frames")

            // Scale selected frames
            progress.update(0, 0, indices.size, null)
            val frames = ArrayList<Pair<Bitmap, Int>>(indices.size)
            for ((fi, idx) in indices.withIndex()) {
                if (cancelled()) throw CancellationException()
                val src = rawFrames[idx].bitmap
                val scaled = if (src.width == W && src.height == H) src.copy(Bitmap.Config.ARGB_8888, true)
                else Bitmap.createScaledBitmap(src, W, H, true)
                frames.add(scaled to rawFrames[idx].delayMs)
                progress.update(0, fi + 1, indices.size, null)
            }

            val nSrc = sourceFaces.size
            val detector = FaceDetector(context)
            val dets = ArrayList<List<FaceData>>(frames.size)
            try {
                val eta = EtaEstimator(); var last = System.nanoTime()
                for ((i, pair) in frames.withIndex()) {
                    if (cancelled()) throw CancellationException()
                    dets.add(detectFrame(detector, pair.first, min(nSrc, 2)))
                    val now = System.nanoTime(); eta.add((now - last) / 1e9); last = now
                    progress.update(0, i + 1, frames.size, eta.remaining(frames.size - i - 1))
                }
            } finally { detector.close() }

            val anyFace = dets.any { it.isNotEmpty() }
            if (!anyFace) throw NoFaceException(
                "No faces found in the GIF frames. Pick a GIF where the face is clearly visible and facing the camera.")

            // Pairing: left-to-right on first frame with enough faces
            val pairFrame = dets.indexOfFirst { it.size >= min(nSrc, 2).coerceAtLeast(1) }
                .let { if (it < 0) dets.indexOfFirst { f -> f.isNotEmpty() } else it }
            if (pairFrame < 0) throw NoFaceException("No faces found in the GIF frames.")

            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            val engine = AiEngine(models, threads, p.accelerator, lowMemory = true) { Log.w(ImageUtils.TAG, it) }
            val out = File(p.outputPath); out.parentFile?.mkdirs()
            val tmpFrames = ArrayList<Pair<Bitmap, Int>>(frames.size)
            var skipped = 0
            try {
                val usedSrc = (0 until min(nSrc, dets[pairFrame].size)).toSet()
                val latents = HashMap<Int, FloatArray>()
                engine.open(models.arcface).use { a ->
                    for (si in usedSrc) {
                        val srcIdx = if (nSrc >= 2) Math.floorMod(si + p.rotation, nSrc) else 0
                        latents[si] = engine.latent(engine.embedding(a, source, AiMath.kps5(sourceFaces[srcIdx])).first)
                    }
                }
                val enhFile = when (p.enhance) {
                    EnhanceMode.OFF -> null
                    EnhanceMode.LIGHT -> lightEnhancer
                    EnhanceMode.HQ -> models.enhancer
                }
                val enhSize = if (p.enhance == EnhanceMode.LIGHT) 256 else 512
                engine.open(models.swapper).use { sw ->
                    val enh = enhFile?.let { engine.open(it) }
                    try {
                        val eta = EtaEstimator(); var last = System.nanoTime()
                        for (i in frames.indices) {
                            if (cancelled()) throw CancellationException()
                            val bmp = frames[i].first
                            val rgb = FaceDetector.toRgb(bmp)
                            val faces = dets[i]
                            if (faces.isEmpty()) {
                                skipped++
                            } else {
                                val n = min(faces.size, latents.size)
                                for (ti in 0 until n) {
                                    val latent = latents[ti] ?: continue
                                    engine.swapFace(sw, rgb, faces[ti], latent)
                                    if (enh != null) engine.enhance(enh, rgb, faces[ti], 0.8, enhSize)
                                }
                            }
                            val outBmp = FaceDetector.toBitmap(rgb)
                            tmpFrames.add(outBmp to frames[i].second)
                            bmp.recycle()
                            val now = System.nanoTime(); eta.add((now - last) / 1e9); last = now
                            progress.update(1, i + 1, frames.size, eta.remaining(frames.size - i - 1))
                        }
                    } finally { enh?.close() }
                }
                if (cancelled()) throw CancellationException()
                progress.update(2, 0, 1, null)
                GifEncoder.encode(tmpFrames, out, loop = true)
                val secs = (System.nanoTime() - t0) / 1e9
                return GifJobResult(out, tmpFrames.size, secs, W, H, usedSrc.size, nSrc,
                    canFlip = nSrc >= 2, skippedNoFace = skipped)
            } finally {
                engine.close()
                tmpFrames.forEach { it.first.recycle() }
            }
        } finally {
            rawFrames.forEach { it.bitmap.recycle() }
        }
    }

    private fun detectFrame(detector: FaceDetector, bitmap: Bitmap, expected: Int): List<FaceData> {
        val faces = detector.detectRegion(bitmap, 0, 0, bitmap.width, bitmap.height).toMutableList()
        if (faces.size < expected) {
            val w = bitmap.width; val h = bitmap.height
            val tw = (w / 2.0 * 1.5).toInt().coerceAtMost(w)
            val th = (h / 2.0 * 1.5).toInt().coerceAtMost(h)
            for (gy in 0 until 2) for (gx in 0 until 2) {
                val x0 = Math.rint((w - tw) * gx.toDouble()).toInt()
                val y0 = Math.rint((h - th) * gy.toDouble()).toInt()
                for (f in detector.detectRegion(bitmap, x0, y0, tw, th)) detector.mergeFace(faces, f)
            }
        }
        return faces.sortedBy { it.centerX }
    }
}

/** Scale a bitmap into a white canvas of W×H (unused helper kept for tests). */
fun letterbox(src: Bitmap, W: Int, H: Int): Bitmap {
    val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    c.drawColor(Color.WHITE)
    val s = min(W.toFloat() / src.width, H.toFloat() / src.height)
    val m = Matrix().apply {
        postScale(s, s)
        postTranslate((W - src.width * s) / 2f, (H - src.height * s) / 2f)
    }
    c.drawBitmap(src, m, null)
    return out
}
