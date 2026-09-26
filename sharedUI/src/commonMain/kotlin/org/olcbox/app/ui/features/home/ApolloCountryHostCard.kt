package org.olcbox.app.ui.features.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Ready-to-display copy keeps provider names and secrets out of this UI component. */
internal data class ApolloHostChoice(
    val id: String,
    val destination: String,
    val mode: String,
    val technical: String,
    val selected: Boolean,
    val ingress: String? = null
)

/** One country surface with purpose-first choices and native radio semantics. */
@Composable
internal fun ApolloCountryHostCard(
    countryTitle: String,
    choices: List<ApolloHostChoice>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    sharedIngress: String? = null
) {
    if (choices.isEmpty()) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.selectableGroup()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = countryTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() }
                )
                if (sharedIngress != null) {
                    Text(sharedIngress, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            choices.forEachIndexed { index, choice ->
                ApolloCountryChoiceRow(countryTitle, choice, sharedIngress, onSelect)
                if (index != choices.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 18.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ApolloCountryChoiceRow(
    countryTitle: String,
    choice: ApolloHostChoice,
    sharedIngress: String?,
    onSelect: (String) -> Unit
) {
    val background by animateColorAsState(
        targetValue = if (choice.selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainer,
        animationSpec = tween(180),
        label = "host selection"
    )
    val edge by animateColorAsState(
        targetValue = if (choice.selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = tween(180),
        label = "host selection edge"
    )
    val destinationDetail = choice.destination.takeIf { it.isNotBlank() && it != countryTitle }
    val shownTechnical = listOfNotNull(choice.technical,
        choice.ingress?.takeUnless { it == sharedIngress }).joinToString(" · ")
    val spokenLabel = listOfNotNull(countryTitle, choice.mode, destinationDetail,
        choice.technical, choice.ingress)
        .filter(String::isNotBlank).joinToString(", ")

    Surface(color = background) {
        Row(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 72.dp)
                .selectable(
                    selected = choice.selected,
                    role = Role.RadioButton,
                    onClick = { onSelect(choice.id) }
                )
                .semantics {
                    contentDescription = spokenLabel
                    testTag = "apollo-country-choice-${choice.id}"
                }
                .padding(start = 12.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                Modifier.width(3.dp).height(36.dp).background(
                    edge,
                    RoundedCornerShape(3.dp)
                )
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = choice.mode,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (choice.selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (choice.selected) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurface
                )
                if (destinationDetail != null) {
                    Text(
                        text = destinationDetail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (shownTechnical.isNotBlank()) {
                    Text(
                        text = shownTechnical,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            RadioButton(
                selected = choice.selected,
                onClick = null,
                modifier = Modifier.size(48.dp)
            )
        }
    }
}
