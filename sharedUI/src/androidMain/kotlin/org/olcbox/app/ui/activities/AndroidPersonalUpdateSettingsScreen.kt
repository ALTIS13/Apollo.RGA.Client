package org.olcbox.app.ui.activities

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import multiplatform_app.sharedui.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.ui.components.kit.PkScreenHeader
import org.olcbox.app.update.personal.*

@Composable
internal fun AndroidPersonalUpdateSettingsScreen(
    state: PersonalUpdateState,
    installNotice: PersonalUpdateInstallNotice,
    enabled: Boolean,
    onBack: () -> Unit,
    onAction: (PersonalUpdateAction) -> Unit,
    publicOwnServer: Boolean = false
) {
    val ui = personalUpdateUiModel(state, installNotice)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp).padding(top = 16.dp, bottom = 32.dp)) {
        PkScreenHeader(title = stringResource(Res.string.updates_title), onBack = onBack)
        Spacer(Modifier.height(18.dp))
        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.updates_current_version, CurrentAppInfo.value.version),
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                ui.versionName?.let { version ->
                    Text(stringResource(Res.string.personal_ota_candidate_version, version),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                Text(stringResource(ui.message.resource(publicOwnServer)), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (ui.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(stringResource(if (publicOwnServer) Res.string.public_own_server_ota_intro else Res.string.personal_ota_intro), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ui.warnVpnRestart) {
            Spacer(Modifier.height(18.dp))
            Text(stringResource(Res.string.personal_ota_vpn_warning), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(24.dp))
        ui.primary?.let { action ->
            Button(onClick = { onAction(action) }, enabled = enabled && !ui.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), contentPadding = PaddingValues(16.dp)) {
                Text(stringResource(action.resource()))
            }
        }
        ui.secondary?.let { action ->
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { onAction(action) }, enabled = enabled && !ui.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), contentPadding = PaddingValues(16.dp)) {
                Text(stringResource(action.resource()))
            }
        }
    }
}

private fun PersonalUpdateAction.resource(): StringResource = when (this) {
    PersonalUpdateAction.CHECK -> Res.string.action_check_now
    PersonalUpdateAction.DOWNLOAD -> Res.string.action_download
    PersonalUpdateAction.INSTALL -> Res.string.personal_ota_install_action
    PersonalUpdateAction.OPEN_SETTINGS -> Res.string.personal_ota_settings_action
}
private fun PersonalUpdateMessage.resource(publicOwnServer: Boolean): StringResource = when (this) {
    PersonalUpdateMessage.IDLE -> Res.string.personal_ota_idle
    PersonalUpdateMessage.CHECKING -> Res.string.personal_ota_checking
    PersonalUpdateMessage.AVAILABLE -> Res.string.personal_ota_available
    PersonalUpdateMessage.DOWNLOADING -> Res.string.personal_ota_downloading
    PersonalUpdateMessage.READY -> Res.string.personal_ota_ready
    PersonalUpdateMessage.SESSION_CHANGED -> Res.string.personal_ota_session_changed
    PersonalUpdateMessage.UNAVAILABLE -> Res.string.personal_ota_unavailable
    PersonalUpdateMessage.NETWORK_REFUSED -> Res.string.personal_ota_network_refused
    PersonalUpdateMessage.COMPATIBILITY_REFUSED -> if (publicOwnServer) Res.string.public_own_server_ota_compatibility_refused else Res.string.personal_ota_compatibility_refused
    PersonalUpdateMessage.VERIFIED_UPDATE_REFUSED -> Res.string.personal_ota_not_confirmed
    PersonalUpdateMessage.NOT_NEWER -> Res.string.personal_ota_not_newer
    PersonalUpdateMessage.HANDED_OFF -> Res.string.personal_ota_handed_off
    PersonalUpdateMessage.PERMISSION_REQUIRED -> Res.string.personal_ota_permission_required
    PersonalUpdateMessage.PERMISSION_RETURNED -> Res.string.personal_ota_permission_returned
    PersonalUpdateMessage.INSTALL_REFUSED -> Res.string.personal_ota_install_refused
}
