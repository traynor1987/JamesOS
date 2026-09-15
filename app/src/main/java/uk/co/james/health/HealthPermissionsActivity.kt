package uk.co.james.health
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
class HealthPermissionsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {super.onCreate(savedInstanceState);setContent {MaterialTheme {Surface {Column(Modifier.padding(24.dp)) {Text("James health permissions",style=MaterialTheme.typography.headlineMedium);Spacer(Modifier.height(16.dp));Text("James reads only health types you choose, for your dashboard and timeline. Records stay in the local Android database. Samsung Health data is labelled with the originating package supplied by Health Connect. You can revoke access in Health Connect at any time. James does not diagnose conditions or share health records with an AI service.");Button(onClick={finish()}) {Text("Close")}}}}}}
}
