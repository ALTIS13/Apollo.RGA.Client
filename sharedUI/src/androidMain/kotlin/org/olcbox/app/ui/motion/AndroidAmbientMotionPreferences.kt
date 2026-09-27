package org.olcbox.app.ui.motion

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** User choice for decorative motion; connection-state feedback remains independent. */
class AndroidAmbientMotionPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private var enabledState by mutableStateOf(preferences.getBoolean(KEY_USER_ENABLED, true))
    val userEnabled: Boolean get() = enabledState

    fun setUserEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_USER_ENABLED, enabled).apply()
        enabledState = enabled
    }

    companion object {
        private const val PREFERENCES_NAME = "apollo_rga_ambient_motion"
        private const val KEY_USER_ENABLED = "ambient_motion_enabled"

        /** System accessibility/developer preference wins over the app toggle. */
        fun effective(userEnabled: Boolean, systemEnabled: Boolean): Boolean =
            userEnabled && systemEnabled

        fun systemAnimationsEnabled(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ValueAnimator.areAnimatorsEnabled()
            } else {
                Settings.Global.getFloat(
                    context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f
                ) > 0f
            }
    }
}
