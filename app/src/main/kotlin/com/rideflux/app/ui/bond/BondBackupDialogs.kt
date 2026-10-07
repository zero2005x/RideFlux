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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.rideflux.app.R
import com.rideflux.domain.bond.BondFamily

@Composable
internal fun PassphraseDialog(
    title: String,
    busy: Boolean,
    confirmField: Boolean,
    error: BondPassphraseError?,
    onSubmit: (CharArray, CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    DisposableEffect(Unit) {
        onDispose { passphrase = ""; confirm = "" }
    }
    BondAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (confirmField) Text(stringResource(R.string.bond_passphrase_hint), Modifier.fillMaxWidth())
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(), enabled = !busy,
                    value = passphrase, onValueChange = { passphrase = it }, singleLine = true,
                    label = { Text(stringResource(R.string.bond_passphrase)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (confirmField) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(), enabled = !busy,
                        value = confirm, onValueChange = { confirm = it }, singleLine = true,
                        label = { Text(stringResource(R.string.bond_passphrase_confirm)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
                error?.let {
                    Text(stringResource(errorText(it)), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.fillMaxWidth(),
                enabled = !busy && passphrase.isNotEmpty(),
                onClick = {
                    val p = passphrase.toCharArray()
                    val c = confirm.toCharArray()
                    passphrase = ""
                    confirm = ""
                    onSubmit(p, c)
                },
            ) { Text(stringResource(if (confirmField) R.string.action_export else R.string.action_import)) }
        },
        dismissButton = { TextButton(modifier = Modifier.fillMaxWidth(), onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
internal fun ImportPreviewDialog(
    dialog: BondDialog.ImportPreview,
    busy: Boolean,
    onConfirm: (Set<Int>, Set<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = remember(dialog) { mutableStateListOf<Int>().apply { addAll(dialog.rows.map { it.index }) } }
    val replace = remember(dialog) { mutableStateListOf<Int>() }
    BondAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.bond_preview_title)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                dialog.rows.forEach { row ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = row.index in selected, enabled = !busy,
                            onCheckedChange = { if (it) selected += row.index else selected -= row.index },
                        )
                        Column(Modifier.weight(1f)) {
                            BondSummary(row.label, row.model, row.maskedMac, row.family)
                            if (row.conflict) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = row.index in replace, enabled = !busy && row.index in selected,
                                        onCheckedChange = { if (it) replace += row.index else replace -= row.index },
                                    )
                                    Text(stringResource(R.string.bond_preview_replace),
                                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
                if (dialog.skippedUnsupported > 0) {
                    Text(pluralStringResource(R.plurals.bond_preview_skipped, dialog.skippedUnsupported, dialog.skippedUnsupported), modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.fillMaxWidth(), enabled = !busy && selected.isNotEmpty(), onClick = { onConfirm(selected.toSet(), replace.toSet()) }) {
                Text(stringResource(R.string.action_import))
            }
        },
        dismissButton = { TextButton(modifier = Modifier.fillMaxWidth(), onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } },
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
    busy: Boolean,
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

    BondAlertDialog(
        onDismissRequest = { if (!busy) dismissAndWipe() },
        title = { Text(stringResource(R.string.bond_manual_title)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    BondFamily.entries.forEach { f ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { family = f },
                        ) {
                            RadioButton(selected = family == f, enabled = !busy, onClick = { family = f })
                            Text(familyName(f), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                }
                OutlinedTextField(
                    value = mac,
                    onValueChange = { mac = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.bond_manual_mac)) },
                    placeholder = { Text(stringResource(R.string.bond_mac_example)) },
                    textStyle = ltrTextStyle(), enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth().testTag("bond_mac"),
                )
                OutlinedTextField(
                    value = String(tokenBuffer, 0, tokenLength),
                    textStyle = ltrTextStyle(), enabled = !busy,
                    onValueChange = { input ->
                        val count = input.length.coerceAtMost(tokenBuffer.size)
                        input.toCharArray(tokenBuffer, 0, 0, count)
                        tokenBuffer.fill('\u0000', count, tokenBuffer.size)
                        tokenLength = count
                    },
                    singleLine = true,
                    label = { Text(stringResource(R.string.bond_manual_token)) },
                    placeholder = {
                        Text(stringResource(R.string.bond_hex_hint, family.credentialBytes))
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = label, enabled = !busy,
                    onValueChange = { label = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.bond_manual_label)) },
                    modifier = Modifier.fillMaxWidth().testTag("bond_label"),
                )
                if (error != null) {
                    Text(
                        stringResource(R.string.bond_manual_error), modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            val hasNonWhitespace = (0 until tokenLength).any { !tokenBuffer[it].isWhitespace() }
            TextButton(modifier = Modifier.fillMaxWidth(),
                enabled = !busy && mac.isNotBlank() && tokenLength > 0 && hasNonWhitespace,
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
            TextButton(modifier = Modifier.fillMaxWidth(), onClick = dismissAndWipe, enabled = !busy) {
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
    BondAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.bond_overwrite_title)) },
        text = { Text(stringResource(R.string.bond_overwrite_message, isolateMac(maskedMac)), Modifier.fillMaxWidth()) },
        confirmButton = {
            TextButton(modifier = Modifier.fillMaxWidth(), onClick = onConfirm, enabled = !busy) {
                Text(stringResource(R.string.action_replace))
            }
        },
        dismissButton = {
            TextButton(modifier = Modifier.fillMaxWidth(), onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** Isolate machine identifiers when interpolating them into RTL prose. */
internal fun isolateMac(mac: String) = "\u2066$mac\u2069"

@Composable
private fun ltrTextStyle(): TextStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr)

@Composable
internal fun BondSummary(
    label: String, model: String?, maskedMac: String, family: BondFamily,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val name = label.ifBlank { model?.takeIf(String::isNotBlank) ?: maskedMac }
        val nameStyle = if (name == maskedMac) MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Ltr)
            else MaterialTheme.typography.titleMedium
        Text(name, style = nameStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(maskedMac, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr))
        }
        Text(familyName(family), style = MaterialTheme.typography.labelMedium)
        model?.takeIf { it.isNotBlank() && it != label }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun ConfirmDeleteDialog(
    row: BondRow, busy: Boolean, failed: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit,
) {
    BondAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.bond_delete_title)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BondSummary(row.label, row.model, row.maskedMac, row.family)
                Text(stringResource(R.string.bond_delete_message), Modifier.fillMaxWidth())
                if (failed) Text(stringResource(R.string.bond_delete_failed), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.fillMaxWidth(), onClick = onConfirm, enabled = !busy) { Text(stringResource(R.string.bond_delete_action)) }
        },
        dismissButton = {
            TextButton(modifier = Modifier.fillMaxWidth(), onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** One scroll container includes actions, so even a large font and IME cannot strand them. */
@Composable
private fun BondAlertDialog(
    onDismissRequest: () -> Unit, title: @Composable () -> Unit, text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit, dismissButton: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
        securePolicy = SecureFlagPolicy.SecureOn,
    )) {
        CompositionLocalProvider(LocalContext provides context, LocalDensity provides density,
            LocalLayoutDirection provides direction) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp), contentAlignment = Alignment.Center) {
                Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.headlineSmall) { title() }
                        text()
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                            confirmButton()
                            dismissButton()
                        }
                    }
                }
            }
        }
    }
}
