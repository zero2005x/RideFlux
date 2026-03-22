package com.wheellog.next.hud

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

// HUD color palette — black background is optically transparent on Rokid
private val HudGreen = Color(0xFF00FF88)
private val HudRed = Color(0xFFFF4444)
private val HudAmber = Color(0xFFFFAA00)
private val HudBackground = Color.Black

/**
 * Full-screen HUD overlay for Rokid AR glasses.
 * Black background = optically transparent. High-brightness green text.
 *
 * The manifest sets screenOrientation="portrait" so Android handles the
 * display rotation natively — no graphicsLayer rotation needed, no clipping.
 *
 * Layout: single bottom row — Wheel BAT% | Speed | Glasses BAT%
 */
@Composable
fun HudOverlayScreen(
    hudDataFlow: StateFlow<HudData>,
    connectionStateFlow: StateFlow<String>,
    glassBatteryFlow: StateFlow<Int>,
) {
    val hudData by hudDataFlow.collectAsState()
    val connectionState by connectionStateFlow.collectAsState()
    val glassBattery by glassBatteryFlow.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HudBackground)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        when (connectionState) {
            "Receiving" -> HudContent(hudData, glassBattery)
            "Connected" -> StatusText("Connected — waiting for data...")
            "Scanning..." -> StatusText("Scanning for RideFlux...")
            "Retrying scan..." -> StatusText("Phone not found — retrying...")
            "Connecting..." -> StatusText("Connecting...")
            else -> StatusText("Disconnected")
        }
    }
}

private fun batteryColor(percent: Int): Color = when {
    percent < 10 -> HudRed
    percent < 30 -> HudAmber
    else -> HudGreen
}

@Composable
private fun HudContent(data: HudData, glassBattery: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        // Left: EUC icon + Wheel battery
        val wheelColor = batteryColor(data.batteryPercent)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f),
        ) {
            EucIcon(color = wheelColor, modifier = Modifier.size(28.dp))
            Text(
                text = "${data.batteryPercent}%",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = wheelColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
            )
        }

        // Center: large speed + unit + time
        SpeedColumn(data = data, modifier = Modifier.weight(1.5f))

        // Right: Glasses icon + Glasses battery
        val glassesColor = batteryColor(glassBattery)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f),
        ) {
            GlassesIcon(color = glassesColor, modifier = Modifier.size(28.dp))
            Text(
                text = "${glassBattery}%",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = glassesColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun SpeedColumn(data: HudData, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        val displaySpeed = if (data.useMetric) {
            abs(data.speedKmh)
        } else {
            abs(data.speedKmh) * 0.621371f
        }
        Text(
            text = "%.1f".format(displaySpeed),
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = if (data.hasOverspeed) HudRed else HudGreen,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = if (data.useMetric) "km/h" else "mph",
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            color = HudGreen.copy(alpha = 0.7f),
            maxLines = 1,
        )

        // Current time — always below speed
        val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
        var currentTime by remember { mutableStateOf(timeFormat.format(Date())) }
        LaunchedEffect(Unit) {
            while (true) {
                currentTime = timeFormat.format(Date())
                delay(1000L)
            }
        }
        Text(
            text = currentTime,
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace,
            color = HudGreen.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Electric unicycle icon: wheel circle + hub + pedals + handle post. */
@Composable
private fun EucIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = w * 0.08f
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)

        // Main wheel
        drawCircle(
            color = color,
            radius = w * 0.34f,
            center = Offset(w * 0.5f, h * 0.58f),
            style = stroke,
        )
        // Hub
        drawCircle(
            color = color,
            radius = w * 0.07f,
            center = Offset(w * 0.5f, h * 0.58f),
        )
        // Left pedal
        drawLine(
            color = color,
            start = Offset(w * 0.06f, h * 0.58f),
            end = Offset(w * 0.22f, h * 0.58f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        // Right pedal
        drawLine(
            color = color,
            start = Offset(w * 0.78f, h * 0.58f),
            end = Offset(w * 0.94f, h * 0.58f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        // Handle post
        drawLine(
            color = color,
            start = Offset(w * 0.5f, h * 0.05f),
            end = Offset(w * 0.5f, h * 0.28f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        // Handle grip (horizontal bar at top)
        drawLine(
            color = color,
            start = Offset(w * 0.38f, h * 0.05f),
            end = Offset(w * 0.62f, h * 0.05f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

/** AR glasses icon: two rectangular lenses + bridge + temple arms. */
@Composable
private fun GlassesIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = w * 0.08f
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)

        // Left lens
        drawCircle(
            color = color,
            radius = w * 0.2f,
            center = Offset(w * 0.28f, h * 0.5f),
            style = stroke,
        )
        // Right lens
        drawCircle(
            color = color,
            radius = w * 0.2f,
            center = Offset(w * 0.72f, h * 0.5f),
            style = stroke,
        )
        // Bridge
        drawLine(
            color = color,
            start = Offset(w * 0.44f, h * 0.44f),
            end = Offset(w * 0.56f, h * 0.44f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        // Left temple arm
        drawLine(
            color = color,
            start = Offset(w * 0.08f, h * 0.44f),
            end = Offset(w * 0.0f, h * 0.34f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        // Right temple arm
        drawLine(
            color = color,
            start = Offset(w * 0.92f, h * 0.44f),
            end = Offset(w * 1.0f, h * 0.34f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun StatusText(message: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "status_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "status_alpha",
    )

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            fontSize = 18.sp,
            fontFamily = FontFamily.Monospace,
            color = HudGreen.copy(alpha = alpha),
            softWrap = false,
            maxLines = 1,
        )
    }
}