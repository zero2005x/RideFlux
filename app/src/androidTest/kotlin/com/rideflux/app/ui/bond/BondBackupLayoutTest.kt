package com.rideflux.app.ui.bond

import android.content.res.Configuration
import android.os.LocaleList
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.rideflux.app.ui.theme.RideFluxTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.rideflux.app.R
import com.rideflux.data.preferences.AppLanguage
import com.rideflux.domain.bond.BondEntry
import com.rideflux.domain.bond.BondFamily
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic UI-only rows: no BondStore, real credentials, screenshots or recordings. */
class BondBackupLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val locale = mutableStateOf("en")
    private val scale = mutableStateOf(1f)
    private val ui = mutableStateOf(BondUiState())
    private val notice = mutableStateOf<BondNotice?>(null)
    private var confirmed = 0
    private var cancelled = 0
    private val names = listOf("中英 RideFlux 🚲", "日本語スクーター 한국어", "Véhicule de démonstration très long 🚲",
        "سكوتر تجريبي 🚲", "🚲".repeat(64), "")
    private val rows = names.mapIndexed { i, name -> BondRow("AA:BB:CC:DD:00:%02X".format(i),
        "••:••:••:••:00:%02X".format(i), name, if (i % 2 == 0) BondFamily.XIAOMI_MI else BondFamily.NINEBOT_CRYPTO) }

    private fun text(id: Int, vararg args: Any): String {
        val config = Configuration(compose.activity.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(locale.value))
        return compose.activity.createConfigurationContext(config).getString(id, *args)
    }

    private fun assertTextFits(id: Int) = assertTextFits(text(id))

    private fun assertTextFits(expected: String) {
        val node = compose.onNodeWithText(expected).performScrollTo().assertIsDisplayed().fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
        assertTrue("Text truncated: ${locale.value}/${scale.value}; " + layouts.joinToString {
            "size=${it.size}, lines=${it.lineCount}, widthOverflow=${it.didOverflowWidth}, heightOverflow=${it.didOverflowHeight}, constraints=${it.layoutInput.constraints}, lastBottom=${it.getLineBottom(it.lineCount - 1)}, paragraphWidth=${it.multiParagraph.width}, lastRight=${it.getLineRight(it.lineCount - 1)}, end=${it.getLineEnd(it.lineCount - 1)}, textLength=${it.layoutInput.text.length}"
        }, layouts.all { result ->
            !result.hasVisualOverflow && !result.isLineEllipsized(result.lineCount - 1) &&
                result.getLineEnd(result.lineCount - 1) == result.layoutInput.text.length
        })
    }

    private fun show(state: BondUiState) {
        compose.runOnIdle { ui.value = state }
        compose.waitForIdle()
    }

    private fun actionsReachable() {
        // IME presence precedes its resize animation. Wait for stable native bounds
        // before scrolling; a focused field can otherwise move the container back.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var previousTop = -1
        var stable = 0
        repeat(20) {
            val keyboard = android.graphics.Rect()
            val window = automation.windows.firstOrNull {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
            }
            window?.getBoundsInScreen(keyboard)
            val top = if (window == null) Int.MAX_VALUE else keyboard.top
            stable = if (top == previousTop) stable + 1 else 0
            previousTop = top
            if (stable < 3) android.os.SystemClock.sleep(100)
        }
        compose.waitForIdle()
        val confirmId = when (ui.value.dialog) {
            BondDialog.ExportPassphrase -> R.string.action_export
            BondDialog.ImportPassphrase, is BondDialog.ImportPreview -> R.string.action_import
            BondDialog.ManualEntry -> R.string.action_add
            is BondDialog.ConfirmOverwriteManual -> R.string.action_replace
            is BondDialog.ConfirmDelete -> R.string.bond_delete_action
            else -> error("No dialog")
        }
        val confirm = compose.onNodeWithText(text(confirmId)).performScrollTo()
        assertTrue("Confirm unreachable: ${locale.value}/${scale.value}, dialog=${ui.value.dialog::class.simpleName}, " +
            "bounds=${confirm.fetchSemanticsNode().boundsInWindow}, imeTop=$previousTop", confirm.isDisplayed())
        val cancel = compose.onNodeWithText(text(R.string.action_cancel)).performScrollTo().assertIsDisplayed()
        // Use actual viewport bounds; accessibility nodes can exist behind the IME.
        val bounds = cancel.fetchSemanticsNode().boundsInWindow
        val ime = automation.windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        if (ime != null) {
            val keyboard = android.graphics.Rect()
            ime.getBoundsInScreen(keyboard)
            assertTrue("Cancel intersects keyboard", bounds.bottom <= keyboard.top)
        }
    }

    @Test fun allSupportedLocalesAndFontScalesAtDeviceWidth() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        compose.activityRule.scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        compose.setContent {
            val tag by locale
            val fontScale by scale
            val state by ui
            val context = LocalContext.current
            val config = Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) }
            val localized = context.createConfigurationContext(config)
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides localized, LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if (tag in listOf("ar", "ur")) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                RideFluxTheme {
                    BondBackupScreen(state, {}, {}, {}, {}, {},
                        { pass, confirm -> pass.fill('\u0000'); confirm.fill('\u0000'); ui.value = state.copy(dialog = BondDialog.None) },
                        { pass -> pass.fill('\u0000'); ui.value = state.copy(dialog = BondDialog.None) },
                        { _, key, _, _ -> key.fill('\u0000'); ui.value = state.copy(dialog = BondDialog.None) },
                        { ui.value = state.copy(dialog = BondDialog.None) }, {}, { _, _ -> },
                        { mac -> ui.value = state.copy(dialog = BondDialog.ConfirmDelete(rows.first { it.mac == mac })) },
                        { confirmed++ }, { cancelled++; ui.value = state.copy(dialog = BondDialog.None) },
                        notice = notice.value?.let { noticeText(LocalContext.current, it) })
                }
            }
        }
        val args = InstrumentationRegistry.getArguments()
        val scales = args.getString("qaFontScale")?.toFloat()?.let { listOf(it) } ?: listOf(1f, 1.3f, 2f)
        val tags = args.getString("qaLocale")?.let { listOf(it) } ?: AppLanguage.supportedTags
        require(tags.all { it in AppLanguage.supportedTags } && scales.all { it in listOf(1f, 1.3f, 2f) })
        for (tag in tags) for (fontScale in scales) {
            compose.runOnIdle { locale.value = tag; scale.value = fontScale; notice.value = null }
            show(BondUiState(rows = rows, selectedExportMacs = rows.map { it.mac }.toSet()))
            assertTextFits(R.string.bond_warning)
            for (row in rows) {
                val delete = compose.onNodeWithTag("bond_delete_${row.maskedMac}")
                delete.performScrollTo().assertIsDisplayed()
                delete.assertContentDescriptionEquals(text(R.string.bond_delete_description, row.label, isolateMac(row.maskedMac)))
                val icon = delete.fetchSemanticsNode().boundsInRoot
                val summary = compose.onNodeWithTag("bond_summary_${row.maskedMac}").fetchSemanticsNode().boundsInRoot
                assertTrue("Summary and delete overlap: $tag/$fontScale", !icon.overlaps(summary))
                assertTrue("Delete does not mirror: $tag", if (tag in listOf("ar", "ur")) icon.right <= summary.left else icon.left >= summary.right)
                val px = compose.activity.resources.displayMetrics.density * 48
                assertTrue(icon.width >= px - 1 && icon.height >= px - 1)
            }
            compose.onNodeWithText(text(R.string.bond_manual_button)).performScrollTo().assertIsDisplayed()
            show(BondUiState(rows = rows, dialog = BondDialog.ConfirmDelete(rows[4]), deleteFailed = true))
            assertTextFits(R.string.bond_delete_failed)
            assertTextFits(R.string.bond_delete_message)
            actionsReachable()
            compose.onNodeWithText(text(R.string.action_cancel)).performClick()
            assertEquals(BondDialog.None, ui.value.dialog)
            show(BondUiState(dialog = BondDialog.ExportPassphrase, passphraseError = BondPassphraseError.MISMATCH))
            actionsReachable()
            compose.onNodeWithText(text(R.string.bond_error_mismatch)).performScrollTo().assertIsDisplayed()
            assertTextFits(R.string.bond_error_mismatch)
            show(BondUiState(dialog = BondDialog.ExportPassphrase, passphraseError = BondPassphraseError.WEAK))
            assertTextFits(R.string.bond_error_weak)
            compose.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextInput("layout-testing-only")
            compose.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextInput("layout-testing-only")
            compose.waitUntil(5_000) { automation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
            actionsReachable()
            compose.onNodeWithText(text(R.string.action_export)).performScrollTo().performClick()
            compose.runOnIdle { assertEquals(BondDialog.None, ui.value.dialog) }
            show(BondUiState(dialog = BondDialog.ImportPassphrase, passphraseError = BondPassphraseError.WRONG_OR_CORRUPT))
            assertTextFits(R.string.bond_error_wrong)
            compose.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextInput("layout-testing-only")
            compose.waitUntil(5_000) { automation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
            actionsReachable()
            compose.onNodeWithText(text(R.string.action_import)).performScrollTo().performClick()
            compose.runOnIdle { assertEquals(BondDialog.None, ui.value.dialog) }
            show(BondUiState(dialog = BondDialog.ImportPreview(rows.mapIndexed { i, r -> BondImportRow(i, r.maskedMac,
                r.label, r.family, conflict = true) }, 3)))
            actionsReachable()
            val fake = BondEntry(rows[0].mac, BondFamily.XIAOMI_MI, ByteArray(12), rows[0].label)
            show(BondUiState(dialog = BondDialog.ConfirmOverwriteManual(fake)))
            actionsReachable()
            fake.wipe()
            show(BondUiState(dialog = BondDialog.ManualEntry, manualEntryError = BondManualEntryError.INVALID))
            compose.onNodeWithTag("bond_mac").performScrollTo().performClick().performTextInput("AA:BB:CC:DD:EE:FF")
            compose.waitUntil(5_000) { automation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
            actionsReachable()
            compose.onNodeWithText(text(R.string.action_cancel)).performClick()
            compose.runOnIdle { assertEquals(BondDialog.None, ui.value.dialog) }
            show(BondUiState(dialog = BondDialog.ManualEntry))
            compose.onNodeWithTag("bond_mac").performScrollTo().performTextInput("AA:BB:CC:DD:EE:FF")
            compose.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextInput("00112233445566778899aabb")
            compose.onNodeWithTag("bond_label").performScrollTo().performTextInput(names[4])
            actionsReachable()
            compose.onNodeWithText(text(R.string.action_add)).performScrollTo().assertIsEnabled().performClick()
            compose.runOnIdle { assertEquals(BondDialog.None, ui.value.dialog) }
            show(BondUiState())
            compose.onNodeWithText(text(R.string.bond_empty)).performScrollTo().assertIsDisplayed()
            show(BondUiState(loadFailed = true))
            compose.onNodeWithText(text(R.string.bond_load_failed)).performScrollTo().assertIsDisplayed()
            compose.runOnIdle { notice.value = BondNotice.IoFailed }
            assertTextFits(R.string.bond_io_failed)
            for (count in listOf(0, 1, 2, 3, 11, 21)) {
                compose.runOnIdle { notice.value = BondNotice.ExportDone(count) }
                val config = Configuration(compose.activity.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) }
                val context = compose.activity.createConfigurationContext(config)
                val expected = context.resources.getQuantityString(R.plurals.bond_export_done, count, count)
                assertTextFits(expected)
            }
            assertEquals(0, confirmed)
            assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            android.util.Log.i("BondLayoutQA", "PASS locale=$tag fontScale=$fontScale widthDp=${compose.activity.resources.configuration.screenWidthDp}")
        }
    }
}
