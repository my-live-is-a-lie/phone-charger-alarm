package com.example.chargealert

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** بطاقة مختصرة في الشاشة الرئيسية: آخر الأحداث + زر لفتح التشخيص المتقدم */
@Composable
fun DebugLogCard() {
    val context = LocalContext.current
    var text by remember { mutableStateOf(DebugLog.lines(context, 8).joinToString("\n")) }

    LaunchedEffect(Unit) {
        while (true) {
            text = DebugLog.lines(context, 8).joinToString("\n")
            delay(2000)
        }
    }

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
                text = "سجل التشخيص",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (text.isBlank()) "لا توجد أحداث بعد" else text,
                style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { context.startActivity(Intent(context, DiagnosticsActivity::class.java)) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("فتح التشخيص المتقدم") }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("charge-alert-report", Diagnostics.fullReport(context)))
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("نسخ التقرير الكامل") }
                OutlinedButton(
                    onClick = {
                        DebugLog.clear(context)
                        text = ""
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("مسح") }
            }
        }
    }
}
