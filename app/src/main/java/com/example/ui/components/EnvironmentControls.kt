package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.environment.AppEnvironment
import com.example.data.api.ApiClient
import com.example.data.api.ReviewResourcesApi
import com.example.data.repository.importReviewSamples
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal val LocalEnvironmentSettings = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
internal fun EnvironmentSettingsButton(modifier: Modifier = Modifier, enabled: Boolean = true) {
    val open = LocalEnvironmentSettings.current ?: return
    TextButton(onClick = open, enabled = enabled, modifier = modifier.testTag("environment_settings")) {
        Text("About & demo", color = Color(0xFF007D75))
    }
}

@Composable
internal fun EnvironmentChooser(onDismiss: () -> Unit, onSwitch: (AppEnvironment) -> Unit, onOpenTravel: (() -> Unit)? = null) {
    val selected = ApiClient.environment()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var samplesLoaded by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Passo Mobility", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Go Further, Together.")
                Text("Current environment: ${if (selected == AppEnvironment.DEMO) "Demo" else "Live Passo"}", fontWeight = FontWeight.SemiBold)
                Text("Demo uses an isolated service with synthetic people, trips and tickets. No real payments or SMS are sent. Enter only sample information.")
                Text("Changing environment signs you out and stops trip location sharing. Your saved live tickets and requests stay separate and are kept on this device.")
                AppEnvironment.entries.forEach { target ->
                    OutlinedButton(onClick = { onSwitch(target) }, enabled = !busy && target != selected,
                        modifier = Modifier.fillMaxWidth().testTag("environment_choose_${target.name}")) {
                        Text(if (target == AppEnvironment.DEMO) "Open demo environment" else "Return to live Passo")
                    }
                }
                if (selected == AppEnvironment.DEMO) {
                    Text("Use the demo sign-in details supplied with this test. Sample boarding tickets are server-provided examples; they cannot be used for a real journey.")
                    Button(onClick = {
                        scope.launch {
                            busy = true; message = null
                            try {
                                val api = ApiClient.createFeatureService(ReviewResourcesApi::class.java)
                                val response = api.resources()
                                val body = response.body()
                                check(response.isSuccessful && body?.success == true) { "Sign in with a supplied demo account, then try again." }
                                importReviewSamples(context, body!!)
                                samplesLoaded = true
                                message = "Sample access loaded. View sample journeys, then open My tickets or Group/Cargo request history. Use Refresh if that screen is already open."
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { message = error.message ?: "Could not load sample access. Please try again." }
                            finally { busy = false }
                        }
                    }, enabled = !busy && !ApiClient.getAuthToken().isNullOrBlank(), modifier = Modifier.fillMaxWidth().testTag("review_load_samples")) {
                        Text("Load sample tickets & requests")
                    }
                    if (ApiClient.getAuthToken().isNullOrBlank()) Text("Sign in to a demo account to load sample tickets and requests.")
                    if (samplesLoaded && onOpenTravel != null) OutlinedButton(onClick = onOpenTravel, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("review_open_travel")) { Text("View sample journeys") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, modifier = Modifier.testTag("environment_message")) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") } },
        containerColor = Color(0xFFFFFBF4), titleContentColor = Color(0xFF092438), textContentColor = Color(0xFF092438))
}

@Composable
internal fun DemoEnvironmentBanner(onSettings: () -> Unit) {
    Surface(color = Color(0xFFFFE5AD), contentColor = Color(0xFF092438), modifier = Modifier.fillMaxWidth().testTag("demo_environment_banner")) {
        Row(Modifier.statusBarsPadding().padding(start = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Demo — synthetic data; no real payments or SMS", modifier = Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = onSettings) { Text("Exit demo") }
        }
    }
}
