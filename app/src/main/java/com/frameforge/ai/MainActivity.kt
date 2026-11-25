package com.frameforge.ai

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

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
    val model: String,
    val android: String,
    val soc: String,
    val cores: Int,
    val ramTotal: String,
    val ramAvailable: String,
    val refresh: String,
    val thermal: String,
    val glVersion: String
)

private fun mb(v: Long) = String.format(Locale.US, "%.1f GB", v / 1024.0 / 1024.0 / 1024.0)

private fun collectReport(context: Context): DeviceReport {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mem = ActivityManager.MemoryInfo()
    am.getMemoryInfo(mem)
    val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    val display: Display? = dm.getDisplay(Display.DEFAULT_DISPLAY)
    val hz = display?.refreshRate ?: 0f
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
    } else "Not exposed by Android version"
    val soc = if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL.isNotBlank()) {
        Build.SOC_MODEL
    } else {
        "Unisoc T606 (device profile; verify)"
    }
    return DeviceReport(
        model = "${Build.MANUFACTURER} ${Build.MODEL}",
        android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        soc = soc,
        cores = Runtime.getRuntime().availableProcessors(),
        ramTotal = mb(mem.totalMem),
        ramAvailable = mb(mem.availMem),
        refresh = if (hz > 0f) String.format(Locale.US, "%.0f Hz", hz) else "Unavailable",
        thermal = thermal,
        glVersion = "OpenGL ES requirement: ${
            try {
                val info = context.packageManager.systemAvailableFeatures
                    .firstOrNull { it.name == null && it.reqGlEsVersion > 0 }
                info?.let { "ES ${(it.reqGlEsVersion shr 16)}.${(it.reqGlEsVersion and 0xffff)}" } ?: "Unknown"
            } catch (_: Exception) { "Unknown" }
        }"
    )
}

@Composable
fun FrameForgeApp(context: Context) {
    var page by remember { mutableStateOf("home") }
    var english by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf(collectReport(context)) }
    var targetFps by remember { mutableIntStateOf(60) }
    var selectedAspect by remember { mutableStateOf("Auto") }
    var blackSky by remember { mutableStateOf(false) }
    var gameName by remember { mutableStateOf("") }
    val prefs = remember { context.getSharedPreferences("frameforge", Context.MODE_PRIVATE) }
    var games by remember { mutableStateOf(prefs.getStringSet("games", setOf("PUBG Mobile", "Call of Duty Mobile", "Free Fire"))!!.toList()) }

    val ar = !english
    val title = if (ar) "مركز الألعاب" else "Gaming Center"
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Bg, surface = Panel, primary = Accent,
            onBackground = Color.White, onSurface = Color.White
        )
    ) {
        Scaffold(containerColor = Bg, bottomBar = {
            NavigationBar(containerColor = Panel, contentColor = Color.White) {
                val items = listOf(
                    Triple("home", if (ar) "الرئيسية" else "Home", Icons.Default.Home),
                    Triple("device", if (ar) "الجهاز" else "Device", Icons.Default.Memory),
                    Triple("games", if (ar) "الألعاب" else "Games", Icons.Default.SportsEsports),
                    Triple("lab", if (ar) "المختبر" else "Lab", Icons.Default.Science)
                )
                items.forEach { (id, label, icon) ->
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
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp),
                contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Text("FRAMEFORGE", color = Color.White, fontWeight = FontWeight.Black, fontSize = 23.sp)
                            Text("AI  •  MOBILE EDITION", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        TextButton(onClick = { english = !english }) {
                            Text(if (english) "العربية" else "English", color = Accent)
                        }
                    }
                }
                when (page) {
                    "home" -> {
                        item {
                            Column(Modifier.fillMaxWidth().background(
                                androidx.compose.ui.graphics.Brush.linearGradient(
                                    listOf(Color(0xFF173B58), Color(0xFF172238), Panel)
                                ), RoundedCornerShape(22.dp)
                            ).padding(18.dp)) {
                                Text(if (ar) "أهلاً بك في FRAMEFORGE" else "WELCOME TO FRAMEFORGE", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                Text(if (ar) "تحكم بأداء ألعابك بذكاء" else "Smarter control for your games", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                Text(if (ar) "إصدار تأسيسي: فحص الجهاز وملفات الألعاب وأدوات التوافق." else "Foundation build: device diagnostics, game profiles and compatibility tools.", color = Muted, fontSize = 12.sp)
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                MetricCard(if (ar) "معدل الشاشة" else "Refresh rate", report.refresh, Modifier.weight(1f))
                                MetricCard(if (ar) "الأنوية" else "CPU cores", report.cores.toString(), Modifier.weight(1f))
                            }
                        }
                        item {
                            SectionTitle(if (ar) "محركات FRAMEFORGE" else "FRAMEFORGE ENGINES")
                        }
                        item { FeatureCard(Icons.Default.AutoAwesome, if (ar) "توليد الإطارات" else "Frame Generation", if (ar) "فحص التوافق فقط حالياً؛ لا يوجد API عام يسمح للتطبيق بتوليد إطارات داخل ألعاب أخرى على أندرويد." else "Compatibility diagnostics only. Android has no public API for injecting generated frames into other games.", "EXPERIMENTAL") }
                        item { FeatureCard(Icons.Default.DarkMode, if (ar) "Black Sky" else "Black Sky", if (ar) "ملف مظهر تجريبي داخل التطبيق. لا يغيّر سماء الألعاب الخارجية." else "Visual preset preview inside this app; it does not alter skies in external games.", "PRESET") }
                        item { FeatureCard(Icons.Default.TouchApp, if (ar) "تحسين اللمس والتصويب" else "Touch & Aim Tuning", if (ar) "إرشادات حساسية واختبار لمس مستقل، دون تصويب آلي أو تعديل للعبة." else "Sensitivity notes and a standalone touch test, with no automated aiming or game modification.", "SAFE TOOLS") }
                        item {
                            Button(onClick = { page = "device"; report = collectReport(context) }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp))
                                Text(if (ar) "فحص الجهاز الآن" else "Scan device now")
                            }
                        }
                    }
                    "device" -> {
                        item { SectionTitle(if (ar) "تقرير الجهاز" else "DEVICE REPORT") }
                        item { InfoRow(if (ar) "الجهاز" else "Device", report.model) }
                        item { InfoRow(if (ar) "النظام" else "Operating system", report.android) }
                        item { InfoRow(if (ar) "المعالج" else "SoC", report.soc) }
                        item { InfoRow(if (ar) "أنوية المعالج المتاحة" else "Available CPU cores", report.cores.toString()) }
                        item { InfoRow(if (ar) "إجمالي RAM" else "Total RAM", report.ramTotal) }
                        item { InfoRow(if (ar) "RAM المتاحة حالياً" else "Currently available RAM", report.ramAvailable) }
                        item { InfoRow(if (ar) "تحديث الشاشة" else "Display refresh", report.refresh) }
                        item { InfoRow(if (ar) "حالة الحرارة" else "Thermal status", report.thermal) }
                        item { InfoRow(if (ar) "رسوميات النظام" else "Graphics capability", report.glVersion) }
                        item {
                            Text(if (ar) "ملاحظة: قراءة RAM تشمل ما يبلّغ عنه النظام وقد لا تساوي الذاكرة الفعلية المتاحة للتطبيق. اسم GPU الدقيق يحتاج قراءة OpenGL renderer في اختبار منفصل." else "Note: RAM is reported by Android and may include reserved/virtual memory. Exact GPU renderer requires a dedicated OpenGL renderer probe.", color = Muted, fontSize = 11.sp)
                        }
                        item { Button(onClick = { report = collectReport(context) }, modifier = Modifier.fillMaxWidth()) { Text(if (ar) "تحديث التقرير" else "Refresh report") } }
                    }
                    "games" -> {
                        item { SectionTitle(if (ar) "ملفات الألعاب" else "GAME PROFILES") }
                        item {
                            OutlinedTextField(value = gameName, onValueChange = { gameName = it }, modifier = Modifier.fillMaxWidth(),
                                label = { Text(if (ar) "اسم اللعبة" else "Game name") }, singleLine = true)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = {
                                val clean = gameName.trim()
                                if (clean.isNotEmpty() && !games.contains(clean)) {
                                    games = games + clean
                                    prefs.edit().putStringSet("games", games.toSet()).apply()
                                    gameName = ""
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text(if (ar) "إضافة لعبة" else "Add game") }
                        }
                        item {
                            Text(if (ar) "حد الإطارات المستهدف (ملف إعداد فقط)" else "Target FPS (profile setting only)", color = Muted)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(30, 45, 60, 90).forEach { fps ->
                                    FilterChip(selected = targetFps == fps, onClick = { targetFps = fps }, label = { Text("$fps") })
                                }
                            }
                        }
                        item {
                            Text(if (ar) "أبعاد الشاشة" else "Aspect ratio profile", color = Muted)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("Auto", "4:3", "16:9", "20:9").forEach { ratio ->
                                    FilterChip(selected = selectedAspect == ratio, onClick = { selectedAspect = ratio }, label = { Text(ratio) })
                                }
                            }
                            Text(if (ar) "هذه اختيارات لملفك فقط؛ تغيير أبعاد لعبة أخرى يتطلب أن تدعم اللعبة ذلك." else "These are profile preferences; changing another game's aspect ratio requires in-game or system support.", color = Muted, fontSize = 11.sp)
                        }
                        items(games.size) { index ->
                            val name = games[index]
                            Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.SportsEsports, null, tint = Accent)
                                Spacer(Modifier.width(10.dp))
                                Text(name, Modifier.weight(1f), color = Color.White)
                                IconButton(onClick = {
                                    games = games.filterNot { it == name }
                                    prefs.edit().putStringSet("games", games.toSet()).apply()
                                }) { Icon(Icons.Default.Delete, null, tint = Muted) }
                            }
                        }
                    }
                    "lab" -> {
                        item { SectionTitle(if (ar) "مختبر الصورة والأداء" else "VISUAL & PERFORMANCE LAB") }
                        item {
                            FeatureCard(Icons.Default.AutoGraph, if (ar) "Frame Generation Lab" else "Frame Generation Lab",
                                if (ar) "الهاتف يدعم شاشة تصل إلى 90Hz بحسب النسخة، لكن الشاشة وحدها لا تعني إمكانية توليد إطارات. نحتاج مسار معالجة GPU وتكامل خاص مع اللعبة." else "The device may have a 90Hz panel, but refresh rate is not frame generation. Real generation requires a GPU processing pipeline and game-level integration.", "NOT SYSTEM-WIDE")
                        }
                        item {
                            Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(if (ar) "Black Sky Preview" else "Black Sky Preview", color = Color.White, fontWeight = FontWeight.Bold)
                                    Text(if (ar) "معاينة مظهر داكن داخل التطبيق" else "Dark visual preview inside this app", color = Muted, fontSize = 11.sp)
                                }
                                Switch(checked = blackSky, onCheckedChange = { blackSky = it })
                            }
                            Box(Modifier.fillMaxWidth().height(110.dp).background(if (blackSky) Color.Black else Color(0xFF233B59), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                                Text(if (blackSky) "BLACK SKY PREVIEW" else "STANDARD SKY PREVIEW", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                        item {
                            FeatureCard(Icons.Default.Speed, if (ar) "اختبار الاستجابة" else "Touch response practice",
                                if (ar) "اختبار تدريبي بسيط داخل التطبيق قيد التخطيط. لا يغيّر حساسية الألعاب الخارجية." else "A standalone practice test is planned. It cannot change sensitivity in external games.", "TRAINING")
                        }
                        item {
                            FeatureCard(Icons.Default.Thermostat, if (ar) "الإدارة الحرارية" else "Thermal awareness",
                                if (ar) "نقرأ حالة الحرارة التي يتيحها Android. لا نفرض كسر سرعة أو تعطيل حماية الحرارة." else "Reads Android thermal status. No overclocking or thermal-protection bypass.", "DEVICE API")
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(4.dp))
                    Text("FRAMEFORGE AI • v0.1.0 • Android Foundation", color = Muted, fontSize = 10.sp, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
}

@Composable
fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.background(Panel, RoundedCornerShape(16.dp)).padding(14.dp)) {
        Text(label, color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(5.dp))
        Text(value, color = Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(13.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun FeatureCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, desc: String, tag: String) {
    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = Accent, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(desc, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(7.dp))
            Text(tag, color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}
