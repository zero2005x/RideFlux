/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.bond

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.rideflux.app.R
import com.rideflux.app.navigation.Routes
import com.rideflux.domain.bond.BondEnvelope
import com.rideflux.domain.bond.BondFamily

@Composable
fun BondImportNavigator(navController: NavController) {
    val pendingUri by PendingBondImport.pendingUri.collectAsStateWithLifecycle()
    LaunchedEffect(pendingUri) {
        if (pendingUri != null && navController.currentDestination != null) {
            navController.navigate(Routes.BOND_BACKUP) {
                launchSingleTop = true
            }
        }
    }
}

@Composable
fun BondBackupRoute(
    onNavigateUp: () -> Unit,
    viewModel: BondBackupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SecureWindowEffect()
    val reauth = rememberDeviceReauth { ok -> viewModel.onReauthResult(ok, SystemClock.elapsedRealtime()) }
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BondEnvelope.MIME_TYPE),
    ) { uri -> viewModel.onExportDocumentCreated(uri?.toString()) }
    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> viewModel.onImportDocumentPicked(uri?.toString()) }

    val pendingUri by PendingBondImport.pendingUri.collectAsStateWithLifecycle()
    LaunchedEffect(pendingUri) {
        val uri = pendingUri
        if (uri != null) {
            PendingBondImport.pendingUri.value = null
            viewModel.onImportDocumentPicked(uri)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                BondEvent.NeedReauth ->
                    if (reauth.isAvailable()) {
                        reauth.start(
                            context.getString(R.string.bond_reauth_title),
                            context.getString(R.string.bond_reauth_subtitle),
                        )
                    } else {
                        viewModel.reauthUnavailable()
                    }
                is BondEvent.CreateDocument -> createDocument.launch(event.fileName)
                is BondEvent.ShowNotice ->
                    Toast.makeText(context, noticeText(context, event.notice), Toast.LENGTH_LONG).show()
            }
        }
    }

    BondBackupScreen(
        state = state,
        onNavigateUp = onNavigateUp,
        onExport = viewModel::requestExport,
        onImport = { openDocument.launch(arrayOf("*/*")) },
        onOpenManualEntry = viewModel::openManualEntry,
        onToggleExportSelection = viewModel::toggleExportSelection,
        onSubmitExport = { pass, confirm ->
            viewModel.submitExportPassphrase(pass, confirm, SystemClock.elapsedRealtime())
        },
        onSubmitImport = viewModel::submitImportPassphrase,
        onSubmitManualKey = viewModel::submitManualKey,
        onConfirmOverwriteManual = viewModel::confirmOverwriteManual,
        onCancelOverwriteManual = viewModel::cancelOverwriteManual,
        onConfirmImport = viewModel::confirmImport,
        onDismiss = viewModel::dismissDialog,
    )
}

private fun noticeText(context: Context, notice: BondNotice): String = when (notice) {
    is BondNotice.ExportDone -> context.getString(R.string.bond_export_done, notice.count)
    is BondNotice.ImportDone ->
        context.getString(R.string.bond_import_done, notice.imported, notice.overwritten, notice.kept)
    is BondNotice.ManualKeyAdded -> context.getString(R.string.bond_manual_added, notice.maskedMac)
    BondNotice.InvalidFile -> context.getString(R.string.bond_invalid_file)
    BondNotice.IoFailed -> context.getString(R.string.bond_io_failed)
    BondNotice.ReauthUnavailable -> context.getString(R.string.bond_reauth_unavailable)
    BondNotice.ReauthExpired -> context.getString(R.string.bond_reauth_expired)
}

/** Blocks screenshots, screen recording and the recents thumbnail while this screen is shown. */
@Composable
private fun SecureWindowEffect() {
    val window = LocalContext.current.findActivity()?.window
    DisposableEffect(window) {
        val alreadySecure = window != null &&
            window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!alreadySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BondBackupScreen(
    state: BondUiState,
    onNavigateUp: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onOpenManualEntry: () -> Unit,
    onToggleExportSelection: (String) -> Unit,
    onSubmitExport: (CharArray, CharArray) -> Unit,
    onSubmitImport: (CharArray) -> Unit,
    onSubmitManualKey: (String, CharArray, String, BondFamily) -> Unit,
    onConfirmOverwriteManual: () -> Unit,
    onCancelOverwriteManual: () -> Unit,
    onConfirmImport: (Set<Int>, Set<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bond_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(stringResource(R.string.bond_warning), Modifier.padding(16.dp))
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                state.loadFailed -> Text(stringResource(R.string.bond_load_failed))
                state.rows.isEmpty() -> Text(stringResource(R.string.bond_empty))
                else -> state.rows.forEach { row ->
                    ListItem(
                        leadingContent = {
                            Checkbox(
                                checked = row.mac in state.selectedExportMacs,
                                onCheckedChange = { onToggleExportSelection(row.mac) },
                                enabled = !state.busy,
                            )
                        },
                        headlineContent = { Text(row.label.ifBlank { row.model ?: row.maskedMac }) },
                        supportingContent = {
                            val details = listOfNotNull(
                                familyName(row.family),
                                row.model?.takeIf { it.isNotBlank() },
                                row.maskedMac,
                            ).joinToString(" · ")
                            Text(details)
                        },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onExport, enabled = state.selectedExportMacs.isNotEmpty() && !state.busy) {
                    Text(stringResource(R.string.bond_export))
                }
                OutlinedButton(onClick = onImport, enabled = !state.busy) {
                    Text(stringResource(R.string.bond_import))
                }
            }
            OutlinedButton(onClick = onOpenManualEntry, enabled = !state.busy) {
                Text(stringResource(R.string.bond_manual_button))
            }
        }
    }
    when (val dialog = state.dialog) {
        BondDialog.None -> Unit
        BondDialog.ExportPassphrase -> PassphraseDialog(
            title = stringResource(R.string.bond_export_dialog_title),
            confirmField = true,
            error = state.passphraseError,
            onSubmit = { pass, confirm -> onSubmitExport(pass, confirm) },
            onDismiss = onDismiss,
        )
        BondDialog.ImportPassphrase -> PassphraseDialog(
            title = stringResource(R.string.bond_import_dialog_title),
            confirmField = false,
            error = state.passphraseError,
            onSubmit = { pass, _ -> onSubmitImport(pass) },
            onDismiss = onDismiss,
        )
        is BondDialog.ImportPreview -> ImportPreviewDialog(dialog, onConfirmImport, onDismiss)
        BondDialog.ManualEntry -> ManualEntryDialog(
            error = state.manualEntryError,
            onSubmit = onSubmitManualKey,
            onDismiss = onDismiss,
        )
        is BondDialog.ConfirmOverwriteManual -> ConfirmOverwriteDialog(
            maskedMac = dialog.entry.maskedMac(),
            busy = state.busy,
            onConfirm = onConfirmOverwriteManual,
            onDismiss = onCancelOverwriteManual,
        )
    }
}


