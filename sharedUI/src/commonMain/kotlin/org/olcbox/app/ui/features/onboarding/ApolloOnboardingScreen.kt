package org.olcbox.app.ui.features.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import multiplatform_app.sharedui.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.olcbox.app.ui.ApolloOrbitMark

/** The Android first-run introduction; VPN permission stays in its separate disclosure. */
@Composable
fun ApolloOnboardingScreen(
    onFinished: () -> Unit,
    onAddServerList: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            ApolloOrbitMark(Modifier.size(76.dp))
            Text("Apollo.RGA", style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary)
            Text(stringResource(Res.string.apollo_onboarding_title), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(Res.string.apollo_onboarding_body), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(Res.string.apollo_onboarding_hint), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = { onFinished(); onAddServerList() },
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp)
            ) {
                Text(stringResource(Res.string.apollo_onboarding_import))
            }
            TextButton(onClick = onFinished,
                modifier = Modifier.align(Alignment.CenterHorizontally).defaultMinSize(minHeight = 48.dp)) {
                Text(stringResource(Res.string.apollo_onboarding_skip))
            }
        }
    }
}
