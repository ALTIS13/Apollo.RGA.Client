package org.olcbox.app.ui.features.home

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
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
import org.olcbox.app.ui.theme.LocalPkPalette
import org.olcbox.app.ui.components.kit.pkScreenBackground

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
    val ordinary = remember(locations) {
        locations.mapNotNull { item ->
            val config = item.config ?: return@mapNotNull null
            if (config.kind == LocationKind.Olcrtc) return@mapNotNull null
            val entry = LocationEntry.from(item.storageId, config, item.subscriptionUrl, item.subscriptionOriginLink, item.metadata)
            HostPresenter.present(entry)?.let { item to it }
        }
    }
    // Task 5 controls which modes are admitted. This is presentation grouping,
    // not a second security filter: an explicitly opted-in mode gets its own area.
    val experimental = locations.filter { it.config?.kind == LocationKind.Olcrtc }
    val selectedPair = ordinary.firstOrNull { it.first.storageId == selectedLocationId }
    val selected = selectedPair?.second
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
    val palette = LocalPkPalette.current
    val signalColor by animateColorAsState(
        targetValue = when {
            notice == ApolloConnectionNotice.FAILED -> MaterialTheme.colorScheme.error
            isConnecting -> palette.warning
            isConnected -> palette.success
            else -> MaterialTheme.colorScheme.secondary
        },
        animationSpec = tween(240), label = "connection signal"
    )
    val heroColor by animateColorAsState(
        targetValue = if (isConnected) MaterialTheme.colorScheme.surfaceContainerHigh
            else MaterialTheme.colorScheme.surfaceContainer,
        animationSpec = tween(280), label = "connection surface"
    )

    Scaffold(
        modifier = modifier.fillMaxSize().then(pkScreenBackground()),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
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
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(color = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, signalColor.copy(alpha = if (isConnected) 0.62f else 0.34f)),
                modifier = Modifier.semantics { testTag = "apollo-hero" }) {
                Box(
                    Modifier.fillMaxWidth().background(
                        Brush.verticalGradient(
                            listOf(MaterialTheme.colorScheme.primaryContainer, heroColor, heroColor)
                        )
                    )
                ) {
                    ApolloHeroBackdrop(signalColor, Modifier.matchParentSize())
                Column(Modifier.fillMaxWidth().animateContentSize(animationSpec = tween(220)).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(connectionTitle, style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold, color = signalColor,
                            modifier = Modifier.weight(1f))
                        ApolloConnectionSignal(signalColor, isConnected, isConnecting)
                    }
                    if (selected != null) {
                        Text(destination(selected), style = MaterialTheme.typography.titleLarge)
                        Text(hostMode(selected, selectedPair.first.config?.kind),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.semantics { testTag = "apollo-selected-mode" })
                    } else if (selectedExperimental) {
                        Text(stringResource(Res.string.apollo_experimental), style = MaterialTheme.typography.titleLarge)
                    } else {
                        Text(stringResource(Res.string.apollo_no_destination), style = MaterialTheme.typography.bodyLarge)
                    }
                    if (accessBlocked) {
                        Text(statusTitle, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error)
                    }
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
                    Button(
                        onClick = onAction,
                        enabled = actionEnabled,
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp)
                            .semantics { testTag = "apollo-action" },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(actionTitle, style = MaterialTheme.typography.titleMedium)
                    }
                    if (selected != null || selectedExperimental) {
                        if (selected?.relay != null) {
                            ApolloRoutePath(route, signalColor, isConnected, isConnecting)
                        } else {
                            Text(route, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                }
            }

            val subscriptionColor = when (subscription.state) {
                SubscriptionDisplayState.ACTIVE -> palette.success
                SubscriptionDisplayState.EXPIRING, SubscriptionDisplayState.REFRESH_FAILED -> palette.warning
                SubscriptionDisplayState.EXPIRED, SubscriptionDisplayState.LIMITED -> MaterialTheme.colorScheme.error
                SubscriptionDisplayState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.semantics { testTag = "apollo-subscription-card" }) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Canvas(Modifier.size(8.dp)) { drawCircle(subscriptionColor) }
                            Text(statusTitle, style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold, color = subscriptionColor,
                                modifier = Modifier.semantics { testTag = "apollo-subscription-status" })
                        }
                        val summary = listOfNotNull(
                            subscription.expiresAtEpochMs?.let {
                                stringResource(Res.string.apollo_expires, formatDate(it))
                            },
                            subscription.remainingBytes?.let {
                                stringResource(Res.string.apollo_remaining, formatByteSize(it))
                            }
                        )
                        if (summary.isNotEmpty()) {
                            Text(summary.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (subscription.state == SubscriptionDisplayState.REFRESH_FAILED) {
                            Text(stringResource(Res.string.apollo_status_retry),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (locations.any { !it.subscriptionUrl.isNullOrBlank() }) {
                        IconButton(onClick = onRefresh, enabled = !isRefreshing,
                            modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.Refresh,
                                contentDescription = stringResource(Res.string.apollo_refresh),
                                tint = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }

            Text(stringResource(Res.string.apollo_destinations),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
            Text(stringResource(Res.string.apollo_single_connection_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (ordinary.isEmpty()) {
                Text(stringResource(Res.string.apollo_no_hosts), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onAdd, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                    Text(stringResource(Res.string.apollo_add))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ordinary.groupBy { it.second.country }.forEach { (country, hosts) ->
                        ApolloCountryHostCard(
                            countryTitle = destination(hosts.first().second),
                            choices = hosts.map { (item, host) ->
                                val protocol = if (host.protocol == "Протокол не указан")
                                    stringResource(Res.string.apollo_protocol_unknown) else host.protocol
                                val ingress = host.relay?.let {
                                    stringResource(Res.string.apollo_ingress_configured)
                                }
                                ApolloHostChoice(
                                    id = item.storageId,
                                    destination = destination(host),
                                    mode = hostMode(host, item.config?.kind),
                                    technical = listOfNotNull(protocol, ingress).joinToString(" · "),
                                    selected = item.storageId == selectedLocationId
                                )
                            },
                            onSelect = onLocationSelected,
                            modifier = Modifier.semantics { testTag = "apollo-group-$country" }
                        )
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

@Composable
private fun localizedCountry(country: String, fallback: String): String = when (country) {
    "Страна не указана" -> fallback
    "Netherlands" -> stringResource(Res.string.apollo_country_netherlands)
    "Finland" -> stringResource(Res.string.apollo_country_finland)
    "Germany" -> stringResource(Res.string.apollo_country_germany)
    "Singapore" -> stringResource(Res.string.apollo_country_singapore)
    else -> country
}

@Composable
private fun destination(host: HostPresentation): String = listOf(
    host.flag, localizedCountry(host.country, stringResource(Res.string.apollo_country_unknown)))
    .filter(String::isNotBlank).joinToString(" ")

@Composable
private fun hostMode(host: HostPresentation, kind: LocationKind?): String = when {
    kind == LocationKind.Hysteria2 -> stringResource(Res.string.apollo_mode_games)
    kind == LocationKind.Vless && host.protocol.contains("XHTTP") ->
        stringResource(Res.string.apollo_mode_sites)
    else -> stringResource(Res.string.apollo_mode_other)
}

/** A single arrival reveal; the orbital field is static after the first 600 ms. */
@Composable
private fun ApolloHeroBackdrop(color: Color, modifier: Modifier = Modifier) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, animationSpec = tween(600)) }
    Canvas(modifier) {
        val amount = reveal.value
        val orbitCenter = Offset(size.width * 0.88f, size.height * 0.13f)
        drawRect(
            Brush.radialGradient(
                listOf(color.copy(alpha = 0.17f * amount), Color.Transparent),
                center = orbitCenter, radius = size.width * 0.72f
            )
        )
        repeat(3) { ring ->
            drawCircle(
                color.copy(alpha = (0.13f - ring * 0.025f) * amount),
                radius = size.width * (0.20f + ring * 0.105f) * (0.86f + amount * 0.14f),
                center = orbitCenter,
                style = Stroke(width = 1.dp.toPx())
            )
        }
    }
}

/** The configured relay path lights up once during connection, then rests. */
@Composable
private fun ApolloRoutePath(
    description: String,
    color: Color,
    isConnected: Boolean,
    isConnecting: Boolean
) {
    val progress by animateFloatAsState(
        targetValue = when {
            isConnected -> 1f
            isConnecting -> 0.65f
            else -> 0f
        },
        animationSpec = tween(460),
        label = "configured route"
    )
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Canvas(Modifier.size(width = 52.dp, height = 18.dp)) {
            val y = size.height / 2f
            val left = 5.dp.toPx()
            val right = size.width - left
            drawLine(color.copy(alpha = 0.35f), Offset(left, y), Offset(right, y),
                strokeWidth = 2.dp.toPx())
            if (progress > 0f) {
                drawLine(color, Offset(left, y),
                    Offset(left + (right - left) * progress, y), strokeWidth = 2.dp.toPx())
            }
            listOf(left, size.width / 2f, right).forEach { x ->
                drawCircle(color.copy(alpha = if (x <= left + (right - left) * progress) 1f else 0.46f),
                    radius = 3.dp.toPx(), center = Offset(x, y))
            }
        }
        Text(description, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One short arrival pulse marks a real connection; only in-flight work spins. */
@Composable
private fun ApolloConnectionSignal(color: Color, isConnected: Boolean, isConnecting: Boolean) {
    val arrival = remember { Animatable(1f) }
    LaunchedEffect(isConnected) {
        if (isConnected) {
            arrival.snapTo(0f)
            arrival.animateTo(1f, animationSpec = tween(520))
        } else {
            arrival.snapTo(1f)
        }
    }
    androidx.compose.foundation.layout.Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(42.dp)) {
            val stroke = 2.dp.toPx()
            if (arrival.value < 1f) {
                drawCircle(
                    color.copy(alpha = (1f - arrival.value) * 0.5f),
                    radius = size.minDimension * (0.18f + arrival.value * 0.28f),
                    style = Stroke(width = stroke * (1.5f - arrival.value))
                )
            }
            drawCircle(color.copy(alpha = 0.11f), radius = size.minDimension * 0.46f)
            drawArc(color.copy(alpha = 0.88f), startAngle = -50f, sweepAngle = 280f,
                useCenter = false,
                topLeft = Offset(size.width * 0.17f, size.height * 0.17f),
                size = Size(size.width * 0.66f, size.height * 0.66f),
                style = Stroke(width = stroke * 1.3f))
            drawLine(color, start = Offset(size.width / 2f, size.height * 0.08f),
                end = Offset(size.width / 2f, size.height * 0.47f),
                strokeWidth = stroke * 1.3f)
        }
        if (isConnecting) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp), color = color,
                strokeWidth = 2.dp, trackColor = Color.Transparent
            )
        }
    }
}
