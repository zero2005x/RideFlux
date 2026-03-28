package com.wheellog.next.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            // Unit toggle
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Metric Units",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = if (uiState.useMetricUnits) "km/h, °C" else "mph, °F",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                Switch(
                    checked = uiState.useMetricUnits,
                    onCheckedChange = { viewModel.onIntent(SettingsIntent.SetMetricUnits(it)) },
                )
            }

            // Overspeed threshold
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Overspeed Alert",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "%.0f km/h".format(uiState.overspeedThresholdKmh),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Slider(
                value = uiState.overspeedThresholdKmh,
                onValueChange = { viewModel.onIntent(SettingsIntent.SetOverspeedThreshold(it)) },
                valueRange = 10f..80f,
                steps = 13,
                modifier = Modifier.fillMaxWidth(),
            )

            // Support button
            Spacer(modifier = Modifier.height(32.dp))
            KofiButton(
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun KofiButton(
    modifier: Modifier = Modifier,
    kofiUrl: String = "https://ko-fi.com/liangtinglin",
) {
    val context = LocalContext.current
    Button(
        onClick = {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(kofiUrl))
            context.startActivity(intent)
        },
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFFFF5E5B),
            contentColor = Color.White,
        ),
    ) {
        Icon(
            imageVector = Icons.Default.LocalCafe,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = "Support me on Ko-fi")
    }
}
