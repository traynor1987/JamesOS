package uk.co.james.wear.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.LaunchedEffect

class WearMainActivity:ComponentActivity(){
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);if(intent.getBooleanExtra(EXTRA_RUN_STRESS_CHECK,false))recreate()}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);val startInSettings=intent.getStringExtra(EXTRA_SCREEN)==SCREEN_SETTINGS;val runStressCheck=intent.getBooleanExtra(EXTRA_RUN_STRESS_CHECK,false);setContent {val vm:WearViewModel=viewModel();if(runStressCheck) LaunchedEffect(Unit){vm.startSamsungSensorCheck()};val background=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){vm.registerPassive()};val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){
        val foregroundGranted=checkSelfPermission(Manifest.permission.BODY_SENSORS)==PackageManager.PERMISSION_GRANTED
        val backgroundMissing=android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.BODY_SENSORS_BACKGROUND)!=PackageManager.PERMISSION_GRANTED
        if(foregroundGranted&&backgroundMissing)background.launch(Manifest.permission.BODY_SENSORS_BACKGROUND) else vm.registerPassive()
    };JamesWearTheme {WearApp(vm,startInSettings,onPermissions={permissions.launch(arrayOf(Manifest.permission.BODY_SENSORS,Manifest.permission.ACTIVITY_RECOGNITION))},onInstall={runCatching {startActivity(vm.installIntent());vm.installerLaunched()}.onFailure {vm.installerLaunchFailed(it)}},onDismissUpdate={vm.dismissUpdate()})}}}
    companion object {const val EXTRA_SCREEN="uk.co.james.wear.screen";const val SCREEN_SETTINGS="settings";const val EXTRA_RUN_STRESS_CHECK="uk.co.james.wear.run_stress_check"}
}
