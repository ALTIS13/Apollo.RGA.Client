package org.olcbox.app.ui.features.home

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Android's operating surface. It receives the repository's policy-filtered projection. */
internal enum class ApolloConnectionNotice { NONE, FAILED, CHECKING, BLOCKED }
enum class ApolloHeroPhase { IDLE, CONNECTING, CONNECTED, ERROR }
private class OrbitCheckpoint(var angle: Float = 0f)

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
    ambientMotionEnabled: Boolean = false,
    routeMotion: (@Composable (ApolloHeroPhase, Modifier) -> Boolean)? = null,
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
        SubscriptionDisplayState.DEVICE_DENIED -> stringResource(Res.string.apollo_status_device_denied)
        SubscriptionDisplayState.UNKNOWN -> stringResource(Res.string.apollo_status_unknown)
        SubscriptionDisplayState.REFRESH_FAILED -> stringResource(Res.string.apollo_status_refresh_failed)
    }
    val connectionTitle = when {
        isConnected && isConnecting -> stringResource(Res.string.apollo_reconnecting)
        isConnected -> stringResource(Res.string.apollo_connected)
        isConnecting -> stringResource(Res.string.apollo_connecting)
        else -> stringResource(Res.string.apollo_disconnected)
    }
    val linkCode = when {
        notice == ApolloConnectionNotice.FAILED || notice == ApolloConnectionNotice.BLOCKED ->
            stringResource(Res.string.apollo_link_failed)
        isConnected && isConnecting -> stringResource(Res.string.apollo_link_reconnecting)
        isConnecting -> stringResource(Res.string.apollo_link_connecting)
        isConnected -> stringResource(Res.string.apollo_link_connected)
        else -> stringResource(Res.string.apollo_link_idle)
    }
    val actionTitle = when {
        isConnected -> stringResource(Res.string.apollo_disconnect)
        isConnecting -> stringResource(Res.string.apollo_cancel)
        else -> stringResource(Res.string.apollo_connect)
    }
    val compactActionTitle = if (isConnecting && !isConnected)
        stringResource(Res.string.apollo_cancel_short) else actionTitle
    val accessBlocked = subscription.state in setOf(SubscriptionDisplayState.EXPIRED,
        SubscriptionDisplayState.LIMITED, SubscriptionDisplayState.DEVICE_DENIED)
    val actionEnabled = isConnected || isConnecting || (canStartVpn && !accessBlocked && selectedLocationId != null)
    val unknownCountry = stringResource(Res.string.apollo_country_unknown)
    val selectedCountry = selected?.let { localizedCountry(it.country, unknownCountry) }
    val actionDescription = listOfNotNull(actionTitle, selectedCountry).joinToString(", ")
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
    val scrollState = rememberScrollState()
    var heroHeightPx by remember { mutableIntStateOf(0) }
    var statusRowHeightPx by remember { mutableIntStateOf(0) }
    val compactDestination = selected?.let { destination(it) }
        ?: if (selectedExperimental) stringResource(Res.string.apollo_experimental) else null
    val showCompactAction by remember(scrollState, compactDestination, heroHeightPx) {
        derivedStateOf {
            compactDestination != null && heroHeightPx > 0 && scrollState.value >= heroHeightPx
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
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
        },
        bottomBar = {
            AnimatedVisibility(
                visible = showCompactAction,
                enter = fadeIn(animationSpec = tween(160)),
                exit = fadeOut(animationSpec = tween(120)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(compactDestination.orEmpty(), style = MaterialTheme.typography.titleSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            selected?.let {
                                Text(hostMode(it, selectedPair.first.config?.kind),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Button(onClick = onAction, enabled = actionEnabled,
                            modifier = Modifier.widthIn(min = 132.dp, max = 180.dp)
                                .defaultMinSize(minHeight = 52.dp)
                                .semantics {
                                    contentDescription = actionDescription
                                    testTag = "apollo-sticky-action"
                                }) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (!selected?.flag.isNullOrBlank()) {
                                    ApolloActionFlag(selected?.flag, isConnecting, ambientMotionEnabled,
                                        tag = "apollo-sticky-action-flag")
                                }
                                Text(compactActionTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(scrollState)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(color = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, signalColor.copy(alpha = if (isConnected) 0.62f else 0.34f)),
                modifier = Modifier.onSizeChanged { heroHeightPx = it.height }
                    .semantics { testTag = "apollo-hero" }) {
                Box(
                    Modifier.fillMaxWidth().background(
                        Brush.verticalGradient(
                            listOf(MaterialTheme.colorScheme.primaryContainer, heroColor, heroColor)
                        )
                    )
                ) {
                    ApolloHeroBackdrop(signalColor, ambientMotionEnabled, isConnecting, isConnected,
                        statusRowHeightPx, Modifier.matchParentSize())
                Column(Modifier.fillMaxWidth().animateContentSize(animationSpec = tween(220)).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(Modifier.fillMaxWidth().onSizeChanged { statusRowHeightPx = it.height },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(linkCode, style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace, letterSpacing = 0.5.sp,
                                color = signalColor.copy(alpha = 0.80f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(connectionTitle, style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold, color = signalColor)
                        }
                        ApolloStatusSignal(signalColor, isConnected, isConnecting, ambientMotionEnabled)
                    }
                    if (selected != null) {
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
                    val actionShape = RoundedCornerShape(20.dp)
                    val actionGradient = Brush.linearGradient(
                        when {
                            !actionEnabled -> listOf(Color(0xFF39394C), Color(0xFF303447))
                            isConnecting -> listOf(Color(0xFF795BCB), Color(0xFF5261BA), Color(0xFF5D48AA))
                            isConnected -> listOf(Color(0xFF177F78), Color(0xFF176262), Color(0xFF24475D))
                            else -> listOf(Color(0xFF825AE4), Color(0xFF634FD4), Color(0xFF465ABE))
                        }
                    )
                    val actionInteraction = remember { MutableInteractionSource() }
                    val pressed by actionInteraction.collectIsPressedAsState()
                    val actionScale by animateFloatAsState(
                        targetValue = if (pressed) 0.985f else 1f,
                        animationSpec = tween(140),
                        label = "primary action press"
                    )
                    Button(
                        onClick = onAction,
                        enabled = actionEnabled,
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp)
                            .graphicsLayer(scaleX = actionScale, scaleY = actionScale)
                            .shadow(10.dp, actionShape)
                            .background(actionGradient, actionShape)
                            .background(
                                Brush.linearGradient(listOf(
                                    Color.White.copy(alpha = 0.17f),
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.06f)
                                )), actionShape
                            )
                            .border(BorderStroke(1.dp, Color.White.copy(alpha = if (actionEnabled) 0.32f else 0.10f)), actionShape)
                            .semantics {
                                contentDescription = actionDescription
                                testTag = "apollo-action"
                            },
                        shape = actionShape,
                        interactionSource = actionInteraction,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            contentColor = Color.White,
                            disabledContentColor = Color.White.copy(alpha = 0.55f)
                        )
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            ApolloActionFlag(selected?.flag, isConnecting, ambientMotionEnabled,
                                tag = "apollo-action-flag")
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(actionTitle, style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                selectedCountry?.let { country ->
                                    Text(country, style = MaterialTheme.typography.labelMedium,
                                        textAlign = TextAlign.Center, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.semantics { testTag = "apollo-action-country" })
                                }
                            }
                            Text(if (isConnected || isConnecting) "×" else "→",
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White.copy(alpha = 0.78f),
                                textAlign = TextAlign.Center, maxLines = 1,
                                modifier = Modifier.width(32.dp).clearAndSetSemantics { })
                        }
                    }
                    if (selected != null || selectedExperimental) {
                        if (selected?.relay != null) {
                            ApolloRoutePath(
                                description = route,
                                destination = localizedCountry(selected.country, unknownCountry),
                                color = signalColor,
                                isConnected = isConnected,
                                isConnecting = isConnecting,
                                ambientMotionEnabled = ambientMotionEnabled,
                                phase = when {
                                    notice == ApolloConnectionNotice.FAILED ||
                                        notice == ApolloConnectionNotice.BLOCKED -> ApolloHeroPhase.ERROR
                                    isConnecting -> ApolloHeroPhase.CONNECTING
                                    isConnected -> ApolloHeroPhase.CONNECTED
                                    else -> ApolloHeroPhase.IDLE
                                },
                                routeMotion = routeMotion
                            )
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
                SubscriptionDisplayState.EXPIRED, SubscriptionDisplayState.LIMITED,
                SubscriptionDisplayState.DEVICE_DENIED -> MaterialTheme.colorScheme.error
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
                        } else if (subscription.state == SubscriptionDisplayState.DEVICE_DENIED) {
                            Text(stringResource(Res.string.apollo_status_device_denied_hint),
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

            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(Res.string.apollo_destinations),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() })
                Text(stringResource(Res.string.apollo_single_connection_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (ordinary.isEmpty()) {
                Text(stringResource(Res.string.apollo_no_hosts), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onAdd, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                    Text(stringResource(Res.string.apollo_add))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ordinary.groupBy { it.second.country }.forEach { (country, hosts) ->
                        val sharedIngress = hosts.map { (_, host) ->
                            host.relay?.let { stringResource(Res.string.apollo_ingress_configured) }
                        }.distinct().singleOrNull()
                        ApolloCountryHostCard(
                            countryTitle = destination(hosts.first().second),
                            sharedIngress = sharedIngress,
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
                                    technical = protocol,
                                    selected = item.storageId == selectedLocationId,
                                    ingress = ingress
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
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** A looping reveal communicates activity without pretending to measure connection progress. */
@Composable
private fun ApolloActionFlag(
    flag: String?,
    isConnecting: Boolean,
    motionEnabled: Boolean,
    tag: String
) {
    if (flag.isNullOrBlank()) {
        Spacer(Modifier.size(32.dp))
        return
    }
    val reveal = if (isConnecting && motionEnabled) {
        val transition = rememberInfiniteTransition(label = "destination flag reveal")
        val fraction by transition.animateFloat(
            initialValue = 0.01f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
            label = "flag reveal"
        )
        fraction
    } else 1f
    Box(Modifier.size(32.dp).semantics { testTag = tag }, contentAlignment = Alignment.Center) {
        Text(flag, fontSize = 24.sp, textAlign = TextAlign.Center,
            modifier = Modifier.requiredWidth(32.dp).graphicsLayer(alpha = if (reveal < 1f) 0.28f else 1f)
                .clearAndSetSemantics { })
        if (isConnecting && motionEnabled) {
            Box(
                Modifier.align(Alignment.CenterStart).width((32f * reveal).dp).height(32.dp)
                    .clipToBounds().semantics { testTag = "$tag-fill" },
                contentAlignment = Alignment.CenterStart
            ) {
                Text(flag, fontSize = 24.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.requiredWidth(32.dp).clearAndSetSemantics { })
            }
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

/** A quiet orbital field: planets start with the tunnel and coast to a stop after disconnection. */
@Composable
private fun ApolloHeroBackdrop(
    color: Color,
    motionEnabled: Boolean,
    isConnecting: Boolean,
    isConnected: Boolean,
    statusRowHeightPx: Int,
    modifier: Modifier = Modifier
) {
    val active = isConnecting || isConnected
    val orbitCheckpoint = remember { OrbitCheckpoint() }
    var wasActive by remember { mutableStateOf(false) }
    var isSettling by remember { mutableStateOf(false) }
    val settledOrbit = remember { Animatable(0f) }
    val orbitStart = remember(active, motionEnabled) { orbitCheckpoint.angle }
    val loopPosition = if (motionEnabled && active) {
        val transition = rememberInfiniteTransition(label = "Apollo planets")
        val position by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Restart),
            label = "planet orbit"
        )
        position
    } else null
    val orbitPosition = when {
        loopPosition != null -> orbitStart + loopPosition
        isSettling -> settledOrbit.value
        else -> orbitCheckpoint.angle
    }
    if (loopPosition != null) SideEffect { orbitCheckpoint.angle = orbitPosition }
    LaunchedEffect(motionEnabled, active) {
        when {
            !motionEnabled -> {
                isSettling = false
                wasActive = false
            }
            active -> {
                isSettling = false
                wasActive = true
            }
            wasActive -> {
                isSettling = true
                settledOrbit.snapTo(orbitCheckpoint.angle)
                settledOrbit.animateTo(orbitCheckpoint.angle + 0.08f,
                    animationSpec = tween(900, easing = FastOutSlowInEasing))
                orbitCheckpoint.angle = settledOrbit.value
                isSettling = false
                wasActive = false
            }
        }
    }
    val energy by animateFloatAsState(
        targetValue = when {
            isConnected && !isConnecting -> 1f
            isConnecting -> 0.72f
            else -> 0.12f
        },
        animationSpec = if (motionEnabled) tween(700) else snap(),
        label = "Apollo sun intensity"
    )
    Canvas(modifier.then(if (motionEnabled && (active || isSettling)) Modifier.semantics {
        testTag = "apollo-ambient-orbit"
    } else Modifier)) {
        val centerY = 20.dp.toPx() +
            if (statusRowHeightPx > 0) statusRowHeightPx / 2f else 16.dp.toPx()
        val orbitCenter = Offset(size.width - 36.dp.toPx(), centerY)
        drawRect(
            Brush.radialGradient(
                listOf(color.copy(alpha = 0.07f + 0.12f * energy), Color.Transparent),
                center = orbitCenter, radius = 132.dp.toPx()
            )
        )
        repeat(3) { ring ->
            val radius = (26 + ring * 26).dp.toPx()
            drawCircle(
                color.copy(alpha = 0.11f + 0.035f * energy - ring * 0.016f),
                radius = radius,
                center = orbitCenter,
                style = Stroke(width = 1.dp.toPx())
            )
            val phase = when (ring) { 0 -> 0f; 1 -> 0.34f; else -> 0.71f }
            val angle = 2.0 * PI * (orbitPosition + phase) - PI / 2.0
            val planet = Offset(
                orbitCenter.x + (radius * cos(angle)).toFloat(),
                orbitCenter.y + (radius * sin(angle)).toFloat()
            )
            drawCircle(color.copy(alpha = 0.07f + 0.18f * energy),
                radius = (5 + ring).dp.toPx(), center = planet)
            drawCircle(color.copy(alpha = 0.28f + 0.42f * energy),
                radius = (1.7f + ring * 0.4f).dp.toPx(), center = planet)
        }
    }
}

/** The configured relay path lights up once during connection, then rests. */
@Composable
private fun ApolloRoutePath(
    description: String,
    destination: String,
    color: Color,
    isConnected: Boolean,
    isConnecting: Boolean,
    ambientMotionEnabled: Boolean,
    phase: ApolloHeroPhase,
    routeMotion: (@Composable (ApolloHeroPhase, Modifier) -> Boolean)?
) {
    val progress by animateFloatAsState(
        targetValue = if (isConnected && !isConnecting) 1f else 0f,
        animationSpec = tween(460),
        label = "configured route"
    )
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val probe = if (isConnecting && ambientMotionEnabled) {
        val transition = rememberInfiniteTransition(label = "route probe")
        val position by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1700, easing = LinearEasing), RepeatMode.Restart),
            label = "route probe position"
        )
        position
    } else null
    Column(
        modifier = Modifier.fillMaxWidth().semantics { testTag = "apollo-route" },
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(description, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().height(if (routeMotion != null) 32.dp else 18.dp),
            contentAlignment = Alignment.Center) {
            val riveReady = if (routeMotion != null && ambientMotionEnabled) {
                routeMotion(phase, Modifier.fillMaxSize())
            } else false
            if (!riveReady) Canvas(Modifier.fillMaxWidth().height(18.dp)) {
                val y = size.height / 2f
                val left = 6.dp.toPx()
                val right = size.width - left
                drawLine(trackColor, Offset(left, y), Offset(right, y),
                    strokeWidth = 2.dp.toPx())
                if (progress > 0f) {
                    drawLine(color, Offset(left, y),
                        Offset(left + (right - left) * progress, y), strokeWidth = 2.dp.toPx())
                }
                if (isConnecting) {
                    val x = left + (right - left) * (probe ?: 0.12f)
                    drawCircle(color.copy(alpha = 0.20f), radius = 9.dp.toPx(), center = Offset(x, y))
                    drawCircle(color, radius = 4.dp.toPx(), center = Offset(x, y))
                }
                listOf(left, size.width / 2f, right).forEach { x ->
                    drawCircle(if (x <= left + (right - left) * progress) color else trackColor,
                        radius = 5.dp.toPx(), center = Offset(x, y))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.apollo_route_device),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            Text(stringResource(Res.string.apollo_route_relay),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f))
            Text(destination, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
        }
    }
}

/** Decorative status dot, not a second connect control. Only in-flight work spins. */
@Composable
private fun ApolloStatusSignal(color: Color, isConnected: Boolean, isConnecting: Boolean, motionEnabled: Boolean) {
    val arrival = remember { Animatable(1f) }
    var wasConnected by remember { mutableStateOf(isConnected) }
    val sunEnergy by animateFloatAsState(
        targetValue = when {
            isConnected && !isConnecting -> 1f
            isConnecting -> 0.72f
            else -> 0.14f
        },
        animationSpec = if (motionEnabled) tween(700) else snap(),
        label = "Apollo sun glow"
    )
    LaunchedEffect(isConnected, motionEnabled) {
        val justConnected = isConnected && !wasConnected
        wasConnected = isConnected
        if (justConnected && motionEnabled) {
            arrival.snapTo(0f)
            arrival.animateTo(1f, animationSpec = tween(520))
        } else {
            arrival.snapTo(1f)
        }
    }
    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(28.dp)) {
            val stroke = 2.dp.toPx()
            if (arrival.value < 1f) {
                drawCircle(
                    color.copy(alpha = (1f - arrival.value) * 0.5f),
                    radius = size.minDimension * (0.16f + arrival.value * 0.34f),
                    style = Stroke(width = stroke * (1.5f - arrival.value))
                )
            }
            drawCircle(color.copy(alpha = 0.06f + 0.22f * sunEnergy),
                radius = size.minDimension * 0.48f)
            drawCircle(color.copy(alpha = 0.36f + 0.64f * sunEnergy),
                radius = size.minDimension * 0.18f)
        }
        if (isConnecting && motionEnabled) {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp), color = color,
                strokeWidth = 2.dp, trackColor = Color.Transparent
            )
        }
    }
}
