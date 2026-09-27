package org.olcbox.app

import org.olcbox.app.ui.tv.TelevisionAware
import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import org.olcbox.app.data.datasource.LocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.exporter.AndroidLogExporter
import org.olcbox.app.data.identity.PersistentDeviceIdentityProvider
import org.olcbox.app.data.importer.AndroidConfigImporter
import org.olcbox.app.ui.activities.AndroidMainScreen
import org.olcbox.app.ui.activities.ExternalImportConfirmationState
import org.olcbox.app.ui.features.home.HomeScreenViewModel
import org.olcbox.app.ui.features.locations.LocationViewModel
import org.olcbox.app.ui.theme.AppTheme
import org.olcbox.app.net.AndroidRuntimeLocationPolicy
import org.olcbox.app.net.AndroidExternalImportIntentPolicy
import org.olcbox.app.update.AppUpdateService
import org.olcbox.app.vpn.AndroidVpnManager

class AppActivity : ComponentActivity() {

    private companion object {
        const val EXTERNAL_IMPORT_CONFLICT_KEY = "external_import_conflict"
    }

    // Process every launch intent synchronously; a StateFlow would conflate
    // distinct links received before the screen's collector runs.
    private val externalImportConfirmation = ExternalImportConfirmationState()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Permission handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val vpnManager = AndroidVpnManager(this)
        val locationsDataSource = LocationsDataSourceImpl(this)
        val locationsRepository = LocationsRepositoryImpl(
            locationsDataSource,
            runtimePolicy = AndroidRuntimeLocationPolicy.forApp(applicationContext)
        )
        val configImporter = AndroidConfigImporter(this)
        val logExporter = AndroidLogExporter(this)
        // Null where the store owns updates (the `play` flavor): the screen then
        // neither polls the release feed nor shows the Updates section, and the
        // manifest of that flavor carries no REQUEST_INSTALL_PACKAGES to use.
        val updateService = if (resources.getBoolean(R.bool.store_self_update)) {
            AppUpdateService(
                deviceIdentityProvider = PersistentDeviceIdentityProvider(locationsDataSource)
            )
        } else {
            null
        }

        val viewModel = HomeScreenViewModel(
            vpnManager = vpnManager,
            locationsRepository = locationsRepository,
            configImporter = configImporter,
            logExporter = logExporter
        )
        val locationViewModel = LocationViewModel(
            locationsRepository = locationsRepository
        )

        enableEdgeToEdge()
        setContent {
            AppTheme {
                TelevisionAware {
                    AndroidMainScreen(
                        viewModel = viewModel,
                        locationViewModel = locationViewModel,
                        vpnManager = vpnManager,
                        appUpdateService = updateService,
                        externalImportConfirmation = externalImportConfirmation
                    )
                }
            }
        }
        externalImportConfirmation.receiveInitialIntent(
            allowedExternalImport(intent),
            savedInstanceState?.getBoolean(EXTERNAL_IMPORT_CONFLICT_KEY, false)
        )
        clearStoredExternalIntent()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Only the conflict bit survives recreation; never put a subscription URL in a Bundle.
        outState.putBoolean(EXTERNAL_IMPORT_CONFLICT_KEY, externalImportConfirmation.hasConflict)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        externalImportConfirmation.receiveExternalIntent(allowedExternalImport(intent))
        clearStoredExternalIntent()
    }

    private fun allowedExternalImport(incoming: Intent?): String? =
        AndroidExternalImportIntentPolicy.allowedData(
            incoming?.action,
            incoming?.dataString
        )

    private fun clearStoredExternalIntent() {
        // Android may reuse Activity.intent on recreation; keep no token-bearing VIEW data there.
        setIntent(Intent(this, AppActivity::class.java).apply { action = Intent.ACTION_MAIN })
    }
}
