package org.olcbox.app.ui.features.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.ktor.http.Url
import io.ktor.http.decodeURLPart
import multiplatform_app.sharedui.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.olcbox.app.net.LocationKind
import org.olcbox.app.ui.ApolloOrbitMark
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.ui.icons.PkIcons

/** Presentation only. Recognizing an origin never creates an Olc admission grant. */
internal class ApolloSubscriptionGroup(
    val locations: List<LocationItem>,
    val title: String?,
    val apolloOrigin: Boolean,
    val hasSubscription: Boolean
) {
    val firstLocationId: String? get() = locations.firstOrNull {
        it.config != null && it.config.kind != LocationKind.Olcrtc
    }?.storageId ?: locations.firstOrNull()?.storageId
}

internal class ApolloSubscriptions(locations: List<LocationItem>, selectedLocationId: String?) {
    // URL is an internal identity only. Do not group by provider name, quota or expiry.
    val groups: List<ApolloSubscriptionGroup> = locations.groupBy {
        it.subscriptionUrl?.trim()?.takeIf(String::isNotBlank)
    }.map { (url, entries) ->
        ApolloSubscriptionGroup(entries,
            entries.firstNotNullOfOrNull { safeSubscriptionTitle(it.metadata?.subscription?.name, url) },
            url?.matches(APOLLO_SUBSCRIPTION) == true, url != null)
    }
    val selectedIndex: Int = groups.indexOfFirst { group ->
        group.locations.any { it.storageId == selectedLocationId }
    }.takeIf { it >= 0 } ?: 0
    val selected: ApolloSubscriptionGroup? get() = groups.getOrNull(selectedIndex)
    val visibleLocations: List<LocationItem> get() = selected?.locations.orEmpty()
}

// Same literal origin/path admitted by RemnawavePersonalBearer, not a suffix or name match.
private val APOLLO_SUBSCRIPTION = Regex("https://pan\\.gate-altas\\.tech/[A-Za-z0-9_-]{8,128}")
private fun safeSubscriptionTitle(value: String?, subscriptionUrl: String?): String? = value?.filterNot {
    it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069'
}?.trim()?.replace(Regex("\\s+"), " ")?.takeIf {
    it.isNotEmpty() && !it.contains("://") &&
        !Regex("[A-Za-z0-9_+/=-]{48,}").containsMatchIn(it) &&
        subscriptionCredentials(subscriptionUrl).none { credential -> it.contains(credential) }
}?.take(96)

/** Provider titles are untrusted: a short bearer is just as private as a long one. */
private fun subscriptionCredentials(raw: String?): List<String> = runCatching {
    if (raw == null) return@runCatching emptyList()
    val url = Url(raw)
    (url.encodedPath.split('/').flatMap { listOf(it, it.decodeURLPart()) } +
        url.parameters.entries().flatMap { it.value } + listOfNotNull(url.user, url.password))
        .filter { it.length >= 8 }.distinct()
}.getOrDefault(emptyList())

@Composable
private fun subscriptionTitle(model: ApolloSubscriptions, index: Int): String {
    val titles = model.groups.map { group -> group.title ?: stringResource(if (group.hasSubscription)
        Res.string.apollo_subscription_unnamed else Res.string.apollo_subscription_local) }
    val base = titles[index]
    val sameTitle = titles.indices.filter { titles[it] == base }
    return if (sameTitle.size > 1) {
        val ordinal = sameTitle.indexOf(index) + 1
        stringResource(Res.string.apollo_subscription_profile, base, ordinal)
    } else base
}

/** One quiet source selector, not a second list of country cards or duplicate accounts. */
@Composable
internal fun ApolloSubscriptionSelector(
    model: ApolloSubscriptions,
    locked: Boolean,
    onLocationSelected: (String) -> Unit
) {
    val selected = model.selected ?: return
    var expanded by remember(model.selectedIndex, locked) { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    val title = subscriptionTitle(model, model.selectedIndex)
    val sourceLabel = stringResource(when {
        selected.apolloOrigin -> Res.string.apollo_subscription_source_apollo
        selected.hasSubscription -> Res.string.apollo_subscription_source_external
        else -> Res.string.apollo_subscription_local
    })
    val changeLabel = stringResource(Res.string.apollo_subscription_change, title)
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { expanded = true },
                enabled = !locked && model.groups.size > 1,
                modifier = Modifier.fillMaxWidth().semantics {
                    testTag = "apollo-subscription-selector"
                    contentDescription = changeLabel
                }) {
                if (selected.apolloOrigin) ApolloOrbitMark(Modifier.size(28.dp))
                else Icon(PkIcons.Public, contentDescription = null, modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(Res.string.apollo_subscription_source, sourceLabel),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(title, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface)
                }
                if (model.groups.size > 1) Icon(Icons.Default.ArrowDropDown,
                    contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = expanded && !locked, onDismissRequest = { expanded = false }) {
                model.groups.forEachIndexed { index, group ->
                    DropdownMenuItem(
                        text = { Text(subscriptionTitle(model, index), maxLines = 2,
                            overflow = TextOverflow.Ellipsis) },
                        leadingIcon = {
                            if (group.apolloOrigin) ApolloOrbitMark(Modifier.size(24.dp))
                            else Icon(PkIcons.Public, contentDescription = null)
                        },
                        trailingIcon = { if (index == model.selectedIndex)
                            Icon(Icons.Default.Check, contentDescription = stringResource(Res.string.apollo_subscription_selected)) },
                        modifier = Modifier.semantics { testTag = "apollo-subscription-option-$index" },
                        onClick = {
                            expanded = false
                            if (!locked && index != model.selectedIndex) group.firstLocationId?.let(onLocationSelected)
                        }
                    )
                }
            }
        }
        IconButton(onClick = { showInfo = true }, modifier = Modifier.size(48.dp)
            .semantics { testTag = "apollo-subscription-provider-info" }) {
            Icon(PkIcons.Info, contentDescription = stringResource(Res.string.apollo_subscription_about),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (locked && model.groups.size > 1) Text(
        stringResource(Res.string.apollo_subscription_disconnect_first),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    if (showInfo) AlertDialog(onDismissRequest = { showInfo = false },
        title = { Text(sourceLabel) },
        text = { Text(stringResource(when {
            selected.apolloOrigin -> Res.string.apollo_subscription_apollo_info
            selected.hasSubscription -> Res.string.apollo_subscription_external_info
            else -> Res.string.apollo_subscription_local_info
        })) },
        confirmButton = { TextButton(onClick = { showInfo = false }) {
            Text(stringResource(Res.string.apollo_subscription_understood))
        } })
}
