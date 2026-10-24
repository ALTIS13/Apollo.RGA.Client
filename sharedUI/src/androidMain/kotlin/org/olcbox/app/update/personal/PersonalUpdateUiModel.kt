package org.olcbox.app.update.personal

enum class PersonalUpdateAction { CHECK, DOWNLOAD, INSTALL, OPEN_SETTINGS }
enum class PersonalUpdateMessage { IDLE, CHECKING, AVAILABLE, DOWNLOADING, READY, SESSION_CHANGED,
    UNAVAILABLE, NETWORK_REFUSED, COMPATIBILITY_REFUSED, VERIFIED_UPDATE_REFUSED, NOT_NEWER, HANDED_OFF,
    PERMISSION_REQUIRED, PERMISSION_RETURNED, INSTALL_REFUSED }
enum class PersonalUpdateInstallNotice { NONE, PERMISSION_REQUIRED, PERMISSION_RETURNED, REFUSED }
data class PersonalUpdateUiModel(val message: PersonalUpdateMessage,
    val primary: PersonalUpdateAction?, val secondary: PersonalUpdateAction? = null,
    val busy: Boolean = false, val versionName: String? = null, val warnVpnRestart: Boolean = false)

internal fun personalUpdateUiModel(state: PersonalUpdateState,
    notice: PersonalUpdateInstallNotice = PersonalUpdateInstallNotice.NONE): PersonalUpdateUiModel = when (state) {
    PersonalUpdateState.Idle -> PersonalUpdateUiModel(PersonalUpdateMessage.IDLE, PersonalUpdateAction.CHECK)
    PersonalUpdateState.Checking -> PersonalUpdateUiModel(PersonalUpdateMessage.CHECKING, null, busy = true)
    is PersonalUpdateState.Available -> PersonalUpdateUiModel(PersonalUpdateMessage.AVAILABLE,
        PersonalUpdateAction.DOWNLOAD, PersonalUpdateAction.CHECK, versionName = state.versionName)
    PersonalUpdateState.Downloading -> PersonalUpdateUiModel(PersonalUpdateMessage.DOWNLOADING, null, busy = true)
    is PersonalUpdateState.Ready -> PersonalUpdateUiModel(
        message = when (notice) {
            PersonalUpdateInstallNotice.NONE -> PersonalUpdateMessage.READY
            PersonalUpdateInstallNotice.PERMISSION_REQUIRED -> PersonalUpdateMessage.PERMISSION_REQUIRED
            PersonalUpdateInstallNotice.PERMISSION_RETURNED -> PersonalUpdateMessage.PERMISSION_RETURNED
            PersonalUpdateInstallNotice.REFUSED -> PersonalUpdateMessage.INSTALL_REFUSED
        },
        primary = if (notice == PersonalUpdateInstallNotice.PERMISSION_REQUIRED) PersonalUpdateAction.OPEN_SETTINGS
            else PersonalUpdateAction.INSTALL,
        secondary = PersonalUpdateAction.CHECK, versionName = state.versionName, warnVpnRestart = true)
    PersonalUpdateState.SessionChanged -> PersonalUpdateUiModel(PersonalUpdateMessage.SESSION_CHANGED, PersonalUpdateAction.CHECK)
    PersonalUpdateState.Unavailable -> PersonalUpdateUiModel(PersonalUpdateMessage.UNAVAILABLE, PersonalUpdateAction.CHECK)
    is PersonalUpdateState.Rejected -> PersonalUpdateUiModel(when (state.reason) {
        PersonalUpdateControllerRefusal.HTTP -> PersonalUpdateMessage.NETWORK_REFUSED
        PersonalUpdateControllerRefusal.BASELINE -> PersonalUpdateMessage.COMPATIBILITY_REFUSED
        PersonalUpdateControllerRefusal.NOT_NEWER -> PersonalUpdateMessage.NOT_NEWER
        else -> PersonalUpdateMessage.VERIFIED_UPDATE_REFUSED
    }, PersonalUpdateAction.CHECK)
    PersonalUpdateState.HandedOff -> PersonalUpdateUiModel(PersonalUpdateMessage.HANDED_OFF, PersonalUpdateAction.CHECK)
}
