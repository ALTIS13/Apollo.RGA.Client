package org.olcbox.app.ui.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import multiplatform_app.sharedui.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.net.LocationKind
import org.olcbox.app.ui.HostPresentation
import org.olcbox.app.ui.HostPresenter
import org.olcbox.app.ui.ApolloOrbitMark
import org.olcbox.app.ui.SubscriptionDisplayState
import org.olcbox.app.ui.SubscriptionPresentation
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.util.formatByteSize
import org.olcbox.app.util.formatDate

/** Android's operating surface. It receives the repository's policy-filtered projection. */
internal enum class ApolloConnectionNotice { NONE, FAILED, CHECKING, BLOCKED }

@Composable
internal fun ApolloHomeContent(
    locations: List<LocationItem>,
    selectedLocationId: String?,
    subscription: SubscriptionPresentation,
    isConnected: Boolean,
    isConnecting: Boolean,
    canStartVpn: Boolean,
    isRefreshing: Boolean,
    showSettings: Boolean,
    onAction: () -> Unit,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    onLocationSelected: (String) -> Unit,
    snackbarHostState: SnackbarHostState,
    notice: ApolloConnectionNotice = ApolloConnectionNotice.NONE,
    onDismissNotice: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val ordinary = locations.mapNotNull { item ->
        val config = item.config ?: return@mapNotNull null
        if (config.kind == LocationKind.Olcrtc) return@mapNotNull null
        val entry = LocationEntry.from(item.storageId, config, item.subscriptionUrl, item.subscriptionOriginLink, item.metadata)
        HostPresenter.present(entry)?.let { item to it }
    }
    // Task 5 controls which modes are admitted. This is presentation grouping,
    // not a second security filter: an explicitly opted-in mode gets its own area.
    val experimental = locations.filter { it.config?.kind == LocationKind.Olcrtc }
    val selected = ordinary.firstOrNull { it.first.storageId == selectedLocationId }?.second
    val selectedExperimental = experimental.any { it.storageId == selectedLocationId }
    val statusTitle = when (subscription.state) {
        SubscriptionDisplayState.ACTIVE -> stringResource(Res.string.apollo_status_active)
        SubscriptionDisplayState.EXPIRING -> stringResource(Res.string.apollo_status_expiring)
        SubscriptionDisplayState.EXPIRED -> stringResource(Res.string.apollo_status_expired)
        SubscriptionDisplayState.LIMITED -> stringResource(Res.string.apollo_status_limited)
        SubscriptionDisplayState.UNKNOWN -> stringResource(Res.string.apollo_status_unknown)
        SubscriptionDisplayState.REFRESH_FAILED -> stringResource(Res.string.apollo_status_refresh_failed)
    }
    val connectionTitle = when {
        isConnected -> stringResource(Res.string.apollo_connected)
        isConnecting -> stringResource(Res.string.apollo_connecting)
        else -> stringResource(Res.string.apollo_disconnected)
    }
    val actionTitle = when {
        isConnected -> stringResource(Res.string.apollo_disconnect)
        isConnecting -> stringResource(Res.string.apollo_cancel)
        else -> stringResource(Res.string.apollo_connect)
    }
    val accessBlocked = subscription.state in setOf(SubscriptionDisplayState.EXPIRED, SubscriptionDisplayState.LIMITED)
    val actionEnabled = isConnected || isConnecting || (canStartVpn && !accessBlocked && selectedLocationId != null)
    val unknownCountry = stringResource(Res.string.apollo_country_unknown)
    val route = when {
        selected?.relay != null -> stringResource(Res.string.apollo_route_configured,
            localizedCountry(selected.country, unknownCountry))
        else -> stringResource(Res.string.apollo_route_unknown)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ApolloOrbitMark(Modifier.size(36.dp))
                Text(
                    text = "Apollo.RGA",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(start = 10.dp)
                )
                IconButton(onClick = onAdd, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Add, contentDescription = stringResource(Res.string.apollo_add))
                }
                if (showSettings) {
                    IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(Res.string.apollo_settings))
                    }
                }
            }
        },
        bottomBar = {
            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Button(
                    onClick = onAction,
                    enabled = actionEnabled,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp)
                        .semantics { testTag = "apollo-action" },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(actionTitle, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(connectionTitle, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(
                        statusTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { testTag = "apollo-subscription-status" }
                    )
                    if (notice != ApolloConnectionNotice.NONE) {
                        val noticeText = when (notice) {
                            ApolloConnectionNotice.FAILED -> stringResource(Res.string.apollo_connection_failed)
                            ApolloConnectionNotice.CHECKING -> stringResource(Res.string.apollo_connection_checking)
                            ApolloConnectionNotice.BLOCKED -> stringResource(Res.string.apollo_connection_blocked)
                            ApolloConnectionNotice.NONE -> ""
                        }
                        Text(noticeText, style = MaterialTheme.typography.bodyMedium,
                            color = if (notice == ApolloConnectionNotice.FAILED) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (notice == ApolloConnectionNotice.FAILED) {
                            TextButton(onClick = onDismissNotice, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                                Text(stringResource(Res.string.apollo_dismiss))
                            }
                        }
                    }
                    subscription.expiresAtEpochMs?.let {
                        Text(stringResource(Res.string.apollo_expires, formatDate(it)),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    subscription.remainingBytes?.let {
                        Text(stringResource(Res.string.apollo_remaining, formatByteSize(it)),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    if (subscription.state == SubscriptionDisplayState.REFRESH_FAILED) {
                        Text(stringResource(Res.string.apollo_status_retry), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (selected != null) {
                        Text(stringResource(Res.string.apollo_selected), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(destination(selected), style = MaterialTheme.typography.titleLarge)
                        Text(route, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (selectedExperimental) {
                        Text(stringResource(Res.string.apollo_experimental), style = MaterialTheme.typography.titleLarge)
                        Text(route, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(stringResource(Res.string.apollo_no_destination), style = MaterialTheme.typography.bodyLarge)
                    }
                    if (locations.any { !it.subscriptionUrl.isNullOrBlank() }) {
                        TextButton(onClick = onRefresh, enabled = !isRefreshing,
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                            Text(stringResource(Res.string.apollo_refresh))
                        }
                    }
                }
            }

            Text(stringResource(Res.string.apollo_destinations), style = MaterialTheme.typography.titleLarge)
            if (ordinary.isEmpty()) {
                Text(stringResource(Res.string.apollo_no_hosts), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onAdd, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                    Text(stringResource(Res.string.apollo_add))
                }
            } else {
                Column {
                    ordinary.forEachIndexed { index, (item, host) ->
                        ApolloHostRow(
                            host = host,
                            selected = item.storageId == selectedLocationId,
                            onClick = { onLocationSelected(item.storageId) },
                            modifier = Modifier.semantics { testTag = "apollo-host-$index" }
                        )
                        if (index != ordinary.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }

            if (experimental.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(stringResource(Res.string.apollo_experimental), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(Res.string.apollo_experimental_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                experimental.forEachIndexed { index, item ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp)
                            .selectable(selected = item.storageId == selectedLocationId,
                                role = Role.RadioButton, onClick = { onLocationSelected(item.storageId) }),
                        color = if (item.storageId == selectedLocationId) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Text(stringResource(Res.string.apollo_experimental_node, index + 1),
                            modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

private fun localizedCountry(country: String, fallback: String): String =
    if (country == "Страна не указана") fallback else country

@Composable
private fun destination(host: HostPresentation): String = listOf(
    host.flag, localizedCountry(host.country, stringResource(Res.string.apollo_country_unknown)))
    .filter(String::isNotBlank).joinToString(" ")

@Composable
private fun ApolloHostRow(
    host: HostPresentation,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 72.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val country = localizedCountry(host.country, stringResource(Res.string.apollo_country_unknown))
        val name = if (host.name == "Хост без названия") stringResource(Res.string.apollo_host_unknown)
            else host.name
        val protocol = if (host.protocol == "Протокол не указан")
            stringResource(Res.string.apollo_protocol_unknown) else host.protocol
        val relay = host.relay?.let { stringResource(Res.string.apollo_via_ru_relay) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(destination(host), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (name != country) {
                Text(name, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(listOfNotNull(protocol, relay).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            host.purpose?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        RadioButton(selected = selected, onClick = null, modifier = Modifier.size(48.dp))
    }
}
