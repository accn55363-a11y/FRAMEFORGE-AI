package com.frameforge.ai

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.ceil

private val Bg = Color(0xFF080D16)
private val Panel = Color(0xFF121C2B)
private val Accent = Color(0xFF35B9FF)
private val Muted = Color(0xFF9AAAC0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FrameForgeApp(this) }
    }
}

data class DeviceReport(
    val model: String, val android: String, val soc: String, val cores: Int,
    val ramTotal: String, val ramAvailable: String, val refresh: String,
    val thermal: String, val vulkan: String
)

private fun gb(v: Long) = String.format(Locale.US, "%.2f GB", v / 1024.0 / 1024.0 / 1024.0)

private fun collectReport(context: Context): DeviceReport {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mem = ActivityManager.MemoryInfo()
    am.getMemoryInfo(mem)
    val hz = if (Build.VERSION.SDK_INT >= 30) {
        context.display?.refreshRate ?: 0f
    } else {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.refreshRate
    }
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val thermal = if (Build.VERSION.SDK_INT >= 29) {
        when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "Normal"
            PowerManager.THERMAL_STATUS_LIGHT -> "Light"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
            else -> "Unknown"
        }
    } else "Unavailable"
    val soc = if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL.isNotBlank()) Build.SOC_MODEL else "Not exposed by Android"
    val vulkan = if (Build.VERSION.SDK_INT >= 24 && context.packageManager.hasSystemFeature("android.hardware.vulkan.level", 1)) "Supported (hardware level 1+)" else if (context.packageManager.hasSystemFeature("android.hardware.vulkan.version")) "Supported (version feature present)" else "Not reported"
    return DeviceReport(
        "${Build.MANUFACTURER} ${Build.MODEL}",
        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        soc, Runtime.getRuntime().availableProcessors(),
        gb(mem.totalMem), gb(mem.availMem),
        if (hz > 0f) String.format(Locale.US, "%.0f Hz", hz) else "Unavailable",
        thermal, vulkan
    )
}

class GpuBenchmarkView(context: Context) : GLSurfaceView(context) {
    private val gpuRenderer: BenchmarkRenderer
    init {
        setEGLContextClientVersion(2)
        gpuRenderer = BenchmarkRenderer { vendor, renderer, version, fps, frameMs, done ->
            post {
                onGpuInfo?.invoke(vendor, renderer, version)
                if (fps >= 0) onBenchmarkUpdate?.invoke(fps, frameMs, done)
            }
        }
        setRenderer(gpuRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        preserveEGLContextOnPause = true
    }
    var onGpuInfo: ((String, String, String) -> Unit)? = null
    var onBenchmarkUpdate: ((Double, Double, Boolean) -> Unit)? = null
    fun startBenchmark() { queueEvent { gpuRenderer.beginBenchmark() } }
}

private class BenchmarkRenderer(
    private val report: (String, String, String, Double, Double, Boolean) -> Unit
) : GLSurfaceView.Renderer {
    private var program = 0
    private var position = 0
    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f)); position(0) }
    @Volatile private var benchmarkEnd = 0L
    private var started = 0L
    private var lastReport = 0L
    private var frames = 0
    private var totalFrames = 0
    private val frameTimesNs = ArrayList<Long>()
    private var previousFrameNs = 0L
    private var totalStart = 0L
    private var lastVendor = ""
    private var lastRenderer = ""
    private var lastVersion = ""

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.02f, 0.04f, 0.08f, 1f)
        lastVendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: "Unknown"
        lastRenderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "Unknown"
        lastVersion = GLES20.glGetString(GLES20.GL_VERSION) ?: "Unknown"
        val vs = """
            attribute vec2 aPosition;
            varying vec2 uv;
            void main(){ uv=(aPosition+1.0)*0.5; gl_Position=vec4(aPosition,0.0,1.0); }
        """.trimIndent()
        val fs = """
            precision mediump float;
            varying vec2 uv;
            uniform float uTime;
            void main(){
              vec2 p=(uv-0.5)*2.0;
              float v=0.0;
              for(int i=0;i<18;i++){
                float fi=float(i)+1.0;
                p=vec2(p.x*p.x-p.y*p.y,2.0*p.x*p.y)+vec2(0.013*sin(uTime+fi),0.01*cos(uTime-fi));
                v += sin((p.x+p.y)*fi+uTime)/(fi*1.8);
              }
              vec3 c=vec3(0.04+abs(v)*0.10,0.18+abs(sin(v))*0.22,0.30+abs(cos(v))*0.42);
              gl_FragColor=vec4(c,1.0);
            }
        """.trimIndent()
        val vertex = compile(GLES20.GL_VERTEX_SHADER, vs)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val error = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            program = 0
            report(lastVendor, lastRenderer, "Shader link failed: $error", -1.0, -1.0, false)
            return
        }
        position = GLES20.glGetAttribLocation(program, "aPosition")
        report(lastVendor, lastRenderer, lastVersion, -1.0, -1.0, false)
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val error = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("OpenGL shader compilation failed: $error")
        }
        return shader
    }

    fun beginBenchmark() {
        started = System.nanoTime()
        totalStart = started
        lastReport = started
        benchmarkEnd = started + 10_000_000_000L
        frames = 0
        totalFrames = 0
        frameTimesNs.clear()
        previousFrameNs = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (program != 0) {
            GLES20.glUseProgram(program)
            val timeLoc = GLES20.glGetUniformLocation(program, "uTime")
            GLES20.glUniform1f(timeLoc, now / 1_000_000_000f)
            vertices.position(0)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(position)
        }
        if (benchmarkEnd > 0L && now < benchmarkEnd) {
            if (previousFrameNs > 0L) frameTimesNs.add(now - previousFrameNs)
            previousFrameNs = now
            frames++
            totalFrames++
            if (now - lastReport >= 1_000_000_000L) {
                val elapsed = (now - lastReport) / 1_000_000_000.0
                val fps = frames / elapsed
                report(lastVendor, lastRenderer, lastVersion, fps, 1000.0 / max(0.1, fps), false)
                frames = 0
                lastReport = now
            }
        } else if (benchmarkEnd > 0L) {
            val elapsed = (now - totalStart) / 1_000_000_000.0
            val fps = totalFrames / max(0.1, elapsed)
            // 1% low: average FPS represented by the slowest 1% of measured frame intervals.
            val sortedSlowestFirst = frameTimesNs.sortedDescending()
            val slowCount = if (sortedSlowestFirst.isEmpty()) 0 else max(1, ceil(sortedSlowestFirst.size * 0.01).toInt())
            val slowAverageMs = if (slowCount > 0) {
                sortedSlowestFirst.take(slowCount).average() / 1_000_000.0
            } else 1000.0 / max(0.1, fps)
            val onePercentLow = if (slowAverageMs > 0.0) 1000.0 / slowAverageMs else 0.0
            report(lastVendor, lastRenderer, "$lastVersion | Avg ${String.format(Locale.US, "%.1f", fps)} FPS | 1% low ~${String.format(Locale.US, "%.1f", onePercentLow)}", fps, 1000.0 / max(0.1, fps), true)
            benchmarkEnd = 0L
        }
    }
}

@Composable
fun FrameForgeApp(context: Context) {
    var page by remember { mutableStateOf("home") }
    var english by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf(collectReport(context)) }
    val prefs = remember { context.getSharedPreferences("frameforge_profiles", Context.MODE_PRIVATE) }
    var games by remember { mutableStateOf(prefs.getStringSet("games", setOf("PUBG Mobile", "Call of Duty Mobile", "Free Fire"))!!.toList()) }
    var selectedGame by remember { mutableStateOf(games.firstOrNull() ?: "PUBG Mobile") }
    var gameName by remember { mutableStateOf("") }
    var targetFps by remember { mutableIntStateOf(60) }
    var aspect by remember { mutableStateOf("Auto") }
    var blackSky by remember { mutableStateOf(false) }
    var gpuVendor by remember { mutableStateOf("Scanning…") }
    var gpuRenderer by remember { mutableStateOf("Run GPU test to identify renderer") }
    var glVersion by remember { mutableStateOf("Not read yet") }
    var benchmarkFps by remember { mutableStateOf("--") }
    var frameMs by remember { mutableStateOf("--") }
    var benchmarkStatus by remember { mutableStateOf("Not started") }
    var taps by remember { mutableIntStateOf(0) }
    var lastTap by remember { mutableLongStateOf(0L) }
    var tapInterval by remember { mutableStateOf("--") }
    var gpuView by remember { mutableStateOf<GpuBenchmarkView?>(null) }
    var interpolationView by remember { mutableStateOf<FrameInterpolationView?>(null) }
    var interpolationPhase by remember { mutableFloatStateOf(0.5f) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, gpuView, interpolationView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { gpuView?.onResume(); interpolationView?.onResume() }
                Lifecycle.Event.ON_PAUSE -> { gpuView?.onPause(); interpolationView?.onPause() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); gpuView?.onPause(); interpolationView?.onPause() }
    }
    val ar = !english
    fun saveProfile() {
        prefs.edit()
            .putInt("$selectedGame.fps", targetFps)
            .putString("$selectedGame.aspect", aspect)
            .putBoolean("$selectedGame.blackSky", blackSky)
            .putStringSet("games", games.toSet())
            .apply()
    }
    MaterialTheme(colorScheme = darkColorScheme(
        background = Bg, surface = Panel, primary = Accent,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Scaffold(containerColor = Bg, bottomBar = {
            NavigationBar(containerColor = Panel) {
                listOf(
                    Triple("home", if (ar) "الرئيسية" else "Home", Icons.Default.Home),
                    Triple("device", if (ar) "الجهاز" else "Device", Icons.Default.Memory),
                    Triple("games", if (ar) "الألعاب" else "Games", Icons.Default.SportsEsports),
                    Triple("lab", if (ar) "المختبر" else "Lab", Icons.Default.Science)
                ).forEach { (id, label, icon) ->
                    NavigationBarItem(
                        selected = page == id, onClick = { page = id },
                        icon = { Icon(icon, null) }, label = { Text(label, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Accent, selectedTextColor = Accent,
                            indicatorColor = Color(0xFF20364A), unselectedIconColor = Muted,
                            unselectedTextColor = Muted
                        )
                    )
                }
            }
        }) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp),
                contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("FRAMEFORGE", color = Color.White, fontWeight = FontWeight.Black, fontSize = 23.sp)
                            Text("AI • MOBILE EDITION", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        TextButton(onClick = { english = !english }) { Text(if (ar) "English" else "العربية", color = Accent) }
                    }
                }
                when (page) {
                    "home" -> {
                        item {
                            Column(Modifier.fillMaxWidth().background(
                                androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF173B58), Color(0xFF172238), Panel)),
                                RoundedCornerShape(22.dp)).padding(18.dp)) {
                                Text(if (ar) "إصدار التطوير 0.5.0" else "DEVELOPER BUILD 0.5.0", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                Text(if (ar) "أدوات أداء حقيقية وقياسات شفافة" else "Real diagnostics. Transparent measurements.", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                Text(if (ar) "قياس GPU داخل مشهد اختباري، فحص الجهاز، وملفات ألعاب محفوظة محلياً." else "GPU workload benchmark, device diagnostics and local game profiles.", color = Muted, fontSize = 12.sp)
                            }
                        }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MetricCard(if (ar) "تحديث الشاشة" else "Refresh", report.refresh, Modifier.weight(1f))
                            MetricCard(if (ar) "الأنوية" else "CPU cores", report.cores.toString(), Modifier.weight(1f))
                        } }
                        item { FeatureCard(Icons.Default.Speed, if (ar) "اختبار GPU" else "GPU Benchmark", if (ar) "مشهد OpenGL ES محلي يقيس معدل عرض المشهد ووقت الإطار." else "A local OpenGL ES scene measures rendered frame rate and frame time.", "LOCAL TEST") }
                        item { FeatureCard(Icons.Default.AutoAwesome, if (ar) "توليد الإطارات" else "Frame Generation", if (ar) "مختبر تجريبي قيد التطوير. لا يتم حقن إطارات في ألعاب خارجية." else "Experimental lab. No frame injection into third-party games.", "NOT SYSTEM-WIDE") }
                        item { FeatureCard(Icons.Default.DarkMode, "Black Sky", if (ar) "معاينة داخل التطبيق وملف تفضيل لكل لعبة؛ لا يغيّر ملفات الألعاب." else "In-app preview and per-game preference only; does not alter game assets.", "PRESET") }
                        item { Button(onClick = { page = "lab" }, Modifier.fillMaxWidth()) { Text(if (ar) "افتح مختبر الأداء" else "Open performance lab") } }
                    }
                    "device" -> {
                        item { SectionTitle(if (ar) "تقرير الجهاز" else "DEVICE REPORT") }
                        item { InfoRow(if (ar) "الجهاز" else "Device", report.model) }
                        item { InfoRow(if (ar) "النظام" else "OS", report.android) }
                        item { InfoRow(if (ar) "المعالج / SoC" else "SoC", report.soc) }
                        item { InfoRow(if (ar) "أنوية CPU المتاحة" else "Available CPU cores", report.cores.toString()) }
                        item { InfoRow(if (ar) "إجمالي RAM المعلن" else "Reported total RAM", report.ramTotal) }
                        item { InfoRow(if (ar) "RAM المتاحة" else "Available RAM", report.ramAvailable) }
                        item { InfoRow(if (ar) "معدل تحديث الشاشة" else "Display refresh", report.refresh) }
                        item { InfoRow(if (ar) "الحالة الحرارية" else "Thermal status", report.thermal) }
                        item { InfoRow("Vulkan", report.vulkan) }
                        item { InfoRow(if (ar) "GPU vendor" else "GPU vendor", gpuVendor) }
                        item { InfoRow(if (ar) "GPU renderer" else "GPU renderer", gpuRenderer) }
                        item { InfoRow("OpenGL", glVersion) }
                        item { Button(onClick = { report = collectReport(context) }, Modifier.fillMaxWidth()) { Text(if (ar) "تحديث التقرير" else "Refresh report") } }
                    }
                    "games" -> {
                        item { SectionTitle(if (ar) "ملفات الألعاب" else "GAME PROFILES") }
                        item {
                            OutlinedTextField(gameName, { gameName = it }, Modifier.fillMaxWidth(), label = { Text(if (ar) "اسم اللعبة" else "Game name") }, singleLine = true)
                            Button(onClick = {
                                val n = gameName.trim()
                                if (n.isNotEmpty() && n !in games) {
                                    games = games + n
                                    selectedGame = n
                                    prefs.edit().putStringSet("games", games.toSet()).apply()
                                    gameName = ""
                                }
                            }, Modifier.fillMaxWidth()) { Text(if (ar) "إضافة لعبة" else "Add game") }
                        }
                        item {
                            Text(if (ar) "اللعبة المحددة" else "Selected game", color = Muted)
                            games.forEach { name ->
                                Row(Modifier.fillMaxWidth().background(if (selectedGame == name) Color(0xFF1A3850) else Panel, RoundedCornerShape(13.dp)).padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selectedGame == name, onClick = {
                                        selectedGame = name
                                        targetFps = prefs.getInt("$name.fps", 60)
                                        aspect = prefs.getString("$name.aspect", "Auto") ?: "Auto"
                                        blackSky = prefs.getBoolean("$name.blackSky", false)
                                    })
                                    Text(name, Modifier.weight(1f), color = Color.White)
                                    IconButton(onClick = {
                                        games = games.filterNot { it == name }
                                        if (selectedGame == name) selectedGame = games.firstOrNull() ?: ""
                                        prefs.edit().putStringSet("games", games.toSet()).apply()
                                    }) { Icon(Icons.Default.Delete, null, tint = Muted) }
                                }
                            }
                        }
                        item {
                            Text(if (ar) "حد FPS المفضل (ملف فقط)" else "Preferred FPS cap (profile only)", color = Muted)
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                listOf(30,45,60,90).forEach { fps -> FilterChip(targetFps == fps, { targetFps = fps; saveProfile() }, label = { Text("$fps") }) }
                            }
                            Text(if (ar) "نسبة الأبعاد" else "Aspect ratio", color = Muted)
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                listOf("Auto","4:3","16:9","20:9").forEach { ratio -> FilterChip(aspect == ratio, { aspect = ratio; saveProfile() }, label = { Text(ratio) }) }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Black Sky preference", Modifier.weight(1f), color = Color.White)
                                Switch(blackSky, { blackSky = it; saveProfile() })
                            }
                            Button(onClick = { saveProfile() }, Modifier.fillMaxWidth(), enabled = selectedGame.isNotBlank()) { Text(if (ar) "حفظ ملف اللعبة" else "Save game profile") }
                            Text(if (ar) "هذه تفضيلات محفوظة فقط ولا تفرض إعدادات على اللعبة." else "These are saved preferences and do not force settings into the game.", color = Muted, fontSize = 11.sp)
                        }
                    }
                    "lab" -> {
                        item { SectionTitle(if (ar) "GPU Benchmark" else "GPU BENCHMARK") }
                        item {
                            Text(if (ar) "معلومات GPU المكتشفة" else "Detected GPU", color = Muted, fontSize = 12.sp)
                            InfoRow("Vendor", gpuVendor)
                            InfoRow("Renderer", gpuRenderer)
                            InfoRow("OpenGL", glVersion)
                            AndroidView(factory = { ctx ->
                                GpuBenchmarkView(ctx).also { view ->
                                    gpuView = view
                                    view.onResume()
                                    view.onGpuInfo = { v, r, g -> gpuVendor = v; gpuRenderer = r; glVersion = g }
                                    view.onBenchmarkUpdate = { fps, ms, done ->
                                        benchmarkFps = String.format(Locale.US, "%.1f", fps)
                                        frameMs = String.format(Locale.US, "%.2f ms", ms)
                                        benchmarkStatus = if (done) (if (ar) "اكتمل الاختبار" else "Completed") else (if (ar) "جارٍ الاختبار" else "Running")
                                    }
                                }
                            }, modifier = Modifier.fillMaxWidth().height(190.dp).background(Color.Black, RoundedCornerShape(16.dp)))
                            Button(onClick = {
                                benchmarkStatus = if (ar) "جارٍ الاختبار لمدة 10 ثوانٍ" else "Running 10-second test"
                                benchmarkFps = "--"; frameMs = "--"
                                gpuView?.startBenchmark()
                            }, Modifier.fillMaxWidth()) { Text(if (ar) "ابدأ اختبار GPU (10 ثوانٍ)" else "Start GPU test (10 seconds)") }
                            InfoRow(if (ar) "الحالة" else "Status", benchmarkStatus)
                            InfoRow("FPS", benchmarkFps)
                            InfoRow("Frame time", frameMs)
                            Text(if (ar) "النتيجة تخص مشهد الاختبار داخل التطبيق ولا تمثل FPS لعبة بعينها. أوقف الاختبار إذا ارتفعت حرارة الهاتف." else "This measures the app's test scene, not a game's FPS. Stop if the phone becomes hot.", color = Muted, fontSize = 11.sp)
                        }
                        item { Divider(color = Color(0xFF27364A)) }
                        item { SectionTitle(if (ar) "اختبار اللمس" else "TOUCH RESPONSE TEST") }
                        item {
                            Text(if (ar) "المس المنطقة بأسرع ما يناسبك. الاختبار يعدّ اللمسات ويقيس الفاصل بينها، وليس زمن استجابة النظام الدقيق." else "Tap the pad naturally. It counts taps and intervals, not true OS touch latency.", color = Muted, fontSize = 11.sp)
                            Box(Modifier.fillMaxWidth().height(145.dp).background(Color(0xFF17324A), RoundedCornerShape(18.dp))
                                .pointerInput(Unit) { detectTapGestures {
                                    val now = System.nanoTime()
                                    if (lastTap > 0L) tapInterval = String.format(Locale.US, "%.0f ms", (now-lastTap)/1_000_000.0)
                                    lastTap = now; taps++
                                } }, contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.TouchApp, null, tint = Accent, modifier = Modifier.size(32.dp))
                                    Text(if (ar) "المس هنا" else "TAP HERE", color = Color.White, fontWeight = FontWeight.Bold)
                                    Text("$taps", color = Accent, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            InfoRow(if (ar) "الفاصل بين آخر لمستين" else "Last tap interval", tapInterval)
                            OutlinedButton(onClick = { taps = 0; lastTap = 0L; tapInterval = "--" }, Modifier.fillMaxWidth()) { Text(if (ar) "إعادة الاختبار" else "Reset test") }
                        }
                        item { SectionTitle(if (ar) "مختبر استيفاء الإطارات" else "FRAME INTERPOLATION LAB") }
                        item {
                            Text(
                                if (ar) "يعرض هذا المختبر إطاراً وسيطاً على GPU بين صورتين اصطناعيتين. في هذا المشهد التجريبي نستخدم متجه حركة معروفاً لإزاحة عينتي الصورتين قبل المزج، ما يقلل ازدواجية الجسم المتحرك. هذه معاوضة حركة لمشهد مضبوط وليست Optical Flow عاماً أو AI، ولا تعالج إطارات الألعاب الخارجية." else "This lab renders an in-between GPU frame from two synthetic images. The controlled demo uses a known motion vector to warp both samples before blending, reducing object ghosting. This is not general optical flow or AI and does not process third-party game frames.",
                                color = Muted, fontSize = 11.sp, lineHeight = 16.sp
                            )
                            AndroidView(
                                factory = { ctx -> FrameInterpolationView(ctx).also { view -> interpolationView = view; view.onResume(); view.setInterpolationPhase(interpolationPhase) } },
                                modifier = Modifier.fillMaxWidth().height(190.dp).background(Color.Black, RoundedCornerShape(16.dp))
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(if (ar) "موضع الإطار الوسيط" else "Interpolation phase", color = Muted, fontSize = 12.sp)
                                Text("${(interpolationPhase * 100).toInt()}%", color = Accent, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = interpolationPhase,
                                onValueChange = { value -> interpolationPhase = value; interpolationView?.setInterpolationPhase(value) },
                                valueRange = 0f..1f
                            )
                            InfoRow(if (ar) "طريقة المعالجة" else "Processing", "GPU motion-vector warp + blend")
                            InfoRow(if (ar) "متجه الحركة" else "Motion vector", "104 / 256 texture width")
                            InfoRow(if (ar) "نطاق الحركة" else "Motion compensation", if (ar) "متجه معروف للمشهد الاصطناعي فقط" else "Known vector for synthetic demo only")
                        }
                    }
                }
                item { Text("FRAMEFORGE AI • v0.5.0 • Android", color = Muted, fontSize = 10.sp) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) { Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.background(Panel, RoundedCornerShape(16.dp)).padding(14.dp)) {
        Text(label, color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(5.dp))
        Text(value, color = Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(value, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FeatureCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, desc: String, tag: String) {
    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(15.dp)).padding(14.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = Accent, modifier = Modifier.size(25.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(desc, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(6.dp))
            Text(tag, color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}
