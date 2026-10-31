package com.example.core.notifications

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.core.environment.AppEnvironment
import com.example.core.localization.AppLanguage
import com.example.data.api.ApiClient
import com.example.data.api.MobilityApi
import com.example.data.repository.AndroidEncryptedIntercityBookingStore
import com.example.ui.screens.intercity.MobilityPassengerScreen
import com.example.ui.screens.intercity.intercityText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A reference resolved locally and through the capability-protected API; never a capability from FCM. */
data class MobilityNotificationTarget(val payload: MobilityPushPayload)

internal fun savedRequestNotificationAllowed(payload: MobilityPushPayload, savedIds: Set<String>, actualId: String, actualKind: String): Boolean =
    payload.scope == "REQUEST" && "request:${payload.requestId}" in savedIds && actualId == payload.requestId &&
        actualKind in setOf("CARGO", "GROUP") && actualKind == payload.kind

object MobilityNotificationNavigation {
    private val mutable = MutableStateFlow<MobilityNotificationTarget?>(null)
    val destination = mutable.asStateFlow()
    private val driverMutable = MutableStateFlow<MobilityPushPayload?>(null)
    val driverFocus = driverMutable.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var opening: Job? = null

    /** Call from Activity.onCreate and onNewIntent. Unrelated launcher/deep-link intents are ignored. */
    fun acceptIntent(context: Context, intent: Intent) {
        val payload = MobilityPushPayload.fromIntent(intent) ?: return
        if (!MobilityPush.production(context)) return
        val app = context.applicationContext
        val session = ApiClient.sessionScope()
        intent.removeExtra(MobilityPushPayload.EXTRA_ID)
        intent.removeExtra(MobilityPushPayload.EXTRA_EVENT)
        intent.removeExtra(MobilityPushPayload.EXTRA_SCOPE)
        intent.removeExtra(MobilityPushPayload.EXTRA_KIND)
        opening?.cancel()
        if (payload.scope == "DRIVER") {
            // The composable checks the actual signed-in role before opening driver UI.
            mutable.value = MobilityNotificationTarget(payload)
            return
        }
        opening = scope.launch {
            try {
                val entries = withContext(Dispatchers.IO) { AndroidEncryptedIntercityBookingStore(app, "mobility", AppEnvironment.PRODUCTION).load() }
                val access = entries.firstOrNull { it.id == "request:${payload.requestId}" }
                if (access == null) {
                    Toast.makeText(app, "This request is not saved on this device. Open your request history.", Toast.LENGTH_LONG).show()
                    return@launch
                }
                if (!ApiClient.isCurrent(session)) return@launch
                val api = ApiClient.createFeatureService(MobilityApi::class.java)
                val response = api.requestStatus(payload.requestId, access.secret)
                val request = response.body()?.request
                if (response.isSuccessful && response.body()?.success == true && request != null && ApiClient.isCurrent(session) &&
                    savedRequestNotificationAllowed(payload, entries.map { it.id }.toSet(), request.id, request.kind)) {
                    mutable.value = MobilityNotificationTarget(payload)
                } else Toast.makeText(app, "This request could not be opened. Refresh your request history.", Toast.LENGTH_LONG).show()
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
                Toast.makeText(app, "Could not open the request. Check your connection and try your request history.", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun dismiss() { opening?.cancel(); mutable.value = null }
    internal fun focusDriver(payload: MobilityPushPayload) { if (payload.scope == "DRIVER") driverMutable.value = payload }
    fun consumeDriverFocus() { driverMutable.value = null }
}

/** Keeps the underlying navigation unchanged, including when the requester has no account. */
@Composable fun MobilityNotificationDestination(language: AppLanguage, currentUserId: String?, currentUserRole: String?,
    onOpenDriver: (MobilityPushPayload) -> Unit, onDismiss: () -> Unit = MobilityNotificationNavigation::dismiss) {
    val target by MobilityNotificationNavigation.destination.collectAsState()
    val selected = target ?: return
    val payload = selected.payload
    val context = LocalContext.current
    LaunchedEffect(selected, ApiClient.environment()) {
        if (!MobilityPush.production(context)) onDismiss()
    }
    if (payload.scope == "DRIVER") {
        if (currentUserId != null && currentUserRole.equals("DRIVER", true)) {
            LaunchedEffect(selected, currentUserId) {
                MobilityNotificationNavigation.focusDriver(payload)
                onOpenDriver(payload); onDismiss()
            }
        } else AlertDialog(onDismissRequest = onDismiss,
            title = { Text(intercityText(language, "Driver update", "የአሽከርካሪ ማሳወቂያ", "Odeeffannoo konkolaachisaa")) },
            text = { Text(intercityText(language, "Sign in as your driver account to view requests and jobs.", "ጥያቄዎችን እና ሥራዎችን ለማየት በአሽከርካሪ መለያዎ ይግቡ።", "Gaaffii fi hojii ilaaluuf akka konkolaachisaatti seeni.")) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(intercityText(language, "OK", "እሺ", "Tole")) } })
    } else Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MobilityPassengerScreen(payload.kind, language, onBack = onDismiss, modifier = Modifier.fillMaxSize(), initialRequestId = payload.requestId)
    }
}
