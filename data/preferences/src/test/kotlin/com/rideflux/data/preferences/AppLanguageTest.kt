package com.rideflux.data.preferences

import android.app.Activity
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class AppLanguageTest {
    @Test
    @Config(sdk = [28])
    fun selectedLanguagePersistsAndWrapsOnlyWhenOverridden() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val base = activity.applicationContext
        base.getSharedPreferences("rideflux_language", Context.MODE_PRIVATE).edit().clear().commit()

        assertEquals("", AppLanguage.selected(base))
        assertSame(base, AppLanguage.wrap(base))

        AppLanguage.set(activity, "zh-TW")
        assertEquals("zh-TW", AppLanguage.selected(base))
        val wrapped = AppLanguage.wrap(base)
        assertNotSame(base, wrapped)
        assertEquals("zh-TW", wrapped.resources.configuration.locales[0].toLanguageTag())

        AppLanguage.set(activity, "")
        assertEquals("", AppLanguage.selected(base))
        assertSame(base, AppLanguage.wrap(base))
    }

    @Test
    @Config(sdk = [28])
    fun unsupportedTagCannotBeStored() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertThrows(IllegalArgumentException::class.java) { AppLanguage.set(activity, "xx") }
        assertEquals(18, AppLanguage.supportedTags.size)
    }

    @Test
    @Config(sdk = [33])
    fun androidThirteenUsesSystemAppLocales() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        AppLanguage.set(activity, "ja")
        assertEquals("ja", AppLanguage.selected(activity))
        assertSame(activity, AppLanguage.wrap(activity))
        AppLanguage.set(activity, "")
        assertEquals("", AppLanguage.selected(activity))
    }
}
