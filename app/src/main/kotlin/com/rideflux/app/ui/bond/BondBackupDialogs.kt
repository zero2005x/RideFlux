/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.bond

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.rideflux.app.R
import com.rideflux.domain.bond.BondFamily

@Composable
internal fun PassphraseDialog(
    title: String,
    confirmField: Boolean,
    error: BondPassphraseError?,
    onSubmit: (CharArray, CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (confirmField) Text(stringResource(R.string.bond_passphrase_hint))
                OutlinedTextField(
                    value = passphrase, onValueChange = { passphrase = it }, singleLine = true,
                    label = { Text(stringResource(R.string.bond_passphrase)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (confirmField) {
                    OutlinedTextField(
                        value = confirm, onValueChange = { confirm = it }, singleLine = true,
                        label = { Text(stringResource(R.string.bond_passphrase_confirm)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
                error?.let {
                    Text(stringResource(errorText(it)), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = passphrase.isNotEmpty(),
                onClick = {
                    val p = passphrase.toCharArray()
                    val c = confirm.toCharArray()
                    passphrase = ""
                    confirm = ""
                    onSubmit(p, c)
                },
            ) { Text(stringResource(if (confirmField) R.string.action_export else R.string.action_import)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
internal fun ImportPreviewDialog(
    dialog: BondDialog.ImportPreview,
    onConfirm: (Set<Int>, Set<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = remember { mutableStateListOf<Int>().apply { addAll(dialog.rows.map { it.index }) } }
    val replace = remember { mutableStateListOf<Int>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bond_preview_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                dialog.rows.forEach { row ->
                    Row {
                        Checkbox(
                            checked = row.index in selected,
                            onCheckedChange = { if (it) selected += row.index else selected -= row.index },
                        )
                        Column {
                            Text(row.label.ifBlank { row.model ?: row.maskedMac })
                            val details = listOfNotNull(
                                familyName(row.family),
                                row.model?.takeIf { it.isNotBlank() },
                                row.maskedMac,
                            ).joinToString(" · ")
                            Text(details, style = MaterialTheme.typography.bodySmall)
                            if (row.conflict) {
                                Row {
                                    Checkbox(
                                        checked = row.index in replace,
                                        onCheckedChange = { if (it) replace += row.index else replace -= row.index },
                                    )
                                    Text(stringResource(R.string.bond_preview_replace),
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                if (dialog.skippedUnsupported > 0) {
                    Text(stringResource(R.string.bond_preview_skipped, dialog.skippedUnsupported),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected.toSet(), replace.toSet()) }) {
                Text(stringResource(R.string.action_import))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

internal fun errorText(error: BondPassphraseError): Int = when (error) {
    BondPassphraseError.WEAK -> R.string.bond_error_weak
    BondPassphraseError.MISMATCH -> R.string.bond_error_mismatch
    BondPassphraseError.WRONG_OR_CORRUPT -> R.string.bond_error_wrong
}

@Composable
internal fun familyName(family: BondFamily): String = stringResource(
    when (family) {
        BondFamily.XIAOMI_MI -> R.string.bond_family_xiaomi_mi
        BondFamily.NINEBOT_CRYPTO -> R.string.bond_family_ninebot_crypto
    },
)

/**
 * Dialog for entering a pairing key manually.
 *
 * Platform note (Compose String allocation):
 * Jetpack Compose text fields (`OutlinedTextField`, `BasicTextField`) operate exclusively on
 * immutable [String] values. Entering characters into the text field creates temporary immutable
 * [String] instances on the managed heap that cannot be zeroed in place and must await JVM garbage
 * collection. The internal [tokenBuffer] and [CharArray] parameters provide best-effort zeroing
 * (`\u0000`) of mutable buffers upon submit, cancel, or composition disposal.
 */
@Composable
internal fun ManualEntryDialog(
    error: BondManualEntryError?,
    onSubmit: (String, CharArray, String, BondFamily) -> Unit,
    onDismiss: () -> Unit,
) {
    var family by remember { mutableStateOf(BondFamily.XIAOMI_MI) }
    var mac by remember { mutableStateOf("") }
    val tokenBuffer = remember { CharArray(96) }
    var tokenLength by remember { mutableIntStateOf(0) }
    var label by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        onDispose {
            tokenBuffer.fill('\u0000')
            tokenLength = 0
        }
    }

    val dismissAndWipe = {
        tokenBuffer.fill('\u0000')
        tokenLength = 0
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = dismissAndWipe,
        title = { Text(stringResource(R.string.bond_manual_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BondFamily.entries.forEach { f ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { family = f },
                        ) {
                            RadioButton(selected = family == f, onClick = { family = f })
                            Text(familyName(f), style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                }
                OutlinedTextField(
                    value = mac,
                    onValueChange = { mac = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.bond_manual_mac)) },
                    placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = String(tokenBuffer, 0, tokenLength),
                    onValueChange = { input ->
                        val count = input.length.coerceAtMost(tokenBuffer.size)
                        input.toCharArray(tokenBuffer, 0, 0, count)
                        tokenBuffer.fill('\u0000', count, tokenBuffer.size)
                        tokenLength = count
                    },
                    singleLine = true,
                    label = { Text(stringResource(R.string.bond_manual_token)) },
                    placeholder = {
                        Text(if (family == BondFamily.XIAOMI_MI) "00 11 22 … (12 bytes)" else "00 11 22 … (16 bytes)")
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.bond_manual_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(
                        stringResource(R.string.bond_manual_error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            val hasNonWhitespace = (0 until tokenLength).any { !tokenBuffer[it].isWhitespace() }
            TextButton(
                enabled = mac.isNotBlank() && tokenLength > 0 && hasNonWhitespace,
                onClick = {
                    val chars = tokenBuffer.copyOfRange(0, tokenLength)
                    tokenBuffer.fill('\u0000')
                    tokenLength = 0
                    onSubmit(mac, chars, label, family)
                },
            ) {
                Text(stringResource(R.string.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = dismissAndWipe) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
internal fun ConfirmOverwriteDialog(
    maskedMac: String,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.bond_overwrite_title)) },
        text = { Text(stringResource(R.string.bond_overwrite_message, maskedMac)) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text(stringResource(R.string.action_replace))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
