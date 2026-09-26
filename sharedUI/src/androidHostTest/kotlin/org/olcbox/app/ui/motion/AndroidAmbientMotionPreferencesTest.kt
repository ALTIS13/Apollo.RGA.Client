package org.olcbox.app.ui.motion

import android.content.Context
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AndroidAmbientMotionPreferencesTest {
    @Test
    fun freshInstallAllowsDecorativeMotion() {
        val context = RuntimeEnvironment.getApplication() as Context

        assertTrue(AndroidAmbientMotionPreferences(context).userEnabled)
    }

    @Test
    fun userChoiceSurvivesStoreRecreation() {
        val context = RuntimeEnvironment.getApplication() as Context
        AndroidAmbientMotionPreferences(context).setUserEnabled(false)

        assertFalse(AndroidAmbientMotionPreferences(context).userEnabled)

        AndroidAmbientMotionPreferences(context).setUserEnabled(true)
        assertTrue(AndroidAmbientMotionPreferences(context).userEnabled)
    }

    @Test
    fun systemOverrideStopsMotionWithoutChangingUserChoice() {
        val context = RuntimeEnvironment.getApplication() as Context
        val store = AndroidAmbientMotionPreferences(context)
        store.setUserEnabled(true)

        assertFalse(AndroidAmbientMotionPreferences.effective(store.userEnabled, systemEnabled = false))
        assertTrue(AndroidAmbientMotionPreferences.effective(store.userEnabled, systemEnabled = true))
        assertTrue(AndroidAmbientMotionPreferences(context).userEnabled)
    }

    @Test
    fun userOptOutStopsMotionEvenWhenAndroidAllowsIt() {
        assertFalse(AndroidAmbientMotionPreferences.effective(userEnabled = false, systemEnabled = true))
    }
}
