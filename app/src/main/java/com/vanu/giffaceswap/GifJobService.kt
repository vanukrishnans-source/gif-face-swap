package com.vanu.giffaceswap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vanu.faceswap.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.CancellationException

class EnhancerMissingException(val which: String) : Exception("$which enhancer missing")

sealed class GifJobState {
    data object Idle : GifJobState()
    data class Running(val stage: String, val done: Int, val total: Int, val etaSeconds: Double?, val detail: String) : GifJobState()
    data class Done(val result: GifJobResult, val params: GifJobParams) : GifJobState()
    data class Failed(val message: String, val detail: String?, val offerRedownload: Boolean = false) : GifJobState()
    data object Cancelled : GifJobState()
}

object GifJobs {
    private val _state = MutableStateFlow<GifJobState>(GifJobState.Idle)
    val state: StateFlow<GifJobState> = _state.asStateFlow()
    @Volatile var cancelRequested = false
    internal fun set(s: GifJobState) { _state.value = s }
    fun reset() { if (_state.value !is GifJobState.Running) _state.value = GifJobState.Idle }
    val running get() = _state.value is GifJobState.Running
}

class GifJobService : Service() {
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> { GifJobs.cancelRequested = true; return START_NOT_STICKY }
            ACTION_START -> {
                val p = intent.toParams()
                if (p == null || worker?.isAlive == true) return START_NOT_STICKY
                createChannel(this)
                startInForeground(progressNotification("Preparing…", 0, 0, null))
                GifJobs.cancelRequested = false
                GifJobs.set(GifJobState.Running("Preparing…", 0, 0, null, ""))
                wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GifFaceSwap:job").apply { acquire(2 * 60 * 60 * 1000L) }
                worker = Thread({ runJob(p) }, "gif-job").apply { start() }
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(n: Notification) {
        when {
            Build.VERSION.SDK_INT >= 35 -> startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= 29 -> startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> startForeground(NOTIF_ID, n)
        }
    }

    private fun runJob(p: GifJobParams) {
        val final: GifJobState = try {
            val dir = File(filesDir, "models")
            val store = ModelStore(dir, Models.VIDEO_REQUIRED)
            if (!store.allInstalled()) throw ModelMissingException()
            val light = ModelStore(dir, listOf(Models.ENHANCER_LIGHT))
            if (p.enhance == EnhanceMode.LIGHT && !light.allInstalled()) throw EnhancerMissingException("Light")
            if (p.enhance == EnhanceMode.HQ && !ModelStore(dir, listOf(Models.ENHANCER)).allInstalled()) throw EnhancerMissingException("HQ")
            val srcBmp = ImageUtils.loadFile(File(p.facesPath))
            val det = FaceDetector(this)
            val srcFaces = try { det.detect(srcBmp) } finally { det.close() }
            if (srcFaces.isEmpty()) throw NoFaceException(
                "No face found in the face photo. Try a clearer photo where the face is not too small, covered or turned away.")
            val src = FaceDetector.toRgb(srcBmp); srcBmp.recycle()
            val proc = GifProcessor(this, store.files(), light.file(Models.ENHANCER_LIGHT))
            val res = proc.run(p, src, srcFaces, { stage, done, total, eta ->
                val label = when (stage) {
                    0 -> "Finding faces"
                    1 -> "Swapping faces"
                    else -> "Encoding GIF"
                }
                val detail = if (total > 0) "Frame $done of $total" + (eta?.let { " · about ${EtaEstimator.format(it)} left" } ?: "")
                             else ""
                GifJobs.set(GifJobState.Running(label, done, total, eta, detail))
                val now = System.currentTimeMillis()
                if (now - lastNotify > 1000 || done == total) { lastNotify = now; notifyProgress(label, done, total, eta) }
            }) { GifJobs.cancelRequested }
            GifJobState.Done(res, p)
        } catch (e: CancellationException) {
            GifJobState.Cancelled
        } catch (e: Throwable) {
            Log.e(ImageUtils.TAG, "gif job failed", e)
            errorState(e)
        }
        GifJobs.set(final)
        finish(final)
    }

    private fun errorState(e: Throwable): GifJobState.Failed {
        val msg = when (e) {
            is ModelMissingException -> "AI models are missing. Download them from the setup screen."
            is EnhancerMissingException -> "Download the ${e.which} enhancer first, or pick Enhance: Off."
            is NoFaceException -> e.message ?: "No face found."
            is GifException -> e.message ?: "Couldn't process that GIF."
            is StorageException -> e.message ?: "Not enough free storage."
            is ChecksumException -> e.message ?: "Model checksum failed."
            else -> "Something went wrong while swapping the GIF."
        }
        return GifJobState.Failed(msg, ImageUtils.describe(e), offerRedownload = e is ModelMissingException || e is ChecksumException)
    }

    private fun finish(final: GifJobState) {
        try {
            val nm = NotificationManagerCompat.from(this)
            when (final) {
                is GifJobState.Done -> {
                    val n = NotificationCompat.Builder(this, CHANNEL)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle("GIF face swap ready")
                        .setContentText("${final.result.frames} frames · ${EtaEstimator.format(final.result.seconds)}")
                        .setContentIntent(openApp()).setAutoCancel(true).build()
                    if (canNotify()) nm.notify(NOTIF_ID + 1, n)
                }
                is GifJobState.Failed -> {
                    val n = NotificationCompat.Builder(this, CHANNEL)
                        .setSmallIcon(android.R.drawable.stat_notify_error)
                        .setContentTitle("GIF face swap failed")
                        .setContentText(final.message)
                        .setContentIntent(openApp()).setAutoCancel(true).build()
                    if (canNotify()) nm.notify(NOTIF_ID + 1, n)
                }
                else -> {}
            }
        } catch (_: Exception) {}
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        worker = null
    }

    private fun notifyProgress(stage: String, done: Int, total: Int, eta: Double?) {
        if (!canNotify()) return
        NotificationManagerCompat.from(this).notify(NOTIF_ID, progressNotification(stage, done, total, eta))
    }

    private fun progressNotification(stage: String, done: Int, total: Int, eta: Double?): Notification {
        val text = if (total > 0) "$stage · $done/$total" + (eta?.let { " · ${EtaEstimator.format(it)} left" } ?: "")
                   else stage
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("GIF Face Swap")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(0, "Cancel", PendingIntent.getService(this, 1,
                Intent(this, GifJobService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        if (total > 0) b.setProgress(total, done, false) else b.setProgress(0, 0, true)
        return b.build()
    }

    private fun openApp() = PendingIntent.getActivity(this, 0,
        packageManager.getLaunchIntentForPackage(packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val CHANNEL = "gif_jobs"
        private const val NOTIF_ID = 42
        const val ACTION_START = "com.vanu.giffaceswap.START"
        const val ACTION_CANCEL = "com.vanu.giffaceswap.CANCEL"

        fun createChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val ch = NotificationChannel(CHANNEL, "GIF face swap", NotificationManager.IMPORTANCE_LOW)
                (ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
            }
        }

        fun start(ctx: Context, p: GifJobParams) {
            val i = Intent(ctx, GifJobService::class.java).setAction(ACTION_START).apply {
                putExtra("gif", p.gifPath); putExtra("faces", p.facesPath)
                putExtra("rotation", p.rotation); putExtra("enhance", p.enhance.name)
                putExtra("accelerator", p.accelerator); putExtra("output", p.outputPath)
            }
            ContextCompat.startForegroundService(ctx, i)
        }

        fun cancel(ctx: Context) {
            ctx.startService(Intent(ctx, GifJobService::class.java).setAction(ACTION_CANCEL))
        }
    }
}

private fun Intent.toParams(): GifJobParams? {
    val gif = getStringExtra("gif") ?: return null
    val faces = getStringExtra("faces") ?: return null
    val out = getStringExtra("output") ?: return null
    val enhance = runCatching { EnhanceMode.valueOf(getStringExtra("enhance") ?: "OFF") }.getOrDefault(EnhanceMode.OFF)
    return GifJobParams(gif, faces, getIntExtra("rotation", 0), enhance, getBooleanExtra("accelerator", false), out)
}
