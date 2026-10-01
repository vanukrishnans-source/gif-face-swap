package com.vanu.giffaceswap

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vanu.faceswap.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException

data class GifUi(
    val gif: File? = null,
    val info: GifInfo? = null,
    val thumb: Bitmap? = null,
    val faces: Bitmap? = null,
    val enhance: EnhanceMode = EnhanceMode.OFF,
    val lightInstalled: Boolean = false,
    val hqInstalled: Boolean = false,
    val enhDownloading: EnhanceMode? = null,
    val enhDone: Long = 0L,
    val accelerator: Boolean = false,
    val loading: String? = null,
    val error: String? = null,
    val errorDetail: String? = null,
    val toast: String? = null,
    val rotation: Int = 0,
    val selectedFrames: Int = 0,
)

class GifViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val modelsDir = File(app.filesDir, "models")
    private val lightStore = ModelStore(modelsDir, listOf(Models.ENHANCER_LIGHT))
    private val hqStore = ModelStore(modelsDir, listOf(Models.ENHANCER))
    val models = ModelDownloadController(ModelStore(modelsDir, Models.VIDEO_REQUIRED), viewModelScope, Models.VIDEO_REQUIRED_BYTES)
    private val dir = File(app.cacheDir, "gif").apply { mkdirs() }
    private val _state = MutableStateFlow(
        GifUi(
            accelerator = prefs.getBoolean("xnnpack", false),
            enhance = runCatching { EnhanceMode.valueOf(prefs.getString("gif_enhance", "OFF")!!) }.getOrDefault(EnhanceMode.OFF),
        )
    )
    val state: StateFlow<GifUi> = _state.asStateFlow()
    val job: StateFlow<GifJobState> = GifJobs.state
    private var enhJob: Job? = null
    @Volatile private var enhPause = false

    init {
        models.check()
        viewModelScope.launch {
            val light = withContext(Dispatchers.IO) { lightStore.allInstalled() }
            val hq = withContext(Dispatchers.IO) { hqStore.allInstalled() }
            _state.update {
                it.copy(
                    lightInstalled = light, hqInstalled = hq,
                    enhance = when {
                        it.enhance == EnhanceMode.LIGHT && !light -> EnhanceMode.OFF
                        it.enhance == EnhanceMode.HQ && !hq -> EnhanceMode.OFF
                        else -> it.enhance
                    },
                )
            }
        }
    }

    fun pickGif(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(loading = "Loading GIF…", error = null, errorDetail = null) }
            try {
                val (file, info, thumb, selected) = withContext(Dispatchers.IO) {
                    dir.listFiles()?.filter { it.name.startsWith("input_") }?.forEach { it.delete() }
                    val f = File(dir, "input_${System.currentTimeMillis()}.gif")
                    val input = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?: throw IOException("The gallery returned no data for this GIF.")
                    try { input.use { i -> FileOutputStream(f).use { o -> i.copyTo(o, 1 shl 20) } } }
                    catch (e: IOException) { f.delete(); throw IOException("Couldn't copy the GIF (enough free storage?).", e) }
                    val (inf, frames) = GifDecoder.decode(f)
                    val indices = GifPlan.selectIndices(inf.delaysMs)
                    val t = frames.firstOrNull()?.bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    frames.forEach { it.bitmap.recycle() }
                    Quadruple(f, inf, t, indices.size)
                }
                if (GifJobs.state.value !is GifJobState.Running) GifJobs.reset()
                _state.update {
                    it.copy(gif = file, info = info, thumb = thumb, selectedFrames = selected, rotation = 0)
                }
            } catch (e: Throwable) {
                Log.e(ImageUtils.TAG, "gif load failed", e)
                _state.update {
                    it.copy(
                        error = (e as? GifException)?.message ?: "Couldn't open the selected GIF.",
                        errorDetail = ImageUtils.describe(e),
                    )
                }
            } finally { _state.update { it.copy(loading = null) } }
        }
    }

    fun pickFaces(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(loading = "Loading photo…", error = null, errorDetail = null) }
            try {
                val bmp = withContext(Dispatchers.IO) {
                    val b = ImageUtils.load(getApplication(), uri, "gfaces")
                    FileOutputStream(File(dir, "faces.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    b
                }
                if (GifJobs.state.value !is GifJobState.Running) GifJobs.reset()
                _state.update { it.copy(faces = bmp, rotation = 0) }
            } catch (e: Throwable) {
                Log.e(ImageUtils.TAG, "faces photo load failed", e)
                _state.update {
                    it.copy(
                        error = (e as? ImageLoadException)?.message ?: "Couldn't open the selected image.",
                        errorDetail = ImageUtils.describe(e),
                    )
                }
            } finally { _state.update { it.copy(loading = null) } }
        }
    }

    fun setEnhance(m: EnhanceMode) {
        prefs.edit().putString("gif_enhance", m.name).apply()
        _state.update { it.copy(enhance = m) }
    }

    fun setAccelerator(on: Boolean) {
        prefs.edit().putBoolean("xnnpack", on).apply()
        _state.update { it.copy(accelerator = on) }
    }

    fun downloadEnhancer(mode: EnhanceMode) {
        if (mode == EnhanceMode.OFF || enhJob?.isActive == true) return
        val store = if (mode == EnhanceMode.LIGHT) lightStore else hqStore
        enhPause = false
        _state.update { it.copy(enhDownloading = mode, enhDone = runCatching { store.bytesPresent() }.getOrDefault(0L), error = null, errorDetail = null) }
        enhJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.downloadAll({ enhPause }) { p -> _state.update { it.copy(enhDone = p.done) } } }
                prefs.edit().putString("gif_enhance", mode.name).apply()
                _state.update {
                    if (mode == EnhanceMode.LIGHT) it.copy(lightInstalled = true, enhance = mode)
                    else it.copy(hqInstalled = true, enhance = mode)
                }
            } catch (_: CancellationException) {
            } catch (e: Throwable) {
                Log.e(ImageUtils.TAG, "enhancer download failed", e)
                _state.update { it.copy(error = downloadErrorMessage(e) ?: "Download failed.", errorDetail = ImageUtils.describe(e)) }
            } finally { _state.update { it.copy(enhDownloading = null) } }
        }
    }

    fun pauseEnhancer() { enhPause = true }

    fun start() = run(0)
    fun flip() = run(_state.value.rotation + 1)

    private fun run(rotation: Int) {
        val s = _state.value
        val g = s.gif ?: return
        if (s.faces == null) return
        if (GifJobs.running) return
        if ((s.enhance == EnhanceMode.LIGHT && !s.lightInstalled) || (s.enhance == EnhanceMode.HQ && !s.hqInstalled)) {
            _state.update { it.copy(error = "Download that enhancer first, or pick another Enhance option.") }
            return
        }
        dir.listFiles()?.filter { it.name.startsWith("swap_") }?.forEach { it.delete() }
        val out = File(dir, "swap_${System.currentTimeMillis()}.gif")
        _state.update { it.copy(rotation = rotation, error = null, errorDetail = null) }
        GifJobService.start(
            getApplication(),
            GifJobParams(
                g.absolutePath, File(dir, "faces.png").absolutePath,
                rotation, s.enhance, s.accelerator, out.absolutePath,
            ),
        )
    }

    fun cancel() = GifJobService.cancel(getApplication())

    fun save(file: File) {
        viewModelScope.launch {
            val msg = try {
                "Saved to " + withContext(Dispatchers.IO) { GifFiles.saveToPictures(getApplication(), file) }
            } catch (e: Throwable) {
                Log.e(ImageUtils.TAG, "gif save failed", e)
                "Save failed: ${e.message ?: e.javaClass.simpleName}"
            }
            _state.update { it.copy(toast = msg) }
        }
    }

    fun showError(msg: String, detail: String? = null) = _state.update { it.copy(error = msg, errorDetail = detail) }
    fun toastShown() = _state.update { it.copy(toast = null) }
    fun dismissResult() = GifJobs.reset()

    override fun onCleared() { enhPause = true; models.pause() }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

object GifFiles {
    fun saveToPictures(context: Context, src: File): String {
        // Never publish a MediaStore placeholder for a bad/empty encode.
        GifEncoder.verifyGifFile(src)
        val name = "GifFaceSwap_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".gif"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GifFaceSwap")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val cr = context.contentResolver
            val uri = cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IOException("Couldn't create the GIF in the gallery.")
            try {
                var written = 0L
                (cr.openOutputStream(uri) ?: throw IOException("Couldn't write the GIF.")).use { o ->
                    src.inputStream().use { input ->
                        val buf = ByteArray(1 shl 20)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            o.write(buf, 0, n)
                            written += n
                        }
                        o.flush()
                    }
                }
                if (written < 32 || written != src.length()) {
                    throw IOException("GIF write incomplete (${written}/${src.length()} bytes).")
                }
                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
                cr.update(uri, values, null, null)
            } catch (e: Exception) {
                runCatching { cr.delete(uri, null, null) }
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "GifFaceSwap")
            if (!d.exists() && !d.mkdirs()) throw IOException("Couldn't create Pictures/GifFaceSwap.")
            // Write via temp then rename so a crash never leaves a half-written .gif in Pictures.
            val tmp = File(d, "$name.part")
            val f = File(d, name)
            try {
                src.inputStream().use { input -> FileOutputStream(tmp).use { output -> input.copyTo(output, 1 shl 20); output.flush() } }
                GifEncoder.verifyGifFile(tmp)
                if (f.exists()) f.delete()
                if (!tmp.renameTo(f)) {
                    tmp.copyTo(f, overwrite = true)
                    tmp.delete()
                }
            } catch (e: Exception) {
                tmp.delete(); f.delete(); throw e
            }
            MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), arrayOf("image/gif"), null)
        }
        return "Pictures/GifFaceSwap/$name"
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/gif"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share face swap GIF")
    }
}
