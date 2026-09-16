package uk.co.james

import android.os.Bundle
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.*
import uk.co.james.ui.*

class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {super.onCreate(savedInstanceState);enableEdgeToEdge();setContent {
        val vm:JamesViewModel=viewModel();androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME){vm.onAppResumed()};val theme by vm.theme.collectAsStateWithLifecycle()
        val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let {vm.chooseImport(it)}}
        val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->uri?.let {vm.export(it)}}
        val health=rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()){vm.refreshPermissions()}
        val location=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){vm.message.value="Location permissions updated. For geofences while locked, also choose Allow all the time in Android settings."}
        val calendar=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){ granted->vm.message.value=if(granted)"Calendar access allowed. Choose calendars in Connections." else "Calendar access was not granted. James OS will continue without Calendar schedule evidence." }
        JamesTheme(theme){JamesRoot(vm,
            chooseImport={picker.launch(arrayOf("application/json","text/plain","application/octet-stream"))},
            export={archive->vm.prepareExport(archive);export.launch("james-backup-${uk.co.james.core.today()}.json")},
            requestHealth={selected->health.launch(selected)},
            requestLocation={location.launch(buildList {add(Manifest.permission.ACCESS_FINE_LOCATION);add(Manifest.permission.ACCESS_COARSE_LOCATION);if(android.os.Build.VERSION.SDK_INT>=33)add(Manifest.permission.POST_NOTIFICATIONS)}.toTypedArray())},
            requestActivity={if(android.os.Build.VERSION.SDK_INT>=29)location.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))},
            requestCalendar={calendar.launch(Manifest.permission.READ_CALENDAR)},
            openAppSettings={startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))},
            install={if(!packageManager.canRequestPackageInstalls())startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:$packageName")))else startActivity(vm.installIntent())}
        )}
    }}
}
