@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.example.chargealert

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

class AlertOverlayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            ChargeAlertTheme {
                AlertOverlayScreen(
                    percent = intent.getIntExtra(Notifications.EXTRA_PERCENT, 0),
                    onDismiss = ::dismissAlert
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun dismissAlert() {
        Notifications.cancelAlert(this)
        finish()
    }
}

@Composable
private fun AlertOverlayScreen(percent: Int, onDismiss: () -> Unit) {
    val arrowOffset = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            arrowOffset.animateTo(-23f, tween(620, easing = FastOutSlowInEasing))
            arrowOffset.animateTo(-29f, tween(220, easing = LinearOutSlowInEasing))
            arrowOffset.animateTo(
                4f,
                spring(dampingRatio = 0.38f, stiffness = 430f)
            )
            arrowOffset.animateTo(
                0f,
                spring(dampingRatio = 0.62f, stiffness = 520f)
            )
            delay(180)
        }
    }

    var dragDistance by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.48f))
            .pointerInput(onDismiss) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        dragDistance += amount
                    },
                    onDragEnd = {
                        if (dragDistance < -76f) onDismiss()
                        dragDistance = 0f
                    },
                    onDragCancel = { dragDistance = 0f }
                )
            }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .size(width = 42.dp, height = 42.dp)
                    .offset(y = arrowOffset.value.dp)
            ) {
                val centerX = size.width / 2f
                val top = size.height * 0.18f
                val bottom = size.height * 0.82f
                drawLine(
                    color = Color.White,
                    start = androidx.compose.ui.geometry.Offset(centerX, bottom),
                    end = androidx.compose.ui.geometry.Offset(centerX, top + size.height * 0.22f),
                    strokeWidth = 3.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
                val head = Path().apply {
                    moveTo(centerX - size.width * 0.25f, top + size.height * 0.25f)
                    lineTo(centerX, top)
                    lineTo(centerX + size.width * 0.25f, top + size.height * 0.25f)
                }
                drawPath(
                    path = head,
                    color = Color.White,
                    style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            Text(
                text = "اسحب لأعلى لإغلاق التنبيه",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.94f),
                fontWeight = FontWeight.SemiBold
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(16.dp, RoundedCornerShape(34.dp)),
                shape = RoundedCornerShape(34.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "افصل الشاحن الآن",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "$percent%",
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }
    }
}
