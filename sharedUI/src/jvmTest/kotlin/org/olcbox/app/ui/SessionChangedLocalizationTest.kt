package org.olcbox.app.ui

import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.olcbox.app.data.repository.SubscriptionRefreshError
import org.olcbox.app.data.repository.SubscriptionRefreshFailure
import org.olcbox.app.data.repository.SubscriptionRefreshReport
import org.olcbox.app.ui.features.home.localizedSingleMessage

class SessionChangedLocalizationTest {
    private val originalLocale = Locale.getDefault()
    @AfterTest fun restoreLocale() { Locale.setDefault(originalLocale) }

    @Test fun sessionChangedHasFixedDistinctPrivateSafeEnglishAndRussianMessages() = runBlocking {
        val failure = SubscriptionRefreshFailure("https://example.test/private-test-token",
            SubscriptionRefreshError.SessionChanged, 599)
        val session = SubscriptionRefreshReport(failures = listOf(failure))
        val network = SubscriptionRefreshReport(failures = listOf(failure.copy(error = SubscriptionRefreshError.Unreachable)))
        for ((locale, expected) in listOf(
            "en" to "Connection changed. Refresh the server list again.",
            "ru" to "Подключение изменилось. Повторите обновление списка."
        )) {
            Locale.setDefault(Locale.forLanguageTag(locale))
            val message = session.localizedSingleMessage()
            assertEquals(expected, message)
            assertFalse(message == network.localizedSingleMessage())
            assertFalse(message.contains("private-test-token"))
            assertFalse(message.contains("https://"))
            assertFalse(message.contains("599"))
        }
    }
}
