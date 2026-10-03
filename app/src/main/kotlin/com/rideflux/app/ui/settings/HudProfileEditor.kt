package com.rideflux.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rideflux.app.R
import com.rideflux.domain.settings.HudLayoutProfile
import kotlin.math.roundToInt

@Composable
internal fun HudProfileEditor(
    profile: HudLayoutProfile,
    hudVisible: Boolean,
    onProfileChange: (HudLayoutProfile) -> Unit,
    onHudVisible: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(profile) }
    val update: (HudLayoutProfile) -> Unit = { next ->
        draft = next.normalized()
        onProfileChange(draft)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_hud_customize)) },
        text = {
            Column(
                Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.settings_hud_preview))
                BoxWithConstraints(
                    Modifier.fillMaxWidth().height(180.dp).background(Color.Black),
                ) {
                    val width = maxWidth
                    val height = maxHeight
                    val labelSize = (12 * draft.fontPercent / 100f).sp
                    Box(
                        Modifier.fillMaxWidth().height(height)
                            .padding(
                                start = width * draft.leftInset / 100f,
                                end = width * draft.rightInset / 100f,
                                top = height * draft.topInset / 100f,
                                bottom = height * draft.bottomInset / 100f,
                            )
                            .offset(
                                x = width * draft.offsetX / 100f,
                                y = height * draft.offsetY / 100f,
                            )
                            .border(1.dp, Color.DarkGray),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().align(Alignment.Center),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                if (draft.shows(HudLayoutProfile.CLOCK)) Text("12:34", color = Color.Cyan, fontSize = (13 * draft.fontPercent / 100f).sp)
                                if (draft.shows(HudLayoutProfile.PHONE_BATTERY)) Text("▣ 87%", color = Color.White, fontSize = labelSize)
                                if (draft.shows(HudLayoutProfile.GLASSES_BATTERY)) Text("◇ 92%", color = Color.White, fontSize = labelSize)
                                if (draft.shows(HudLayoutProfile.SIGNAL)) Text("◉", color = Color.Green, fontSize = labelSize)
                            }
                            Text("28", color = Color.Green, fontSize = (35 * draft.fontPercent / 100f).sp)
                            Column {
                                if (draft.shows(HudLayoutProfile.WHEEL_BATTERY)) Text("64%", color = Color.Green, fontSize = labelSize)
                                if (draft.shows(HudLayoutProfile.DISTANCE)) Text("4.2 km", color = Color.White, fontSize = labelSize)
                                if (draft.shows(HudLayoutProfile.DURATION)) Text("00:14", color = Color.White, fontSize = labelSize)
                            }
                        }
                    }
                }
                TextButton(onClick = { onHudVisible(!hudVisible) }) {
                    Text(stringResource(if (hudVisible) R.string.settings_hud_hide else R.string.settings_hud_show))
                }
                ProfileSlider(stringResource(R.string.settings_hud_left), draft.leftInset, 0..30) {
                    update(draft.copy(leftInset = it))
                }
                ProfileSlider(stringResource(R.string.settings_hud_right), draft.rightInset, 0..30) {
                    update(draft.copy(rightInset = it))
                }
                ProfileSlider(stringResource(R.string.settings_hud_top), draft.topInset, 0..30) {
                    update(draft.copy(topInset = it))
                }
                ProfileSlider(stringResource(R.string.settings_hud_bottom), draft.bottomInset, 0..30) {
                    update(draft.copy(bottomInset = it))
                }
                ProfileSlider(stringResource(R.string.settings_hud_horizontal), draft.offsetX, -20..20) {
                    update(draft.copy(offsetX = it))
                }
                ProfileSlider(stringResource(R.string.settings_hud_vertical), draft.offsetY, -20..20) {
                    update(draft.copy(offsetY = it))
                }
                ProfileSlider(stringResource(R.string.settings_hud_font), draft.fontPercent, 70..150) {
                    update(draft.copy(fontPercent = it))
                }
                val items = listOf(
                    HudLayoutProfile.CLOCK to R.string.settings_hud_clock,
                    HudLayoutProfile.PHONE_BATTERY to R.string.settings_hud_phone_battery,
                    HudLayoutProfile.GLASSES_BATTERY to R.string.settings_hud_glasses_battery,
                    HudLayoutProfile.SIGNAL to R.string.settings_hud_signal,
                    HudLayoutProfile.WHEEL_BATTERY to R.string.settings_hud_wheel_battery,
                    HudLayoutProfile.DISTANCE to R.string.metric_trip_distance,
                    HudLayoutProfile.DURATION to R.string.metric_ride_time,
                )
                items.forEach { (flag, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draft.shows(flag),
                            onCheckedChange = { checked ->
                                val next = if (checked) draft.visibleItems or flag else draft.visibleItems and flag.inv()
                                update(draft.copy(visibleItems = next))
                            },
                        )
                        Text(stringResource(label))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_back)) } },
    )
}

@Composable
private fun ProfileSlider(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column {
        Text("$label: $value%")
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}
