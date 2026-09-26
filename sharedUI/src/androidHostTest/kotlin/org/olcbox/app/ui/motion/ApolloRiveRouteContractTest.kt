package org.olcbox.app.ui.motion

import app.rive.runtime.kotlin.core.ViewModel
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApolloRiveRouteContractTest {
    @Test
    fun rejectsNonNumericOrMissingPhaseProperty() {
        assertFalse(
            hasNumericApolloRoutePhase(
                listOf(ViewModel.Property(ViewModel.PropertyDataType.STRING, "phase"))
            )
        )
        assertFalse(
            hasNumericApolloRoutePhase(
                listOf(ViewModel.Property(ViewModel.PropertyDataType.NUMBER, "other"))
            )
        )
    }

    @Test
    fun acceptsNumericPhase() {
        assertTrue(
            hasNumericApolloRoutePhase(
                listOf(ViewModel.Property(ViewModel.PropertyDataType.NUMBER, "phase"))
            )
        )
    }
}
