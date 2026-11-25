package com.frameforge.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Experimental image-to-image midpoint interpolation using coarse block matching.
 * This is a classical CPU motion-estimation prototype, not a neural network and
 * not dense optical flow. It operates only on user-selected images.
 */
@Composable
fun OpticalFlowLab(arabic: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var first by remember { mutableStateOf<Bitmap?>(null) }
    var second by remember { mutableStateOf<Bitmap?>(null) }
    var output by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf(if (arabic) "اختر صورتين للمقارنة" else "Choose two images") }
    var busy by remember { mutableStateOf(false) }
    var phase by remember { mutableFloatStateOf(0.5f) }

    fun loadBitmap(uri: android.net.Uri, onLoaded: (Bitmap?) -> Unit) {
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    var sample = 1
                    while (max(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
                    val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                }.getOrNull()
            }
            onLoaded(bitmap)
        }
    }
    val pickA = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadBitmap(uri) { first = it; output = null; status = if (it != null) (if (arabic) "تم تحميل الصورة الأولى" else "First image loaded") else "Decode failed" }
    }
    val pickB = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadBitmap(uri) { second = it; output = null; status = if (it != null) (if (arabic) "تم تحميل الصورة الثانية" else "Second image loaded") else "Decode failed" }
    }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(if (arabic) "مختبر تقدير الحركة بين الصور" else "IMAGE MOTION ESTIMATION LAB", color = Color.White, fontSize = 15.sp)
        Text(
            if (arabic) "اختر صورتين متقاربتين زمنياً للمشهد نفسه. يحسب المختبر إزاحة تقريبية لكل كتلة باستخدام Block Matching ثم ينشئ صورة وسطية. هذه خوارزمية تقليدية تجريبية وليست نموذج AI أو Optical Flow كثيفاً." else "Choose two nearby frames of the same scene. Coarse block matching estimates per-block displacement and synthesizes a midpoint. This is a classical experimental method, not an AI model or dense optical flow.",
            color = Color(0xFF9AAAC0), fontSize = 11.sp, lineHeight = 16.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickA.launch("image/*") }, modifier = Modifier.weight(1f)) { Text(if (arabic) "اختيار A" else "Choose A") }
            OutlinedButton(onClick = { pickB.launch("image/*") }, modifier = Modifier.weight(1f)) { Text(if (arabic) "اختيار B" else "Choose B") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BitmapPreview(first, if (arabic) "الصورة A" else "Frame A", Modifier.weight(1f))
            BitmapPreview(second, if (arabic) "الصورة B" else "Frame B", Modifier.weight(1f))
        }
        Text(if (arabic) "موضع الإطار الوسيط: ${(phase * 100).toInt()}%" else "Interpolation phase: ${(phase * 100).toInt()}%", color = Color(0xFF35B9FF), fontSize = 12.sp)
        Slider(value = phase, onValueChange = { phase = it; output = null }, valueRange = 0.1f..0.9f)
        Button(
            onClick = {
                val a = first; val b = second
                if (a == null || b == null) {
                    status = if (arabic) "اختر الصورتين أولاً" else "Select both images first"
                } else {
                    busy = true
                    status = if (arabic) "جارٍ تقدير الحركة..." else "Estimating motion..."
                    scope.launch {
                        val result = withContext(Dispatchers.Default) { runCatching { BlockMatchInterpolator.interpolate(a, b, phase) }.getOrNull() }
                        output = result
                        busy = false
                        status = if (result != null) (if (arabic) "اكتمل إنشاء الإطار الوسيط" else "Intermediate frame generated") else (if (arabic) "تعذرت معالجة الصور" else "Image processing failed")
                    }
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = !busy
        ) { Text(if (busy) (if (arabic) "جارٍ المعالجة..." else "Processing...") else (if (arabic) "إنشاء الإطار الوسيط" else "Generate intermediate frame")) }
        Text(status, color = Color(0xFF35B9FF), fontSize = 11.sp)
        output?.let { result ->
            Text(if (arabic) "الإطار الناتج" else "SYNTHESIZED FRAME", color = Color.White, fontSize = 13.sp)
            Image(result.asImageBitmap(), contentDescription = "Interpolated frame", modifier = Modifier.fillMaxWidth().heightIn(max = 230.dp))
        }
        Text(if (arabic) "حدود الاختبار: يعمل على صور تختارها بنفسك، بدقة معالجة منخفضة لتناسب الهواتف. قد تظهر تشوهات عند الانسداد أو الحركة السريعة أو اختلاف المشهد." else "Limitations: processes only user-selected still images at reduced resolution for mobile performance. Occlusion, fast motion and scene changes can cause artifacts.", color = Color(0xFF9AAAC0), fontSize = 10.sp, lineHeight = 14.sp)
    }
}

@Composable
private fun BitmapPreview(bitmap: Bitmap?, label: String, modifier: Modifier = Modifier) {
    Column(modifier.background(Color(0xFF121C2B), RoundedCornerShape(12.dp)).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color(0xFF9AAAC0), fontSize = 10.sp)
        if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = label, modifier = Modifier.fillMaxWidth().height(90.dp))
        else Box(Modifier.fillMaxWidth().height(90.dp), contentAlignment = Alignment.Center) { Text("—", color = Color.Gray) }
    }
}

private object BlockMatchInterpolator {
    fun interpolate(inputA: Bitmap, inputB: Bitmap, phase: Float): Bitmap {
        val longSide = 160
        val ratio = inputA.width.toFloat() / max(1, inputA.height)
        val width = if (ratio >= 1f) longSide else max(64, (longSide * ratio).toInt())
        val height = if (ratio >= 1f) max(64, (longSide / ratio).toInt()) else longSide
        val a = Bitmap.createScaledBitmap(inputA, width, height, true)
        val b = Bitmap.createScaledBitmap(inputB, width, height, true)
        val pa = IntArray(width * height); val pb = IntArray(width * height)
        a.getPixels(pa, 0, width, 0, 0, width, height)
        b.getPixels(pb, 0, width, 0, 0, width, height)
        val block = 8
        val radius = 6
        val cols = (width + block - 1) / block
        val rows = (height + block - 1) / block
        val dxField = IntArray(cols * rows)
        val dyField = IntArray(cols * rows)
        for (by in 0 until rows) for (bx in 0 until cols) {
            val x0 = bx * block; val y0 = by * block
            var best = Long.MAX_VALUE; var bestX = 0; var bestY = 0
            for (dy in -radius..radius) for (dx in -radius..radius) {
                var sad = 0L; var samples = 0
                for (oy in 0 until block step 2) for (ox in 0 until block step 2) {
                    val x = x0 + ox; val y = y0 + oy
                    val xx = x + dx; val yy = y + dy
                    if (x < width && y < height && xx in 0 until width && yy in 0 until height) {
                        sad += abs(luma(pa[y * width + x]) - luma(pb[yy * width + xx])).toLong()
                        samples++
                    }
                }
                if (samples > 0 && sad < best) { best = sad; bestX = dx; bestY = dy }
            }
            dxField[by * cols + bx] = bestX
            dyField[by * cols + bx] = bestY
        }
        val out = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val bx = min(cols - 1, x / block); val by = min(rows - 1, y / block)
            val dx = dxField[by * cols + bx].toFloat()
            val dy = dyField[by * cols + bx].toFloat()
            val ax = (x - dx * phase).toInt().coerceIn(0, width - 1)
            val ay = (y - dy * phase).toInt().coerceIn(0, height - 1)
            val bx2 = (x + dx * (1f - phase)).toInt().coerceIn(0, width - 1)
            val by2 = (y + dy * (1f - phase)).toInt().coerceIn(0, height - 1)
            out[y * width + x] = mix(pa[ay * width + ax], pb[by2 * width + bx2])
        }
        a.recycle(); b.recycle()
        val small = Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
        val scale = min(1f, 1280f / max(inputA.width, inputA.height).toFloat())
        val outWidth = max(1, (inputA.width * scale).toInt())
        val outHeight = max(1, (inputA.height * scale).toInt())
        val result = Bitmap.createScaledBitmap(small, outWidth, outHeight, true)
        small.recycle()
        return result
    }
    private fun luma(c: Int): Int = (((c shr 16 and 255) * 77 + (c shr 8 and 255) * 150 + (c and 255) * 29) shr 8)
    private fun mix(a: Int, b: Int): Int {
        fun ch(shift: Int) = (((a shr shift and 255) + (b shr shift and 255)) / 2)
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
