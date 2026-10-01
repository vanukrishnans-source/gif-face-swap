package com.vanu.faceswap.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** One downloadable model file. [urls] are tried in order (primary, then mirror). */
class ModelSpec(
    val file: String, val label: String, val bytes: Long, val sha256: String, val urls: List<String>,
)

object Models {
    private const val GH = "https://github.com/facefusion/facefusion-assets/releases/download/models-3.0.0/"
    private const val HF = "https://huggingface.co/facefusion/models-3.0.0/resolve/main/"
    private fun spec(file: String, label: String, bytes: Long, sha: String) =
        ModelSpec(file, label, bytes, sha, listOf(GH + file, HF + file))

    val ARCFACE = spec("arcface_w600k_r50.onnx", "Face identity (ArcFace)", 174_388_474L,
        "f1f79dc3b0b79a69f94799af1fffebff09fbd78fd96a275fd8f0cbbea23270d1")
    val SWAPPER = spec("inswapper_128_fp16.onnx", "Face swap (inswapper 128)", 277_680_829L,
        "c4eccca86ad177586c85c28bf1a64a9d9ed237e283a15818d831f7facfd3f420")
    val ENHANCER = spec("gpen_bfr_512.onnx", "Detail enhancer (GPEN 512)", 284_340_240L,
        "d5f066b9068a8b74217f9712e28e875a6144629b108a6f7355acbdb3a2832c54")
    /** Optional (video app): lighter GPEN-256 enhancer, ~8x faster than GPEN-512 per face. */
    val ENHANCER_LIGHT = spec("gpen_bfr_256.onnx", "Light detail enhancer (GPEN 256)", 75_792_988L,
        "bad8bf0426873828df2dbf4e3b3d9ababba9da7965b8b72426569486f7ae5c25")

    /** Photo app (Couple Face Swap): everything it downloads on first run (736 MB). */
    val ALL = listOf(ARCFACE, SWAPPER, ENHANCER)
    val TOTAL_BYTES = ALL.sumOf { it.bytes }

    /** Video app (Face Swap Video): first-run download (452 MB); the enhancers are optional extras. */
    val VIDEO_REQUIRED = listOf(ARCFACE, SWAPPER)
    val VIDEO_REQUIRED_BYTES = VIDEO_REQUIRED.sumOf { it.bytes }
}

class ChecksumException(message: String) : IOException(message)
class ModelMissingException : Exception("models missing")
class StorageException(message: String) : IOException(message)

/**
 * Stores models in [dir] (app-internal files dir on Android). A model counts as installed when the
 * file has the expected size and a "<file>.ok" marker holds its verified SHA-256 — so startup never
 * re-hashes 700 MB. Downloads go to "<file>.part" and resume with HTTP Range requests.
 */
class ModelStore(val dir: File, private val specs: List<ModelSpec> = Models.ALL) {
    class Progress(val done: Long, val total: Long, val file: String, val verifying: Boolean, val bytesPerSec: Double)

    init { dir.mkdirs() }

    fun file(s: ModelSpec) = File(dir, s.file)
    private fun part(s: ModelSpec) = File(dir, s.file + ".part")
    private fun marker(s: ModelSpec) = File(dir, s.file + ".ok")

    fun isInstalled(s: ModelSpec): Boolean {
        val f = file(s); val m = marker(s)
        return f.isFile && f.length() == s.bytes && m.isFile && runCatching { m.readText().trim() }.getOrNull() == s.sha256
    }

    fun allInstalled() = specs.all { isInstalled(it) }

    /** Bytes already on disk (installed files + partial downloads). */
    fun bytesPresent(): Long = specs.sumOf { s ->
        if (isInstalled(s)) s.bytes else part(s).let { if (it.isFile) minOf(it.length(), s.bytes) else 0L }
    }

    fun files() = AiModelFiles(file(Models.ARCFACE), file(Models.SWAPPER), file(Models.ENHANCER))

    /** Remove everything (e.g. "re-download models"). */
    fun clear() { specs.forEach { file(it).delete(); part(it).delete(); marker(it).delete() } }

    /**
     * Download + verify every missing model. Blocking; call from a background thread.
     * Throws [CancellationException] when [cancelled] returns true, [IOException] on failure
     * (partial data is kept so the next call resumes).
     */
    fun downloadAll(cancelled: () -> Boolean = { false }, progress: (Progress) -> Unit = {}) {
        val missing = specs.filter { !isInstalled(it) }
        if (missing.isEmpty()) return
        val need = missing.sumOf { it.bytes - (part(it).takeIf { p -> p.isFile }?.length() ?: 0L) }
        val free = dir.usableSpace
        if (free in 1 until need + 50_000_000L)
            throw StorageException("Not enough free storage: need ${mb(need + 50_000_000L)}, only ${mb(free)} free.")
        val total = Models.TOTAL_BYTES
        var base = specs.filter { isInstalled(it) }.sumOf { it.bytes }
        for (s in missing) {
            var attempt = 0
            while (true) {
                try {
                    fetch(s, base, total, cancelled, progress)
                    break
                } catch (e: CancellationException) { throw e
                } catch (e: ChecksumException) { throw e
                } catch (e: StorageException) { throw e
                } catch (e: IOException) {
                    attempt++
                    if (attempt >= 3 || cancelled()) throw e
                    Thread.sleep(2000L * attempt)            // transient network error: resume
                }
            }
            base += s.bytes
        }
    }

    private fun fetch(s: ModelSpec, base: Long, total: Long, cancelled: () -> Boolean, progress: (Progress) -> Unit) {
        val p = part(s)
        var lastErr: IOException? = null
        for (url in s.urls) {
            try {
                download(url, s, p, base, total, cancelled, progress)
                lastErr = null
                break
            } catch (e: CancellationException) { throw e
            } catch (e: IOException) { lastErr = e }
        }
        lastErr?.let { throw it }
        if (p.length() != s.bytes) throw IOException("${s.file}: size ${p.length()} != ${s.bytes}")
        progress(Progress(base + s.bytes, total, s.file, true, 0.0))
        val sha = sha256(p, cancelled)
        if (sha != s.sha256) {
            p.delete()
            throw ChecksumException("${s.file} failed the checksum check (got ${sha.take(12)}…). It was deleted — tap Retry to download it again.")
        }
        val f = file(s); f.delete()
        if (!p.renameTo(f)) throw IOException("couldn't rename ${p.name}")
        marker(s).writeText(s.sha256)
    }

    private fun download(url: String, s: ModelSpec, p: File, base: Long, total: Long,
                         cancelled: () -> Boolean, progress: (Progress) -> Unit) {
        var have = if (p.isFile) p.length() else 0L
        if (have > s.bytes) { p.delete(); have = 0L }
        if (have == s.bytes) return
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000; c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "CoupleFaceSwap/2.0 (Android)")
        c.setRequestProperty("Accept-Encoding", "identity")
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = c.responseCode
            val append = when {
                code == 206 && have > 0 -> true
                code == 200 -> { have = 0L; false }             // server ignored Range: start over
                code == 416 -> { p.delete(); throw IOException("HTTP 416 for ${s.file}; restarting") }
                else -> throw IOException("HTTP $code from ${URL(url).host}")
            }
            c.inputStream.use { inp ->
                FileOutputStream(p, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var got = have; val t0 = System.nanoTime(); val start = have; var lastReport = 0L
                    while (true) {
                        if (cancelled()) throw CancellationException("cancelled")
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); got += n
                        if (got > s.bytes) throw IOException("${s.file}: server sent more data than expected")
                        val now = System.nanoTime()
                        if (now - lastReport > 200_000_000L) {
                            lastReport = now
                            val secs = (now - t0) / 1e9
                            progress(Progress(base + got, total, s.file, false, if (secs > 0) (got - start) / secs else 0.0))
                        }
                    }
                }
            }
        } finally { c.disconnect() }
    }

    companion object {
        fun sha256(f: File, cancelled: () -> Boolean = { false }): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    if (cancelled()) throw CancellationException("cancelled")
                    val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
        fun mb(b: Long) = "%.0f MB".format(b / 1e6)
    }
}
