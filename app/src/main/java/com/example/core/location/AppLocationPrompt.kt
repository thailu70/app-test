package com.example.core.location

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.location.LocationManager
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.screens.passenger.CommuteCream
import com.example.ui.screens.passenger.CommuteInk
import com.example.ui.screens.passenger.CommuteTeal

/** Only the device location switch is checked here. Real location access stays contextual. */
internal class AppLocationPromptSession(
    var prompted: Boolean = false,
    var settingsPending: Boolean = false,
    var foreground: Boolean = false
) {
    fun start(locationEnabled: Boolean): Boolean {
        if (!foreground && !settingsPending) prompted = false
        foreground = true
        settingsPending = false
        return check(locationEnabled)
    }
    fun check(locationEnabled: Boolean): Boolean {
        if (locationEnabled || prompted) return false
        prompted = true
        return true
    }
    fun stop() { foreground = false }
    fun openSettings() { settingsPending = true }
    fun settingsFailed() { settingsPending = false }
}

private val appLocationSessionSaver = listSaver<AppLocationPromptSession, Boolean>(
    save = { listOf(it.prompted, it.settingsPending, it.foreground) },
    restore = { AppLocationPromptSession(it[0], it[1], it[2]) }
)

internal fun appDeviceLocationEnabled(context: Context): Boolean = runCatching {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return@runCatching true
    LocationManagerCompat.isLocationEnabled(manager)
}.getOrDefault(true)

private fun Context.locationPromptActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.locationPromptActivity() else null
    else -> null
}

/** Set enabled=false in the isolated demo environment. Never forces GPS or asks for background access. */
@Composable fun AppLocationPrompt(enabled: Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val session = rememberSaveable(enabled, saver = appLocationSessionSaver) { AppLocationPromptSession() }
    var visible by rememberSaveable(enabled) { mutableStateOf(false) }
    var settingsError by rememberSaveable(enabled) { mutableStateOf<String?>(null) }
    DisposableEffect(enabled, lifecycle, context) {
        fun inspect(start: Boolean) {
            if (!enabled) return
            val locationEnabled = appDeviceLocationEnabled(context)
            if (locationEnabled) { visible = false; settingsError = null }
            val prompt = if (start) session.start(locationEnabled) else session.check(locationEnabled)
            if (prompt) visible = true
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> inspect(true)
                Lifecycle.Event.ON_RESUME -> inspect(false)
                Lifecycle.Event.ON_STOP -> if (context.locationPromptActivity()?.isChangingConfigurations != true) session.stop()
                else -> Unit
            }
        }
        if (enabled) {
            lifecycle.addObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) inspect(!session.foreground)
        } else visible = false
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (enabled && visible) AlertDialog(
        onDismissRequest = { visible = false; settingsError = null },
        icon = { Icon(Icons.Default.MyLocation, null, tint = CommuteTeal) },
        title = { Text("Turn on location", color = CommuteInk) },
        text = { Text(settingsError ?: "Turn on your phone’s location to find nearby pickup points and help with your journey. You can keep browsing and enable it later. Location access is requested when you use a feature that needs it.", color = CommuteInk) },
        containerColor = CommuteCream,
        modifier = Modifier.testTag("app_location_prompt"),
        confirmButton = { TextButton(onClick = {
            visible = false
            session.openSettings()
            try {
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                if (context.locationPromptActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (_: RuntimeException) {
                session.settingsFailed()
                settingsError = "Open your phone’s Settings → Location to enable location. You can continue browsing Passo."
                visible = true
            }
        }, modifier = Modifier.testTag("app_location_enable")) { Text("Enable location", color = CommuteTeal) } },
        dismissButton = { TextButton(onClick = { visible = false; settingsError = null }, modifier = Modifier.testTag("app_location_later")) { Text("Later", color = CommuteTeal) } }
    )
}
