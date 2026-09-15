package uk.co.james.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.james.BuildConfig

@Composable fun UpdateSettingsCard(vm:JamesViewModel,install:()->Unit) {
    val progress by vm.updateProgress.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val apk by vm.downloaded.collectAsStateWithLifecycle()
    val configured by vm.githubAccess.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val uriHandler=LocalUriHandler.current
    fun openLink(url:String){runCatching {uriHandler.openUri(url)}.onFailure {vm.message.value="No browser is available to open GitHub."}}
    var dialog by remember {mutableStateOf(false)}
    // Credentials must never enter Android saved state or James backups.
    var token by remember {mutableStateOf("")}
    JamesCard("Update App","Version ${BuildConfig.VERSION_NAME}") {
        Muted("James OS · Signed releases")
        Muted(if(configured)"Private GitHub access saved on this device." else "Public releases need no sign-in. If the release repository is private, set up GitHub access or open Releases in your browser.")
        TextButton(enabled=!busy,onClick={dialog=true}){Text(if(configured)"Replace GitHub access" else "GitHub access (private releases only)")}
        if(configured)TextButton(enabled=!busy,onClick={vm.removeGithubAccess()}){Text("Remove GitHub access")}
        if(BuildConfig.DEBUG)Muted("This is the test app. Export your James backup before moving to the signed release app, then import it there. Release updates retain that app’s data.")
        TextButton(onClick={openLink("https://github.com/${BuildConfig.UPDATE_REPOSITORY}/releases/latest")}){Text("Open Releases in browser")}
        Text(progress)
        Button(enabled=!busy,onClick={vm.updateCheck()}){Text("Check for update")}
        if(update!=null)Button(enabled=!busy,onClick={vm.downloadUpdate()}){Text("Download signed APK")}
        if(apk!=null)Button(onClick=install){Text("Install verified update")}
        Muted("Downloads are verified against this app’s package name and signing certificate before installation.")
    }
    if(dialog)AlertDialog(onDismissRequest={dialog=false;token=""},title={Text("Private GitHub access")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("1. Open GitHub below and sign in.\n2. Name the token James OS updates.\n3. Choose Only select repositories → JamesOS.\n4. Set Repository permissions → Contents → Read-only.\n5. Generate the token and paste it below.")
        TextButton(onClick={openLink("https://github.com/settings/personal-access-tokens/new")}){Text("Create GitHub access token")}
        Text("This is separate from your signing password. The token is encrypted on this device and excluded from backups. Renew it here when it expires.")
        OutlinedTextField(value=token,onValueChange={token=it},label={Text("GitHub token")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth())
    }},confirmButton={TextButton(enabled=token.trim().length>=20,onClick={vm.saveGithubAccess(token);token="";dialog=false}){Text("Save")}},dismissButton={TextButton(onClick={token="";dialog=false}){Text("Cancel")}})
}
