package com.wheellog.next.feature.hudgateway

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HudGatewayScreen(
    viewModel: HudGatewayViewModel = hiltViewModel(),
) {
    val uiState by viewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is HudGatewayEffect.ShowError ->
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HUD Gateway") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Connection status card
            ConnectionStatusCard(uiState)

            // Start / Stop button
            if (uiState.isAdvertising) {
                OutlinedButton(
                    onClick = { viewModel.onIntent(HudGatewayIntent.ToggleAdvertising) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Stop HUD Server")
                }
            } else {
                Button(
                    onClick = { viewModel.onIntent(HudGatewayIntent.ToggleAdvertising) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.BluetoothSearching,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start HUD Server")
                }
            }

            // Instructions card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "How to connect Rokid glasses",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "1. Tap \"Start HUD Server\" to begin BLE advertising\n" +
                            "2. Open the WheelLog HUD app on your Rokid glasses\n" +
                            "3. The glasses will automatically scan and connect\n" +
                            "4. Once connected, real-time telemetry will be displayed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusCard(uiState: HudGatewayState) {
    val hasClients = uiState.connectedDeviceCount > 0

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                hasClients -> MaterialTheme.colorScheme.primaryContainer
                uiState.isAdvertising -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = when {
                    hasClients -> Icons.Default.BluetoothConnected
                    uiState.isAdvertising -> Icons.AutoMirrored.Filled.BluetoothSearching
                    else -> Icons.Default.Bluetooth
                },
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = when {
                    hasClients -> MaterialTheme.colorScheme.primary
                    uiState.isAdvertising -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                },
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = when {
                    hasClients -> "Rokid Glasses Connected"
                    uiState.isAdvertising -> "Waiting for Rokid glasses..."
                    else -> "HUD Server Stopped"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = when {
                    hasClients -> "${uiState.connectedDeviceCount} device(s) connected"
                    uiState.isAdvertising -> "Broadcasting BLE service..."
                    else -> "Tap the button below to start"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
        }
    }
}
