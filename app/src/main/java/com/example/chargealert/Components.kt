@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.example.chargealert

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.RingtoneManager
import android.net.Uri
import android.app.Activity
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/** حالة الإعدادات المشتركة بين الواجهتين */
class SettingsState(private val context: Context) {
    var threshold by mutableIntStateOf(Prefs.threshold(context))
        private set
    var enabled by mutableStateOf(Prefs.isEnabled(context))
        private set

    fun reload() {
        threshold = Prefs.threshold(context)
        enabled = Prefs.isEnabled(context)
    }

    fun updateThreshold(value: Int) {
        Prefs.setThreshold(context, value)
        threshold = Prefs.threshold(context)
        MonitorController.onThresholdChanged(context)
    }

    fun updateEnabled(on: Boolean) {
        MonitorController.setEnabled(context, on)
        enabled = Prefs.isEnabled(context)
    }
}

@Composable
fun rememberSettingsState(): SettingsState {
    val context = LocalContext.current.applicationContext
    val state = remember { SettingsState(context) }
    DisposableEffect(state) {
        val prefs = Prefs.of(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> state.reload() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}

@Composable
fun rememberBatteryInfo(): State<BatteryInfo> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(BatteryUtils.read(context)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                state.value = BatteryUtils.parse(intent)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    return state
}

/** يطلب إذن الإشعارات (أندرويد 13+) قبل تفعيل المراقبة */
@Composable
fun rememberToggleAction(settings: SettingsState): (Boolean) -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val exactAllowed = Build.VERSION.SDK_INT < 31 ||
                context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()
            val fullScreenAllowed = Build.VERSION.SDK_INT < 34 ||
                context.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()
            when {
                !exactAllowed -> runCatching {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                            .setData(Uri.parse("package:${context.packageName}"))
                    )
                }
                !fullScreenAllowed -> runCatching {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                            .setData(Uri.parse("package:${context.packageName}"))
                    )
                }
                else -> settings.updateEnabled(true)
            }
        }
    }

    return remember(settings) {
        { on: Boolean ->
            val needsPermission = Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            if (!on) {
                settings.updateEnabled(false)
            } else if (needsPermission) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else if (Build.VERSION.SDK_INT >= 31 &&
                !context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()
            ) {
                runCatching {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                            .setData(Uri.parse("package:${context.packageName}"))
                    )
                }
            } else if (Build.VERSION.SDK_INT >= 34 &&
                !context.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()
            ) {
                runCatching {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                            .setData(Uri.parse("package:${context.packageName}"))
                    )
                }
            } else {
                settings.updateEnabled(true)
            }
        }
    }
}


@Composable
fun AlertSoundSettings() {
    val context = LocalContext.current
    var selectedUri by remember { mutableStateOf(Prefs.alertSoundUri(context)) }
    val currentUri = selectedUri?.let { Uri.parse(it) }
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
    val currentName = remember(selectedUri) {
        runCatching {
            RingtoneManager.getRingtone(context, currentUri)?.getTitle(context)
        }.getOrNull() ?: context.getString(R.string.alert_sound_default)
    }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val picked = result.data?.getParcelableExtra<Uri>(
                RingtoneManager.EXTRA_RINGTONE_PICKED_URI
            )
            selectedUri = picked?.toString()
            Prefs.setAlertSoundUri(context, selectedUri)
            Notifications.createChannels(context)
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
                text = stringResource(R.string.alert_sound_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = currentName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
            OutlinedButton(
                onClick = {
                    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, context.getString(R.string.alert_sound_picker_title))
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentUri)
                    }
                    picker.launch(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.choose_alert_sound))
            }
        }
    }
}

@Composable
fun ChargeControls(
    settings: SettingsState,
    battery: BatteryInfo,
    onToggle: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StatusCard(battery)
        MonitorCard(settings.enabled, onToggle)
        ThresholdCard(settings)
    }
}

@Composable
private fun StatusCard(battery: BatteryInfo) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.percent, battery.percent),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Black
            )
            Text(
                text = stringResource(if (battery.plugged) R.string.plugged else R.string.unplugged),
                style = MaterialTheme.typography.titleMedium
            )
            LinearWavyProgressIndicator(
                progress = { battery.percent / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun MonitorCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val container by animateColorAsState(
        targetValue = if (enabled) MaterialTheme.colorScheme.tertiaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Color>(),
        label = "monitorCard"
    )
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = container,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!enabled) }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.monitor_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(if (enabled) R.string.monitor_on else R.string.monitor_off),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun ThresholdCard(settings: SettingsState) {
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.threshold_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.percent, settings.threshold),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black
                )
            }
            Slider(
                value = settings.threshold.toFloat(),
                onValueChange = { settings.updateThreshold(it.roundToInt()) },
                valueRange = 50f..100f,
                steps = 49
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(70, 80, 90, 100).forEach { preset ->
                    ToggleButton(
                        checked = settings.threshold == preset,
                        onCheckedChange = { settings.updateThreshold(preset) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.percent, preset))
                    }
                }
            }
        }
    }
}
