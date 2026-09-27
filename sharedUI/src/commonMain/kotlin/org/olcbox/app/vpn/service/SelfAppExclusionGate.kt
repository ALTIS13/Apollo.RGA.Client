package org.olcbox.app.vpn.service

internal object SelfAppExclusionGate {
    fun apply(excludeSelf: () -> Boolean, onFailure: () -> Unit): Boolean {
        if (excludeSelf()) return true
        onFailure()
        return false
    }
}
