@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.example.chargealert

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** شاشة التشخيص المتقدم: فحوص حيّة + اختبارات + سجل الأحداث + تقرير كامل قابل للنسخ والمشاركة */
class DiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DebugLog.i(this, "UI", "diagnostics screen opened ${DebugLog.screenState(this)}")
        setContent { ChargeAlertTheme { DiagnosticsScreen() } }
    }
}

private enum class LogFilter(val label: String) {
    ALL("الكل"),
    PROBLEMS("تحذيرات وأخطاء"),
    ALERT("التنبيه والصوت"),
    SERVICE("الخدمة والنبض")
}

private fun applyFilter(lines: List<String>, filter: LogFilter): List<String> = when (filter) {
    LogFilter.ALL -> lines
    LogFilter.PROBLEMS -> lines.filter { DebugLog.levelOf(it) == 'W' || DebugLog.levelOf(it) == 'E' }
    LogFilter.ALERT -> lines.filter { DebugLog.tagOf(it) in setOf("ALERT", "SOUND", "TEST") }
    LogFilter.SERVICE -> lines.filter {
        DebugLog.tagOf(it) in setOf("SVC", "BEAT", "WAKELOCK", "PROC", "EXIT", "SYS", "SCHED", "BATT")
    }
}

@Composable
private fun DiagnosticsScreen() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    var note by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            tick++
        }
    }

    val checks = remember(tick) { Diagnostics.checks(context) }
    val logLines = remember(tick, filter) {
        applyFilter(DebugLog.lines(context, 400), filter).takeLast(200).reversed()
    }
    val problems = checks.count { it.status == CheckStatus.BAD }
    val warnings = checks.count { it.status == CheckStatus.WARN }

    val warnColor = MaterialTheme.colorScheme.tertiary
    val errorColor = MaterialTheme.colorScheme.error
    val logText = remember(logLines, warnColor, errorColor) {
        buildAnnotatedString {
            for (line in logLines) {
                val color = when (DebugLog.levelOf(line)) {
                    'E' -> errorColor
                    'W' -> warnColor
                    else -> androidx.compose.ui.graphics.Color.Unspecified
                }
                withStyle(SpanStyle(color = color)) { append(line) }
                append("\n")
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("التشخيص المتقدم") },
                subtitle = { Text("حالة النظام والاختبارات وسجل الأحداث") },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---------------------------------------------------------------- ملخص
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = if (problems > 0) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = when {
                            problems > 0 -> "❌ $problems مشكلة تحتاج إصلاحاً"
                            warnings > 0 -> "⚠️ $warnings تحذير"
                            else -> "✅ كل الفحوص سليمة"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "تتحدث هذه الصفحة تلقائياً كل ثانيتين.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // ---------------------------------------------------------------- الفحوص الحية
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "الحالة الآن",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    for (check in checks) {
                        CheckRow(check) { fix -> Diagnostics.openFix(context, fix) }
                    }
                }
            }

            // ---------------------------------------------------------------- الاختبارات
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "اختبارات",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Button(
                        onClick = {
                            val ok = TestAlertReceiver.schedule(context, 30)
                            note = if (ok) "تمت الجدولة: أغلق الشاشة الآن وانتظر 30 ثانية. ثم افتح هذه الصفحة وراجع السجل."
                            else "فشلت الجدولة (راجع السجل)"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("اختبار التنبيه بعد 30 ثانية (والشاشة مغلقة)") }
                    FilledTonalButton(
                        onClick = {
                            Notifications.createChannels(context)
                            Notifications.alert(context, Prefs.threshold(context))
                            note = "أُطلق تنبيه تجريبي الآن"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("اختبار التنبيه الآن") }
                    OutlinedButton(
                        onClick = {
                            Notifications.cancelAlert(context)
                            note = "أُوقف التنبيه"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("إيقاف أي تنبيه جارٍ") }
                    OutlinedButton(
                        onClick = {
                            val app = context.applicationContext
                            DebugLog.i(app, "UI", "manual check requested")
                            Thread { ChargeScheduler.check(app) }.start()
                            note = "نُفّذ فحص فوري (راجع السجل)"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("فحص فوري للنسبة") }
                    OutlinedButton(
                        onClick = {
                            Diagnostics.restartMonitor(context)
                            note = "أُعيد تشغيل المراقبة"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("إعادة تشغيل المراقبة") }
                    if (note.isNotEmpty()) {
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // ---------------------------------------------------------------- السجل
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "سجل الأحداث (الأحدث أولاً)",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (f in LogFilter.entries) {
                            FilterChip(
                                selected = filter == f,
                                onClick = { filter = f },
                                label = { Text(f.label) }
                            )
                        }
                    }
                    SelectionContainer {
                        Text(
                            text = if (logLines.isEmpty()) androidx.compose.ui.text.AnnotatedString("لا توجد أحداث مطابقة") else logText,
                            style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                    Button(
                        onClick = {
                            val cm = context.getSystemService(ClipboardManager::class.java)
                            cm.setPrimaryClip(
                                ClipData.newPlainText("charge-alert-report", Diagnostics.fullReport(context))
                            )
                            note = "نُسخ التقرير الكامل إلى الحافظة — الصقه في المحادثة"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("نسخ التقرير الكامل") }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { Diagnostics.shareReport(context) },
                            modifier = Modifier.weight(1f)
                        ) { Text("مشاركة كملف") }
                        OutlinedButton(
                            onClick = {
                                DebugLog.clear(context)
                                note = "مُسح السجل"
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("مسح السجل") }
                    }
                }
            }

            // ---------------------------------------------------------------- الإرشادات
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "كيف تحدد السبب إن لم يعمل التنبيه والشاشة مغلقة؟",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "1) فعّل المراقبة واضبط النسبة على (النسبة الحالية + 1).\n" +
                            "2) وصّل الشاحن، أغلق الشاشة، وانتظر دقيقتين دون لمسها.\n" +
                            "3) افتح هذه الصفحة، انسخ التقرير الكامل وأرسله.\n\n" +
                            "ماذا تعني الأسطر في السجل:\n" +
                            "• BEAT GAP: توقف المعالج أو جُمّدت العملية.\n" +
                            "• EXIT ... reason=: النظام قتل العملية (الاسم يوضح السبب).\n" +
                            "• FGS start REFUSED: رفض النظام تشغيل الخدمة من الخلفية.\n" +
                            "• THRESHOLD REACHED ثم ALERT env: تم الكشف؛ راجع الصوت والشاشة.\n" +
                            "• PROC process START: بدأت عملية جديدة (رقم pid تغيّر).",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun CheckRow(check: Check, onFix: (Fix) -> Unit) {
    val icon = when (check.status) {
        CheckStatus.OK -> "✅"
        CheckStatus.WARN -> "⚠️"
        CheckStatus.BAD -> "❌"
        CheckStatus.INFO -> "ℹ️"
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(text = icon, style = MaterialTheme.typography.titleMedium)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = check.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = check.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val fix = check.fix
        if (fix != null) {
            TextButton(onClick = { onFix(fix) }) { Text("إصلاح") }
        }
    }
}
