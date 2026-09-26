package org.olcbox.app.net

import android.content.pm.ApplicationInfo
import org.junit.Test
import kotlin.test.assertEquals

class AndroidRuntimeLocationPolicyTest {
    @Test fun installedAppDebuggableFlagIsTheOnlyExperimentalOptIn() {
        assertEquals(RuntimeLocationPolicy.OrdinaryOnly, AndroidRuntimeLocationPolicy.fromFlags(0))
        assertEquals(RuntimeLocationPolicy.ExperimentalDebug,
            AndroidRuntimeLocationPolicy.fromFlags(ApplicationInfo.FLAG_DEBUGGABLE))
        assertEquals(RuntimeLocationPolicy.OrdinaryOnly,
            AndroidRuntimeLocationPolicy.fromFlags(ApplicationInfo.FLAG_TEST_ONLY))
    }
}
