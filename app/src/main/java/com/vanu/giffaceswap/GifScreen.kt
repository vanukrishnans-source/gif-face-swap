package com.vanu.giffaceswap

import android.graphics.Bitmap

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vanu.faceswap.core.ErrorCard
import com.vanu.faceswap.core.Models
import com.vanu.faceswap.core.PhotoSlot
import com.vanu.faceswap.core.ImageUtils
import com.vanu.faceswap.core.mb
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GifContent(vm: GifViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val running = job is GifJobState.Running

    val pickGif = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.pickGif(it) }
    }
    val pickFaces = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { vm.pickFaces(it) }
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.start() }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val d = job as? GifJobState.Done
        if (granted && d != null) vm.save(d.result.file)
        else if (!granted) vm.showError("Storage permission is needed to save on this Android version.")
    }

    fun start() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start()
    }

    LaunchedEffect(s.toast) {
        s.toast?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.toastShown() }
    }

    Text(
        "Pick an animated GIF and a face photo. The face is swapped onto each GIF frame. " +
            "Faces are matched left to right when both have more than one face.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(modifier = Modifier.weight(1f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("GIF", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("Animated target", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.fillMaxWidth().aspectRatio(0.8f).clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = !running) { pickGif.launch("image/gif") },
                    contentAlignment = Alignment.Center,
                ) {
                    val t = s.thumb
                    if (t != null) {
                        val b = remember(t) { t.asImageBitmap() }
                        Image(b, contentDescription = "GIF", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Tap to pick", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = { pickGif.launch("image/gif") }, enabled = !running) { Text("Gallery") }
            }
        }
        PhotoSlot(
            title = "Face from", subtitle = "Face to put in", image = s.faces, enabled = !running,
            modifier = Modifier.weight(1f),
            onGallery = { pickFaces.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        )
    }
    s.loading?.let {
        Column(Modifier.fillMaxWidth()) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }

    val info = s.info
    if (info != null) {
        val (pw, ph, _) = GifPlan.outSize(info.width, info.height)
        Card(modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    "${info.frameCount} frames · ${info.width}×${info.height} · ${"%.1f".format(info.durationMs / 1000.0)} s" +
                        (if (pw != info.width || ph != info.height) "  →  processed at ${pw}×$ph" else "") +
                        (if (s.selectedFrames != info.frameCount) " · using ${s.selectedFrames} frames" else ""),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Limits: max ${GifPlan.MAX_FRAMES} frames, ${GifPlan.MAX_DURATION_MS / 1000} s, short side ≤ ${GifPlan.MAX_SHORT_SIDE} px, file ≤ ${GifPlan.MAX_FILE_BYTES / (1024 * 1024)} MB.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text("Enhance detail", style = MaterialTheme.typography.bodyLarge)
                val busyDl = s.enhDownloading != null
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = s.enhance == EnhanceMode.OFF, onClick = { vm.setEnhance(EnhanceMode.OFF) }, label = { Text("Off") }, enabled = !running)
                    FilterChip(
                        selected = s.enhance == EnhanceMode.LIGHT,
                        onClick = { if (s.lightInstalled) vm.setEnhance(EnhanceMode.LIGHT) else vm.downloadEnhancer(EnhanceMode.LIGHT) },
                        label = { Text(if (s.lightInstalled) "Light" else "Light ↓") }, enabled = !running && !busyDl,
                    )
                    FilterChip(
                        selected = s.enhance == EnhanceMode.HQ,
                        onClick = { if (s.hqInstalled) vm.setEnhance(EnhanceMode.HQ) else vm.downloadEnhancer(EnhanceMode.HQ) },
                        label = { Text(if (s.hqInstalled) "HQ" else "HQ ↓") }, enabled = !running && !busyDl,
                    )
                }
                Text(
                    when (s.enhance) {
                        EnhanceMode.OFF -> "Fastest. Faces are a little soft (swap model works at 128 px)."
                        EnhanceMode.LIGHT -> "Sharper faces for ~10–30% more time (GPEN 256). Recommended."
                        EnhanceMode.HQ -> "Sharpest (GPEN 512) but much slower — best for short GIFs."
                    },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val dl = s.enhDownloading
                if (dl != null) {
                    val spec = if (dl == EnhanceMode.LIGHT) Models.ENHANCER_LIGHT else Models.ENHANCER
                    LinearProgressIndicator(progress = { (s.enhDone.toFloat() / spec.bytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Downloading ${if (dl == EnhanceMode.LIGHT) "Light" else "HQ"}: ${s.enhDone / 1_000_000} of ${mb(spec.bytes)}",
                            style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.pauseEnhancer() }) { Text("Pause") }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Experimental accelerator", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Try XNNPACK instead of the standard CPU engine. Falls back automatically if unsupported.",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = s.accelerator, onCheckedChange = { vm.setAccelerator(it) }, enabled = !running)
                }
            }
        }
    }

    Button(
        onClick = { start() },
        enabled = !running && s.gif != null && s.faces != null && s.loading == null,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Icon(Icons.Filled.Face, contentDescription = null); Spacer(Modifier.size(8.dp))
        Text("Create face swap GIF", style = MaterialTheme.typography.titleMedium)
    }

    when (val j = job) {
        is GifJobState.Running -> Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(j.stage + "…", style = MaterialTheme.typography.titleSmall)
                if (j.total > 0) LinearProgressIndicator(progress = { j.done.toFloat() / j.total }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                if (j.detail.isNotEmpty()) Text(j.detail, style = MaterialTheme.typography.bodySmall)
                Text(
                    "You can leave the app — progress is shown in the notification.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { vm.cancel() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
        is GifJobState.Failed -> ErrorCard(j.message, j.detail)
        is GifJobState.Cancelled -> Text("Cancelled.", style = MaterialTheme.typography.bodySmall)
        is GifJobState.Done -> {
            val r = j.result
            Text("Result", style = MaterialTheme.typography.titleMedium)
            ResultPreview(r.file)
            Text(
                buildString {
                    append("${r.frames} frames, ${r.width}×${r.height}. ")
                    append("Swapped ${r.swappedFaces} face${if (r.swappedFaces == 1) "" else "s"}")
                    if (j.params.rotation % 2 != 0 && r.sourceFaces >= 2) append(" (flipped)")
                    append(". ")
                    if (r.skippedNoFace > 0) append("${r.skippedNoFace} frame(s) had no face and were left as-is. ")
                    append("Done in ${EtaEstimator.format(r.seconds)}.")
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { vm.flip() }, enabled = r.canFlip && !running, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp)); Text("Flip")
                }
                FilledTonalButton(
                    onClick = {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                        ) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        else vm.save(r.file)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp)); Text("Save")
                }
                FilledTonalButton(
                    onClick = {
                        try { ctx.startActivity(GifFiles.shareIntent(ctx, r.file)) }
                        catch (e: Exception) {
                            android.util.Log.e(ImageUtils.TAG, "share failed", e)
                            vm.showError("Share failed.", ImageUtils.describe(e))
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp)); Text("Share")
                }
            }
            if (r.canFlip) Text(
                "Faces on the wrong people? Tap Flip to re-run with pairing swapped.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GifJobState.Idle -> {}
    }
    s.error?.let { ErrorCard(it, s.errorDetail) }
}

@Composable
private fun ResultPreview(file: File) {
    // Show first frame as a static preview (full GIF playback would need an extra library)
    val bmp = remember(file.absolutePath, file.length()) {
        runCatching {
            val (_, frames) = GifDecoder.decode(file)
            val first = frames.firstOrNull()?.bitmap?.copy(Bitmap.Config.ARGB_8888, false)
            frames.forEach { it.bitmap.recycle() }
            first
        }.getOrNull()
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
            .background(androidx.compose.ui.graphics.Color.DarkGray),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = "Result GIF", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Text(
                "GIF ready — Save or Share to view animation",
                style = MaterialTheme.typography.labelMedium,
                color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        } else Text("Preview unavailable", color = androidx.compose.ui.graphics.Color.White)
    }
}
