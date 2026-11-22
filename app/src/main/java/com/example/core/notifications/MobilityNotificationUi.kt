package com.example.core.notifications

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.api.ApiClient
import com.example.core.localization.AppLanguage
import com.example.ui.screens.intercity.intercityText
import com.example.ui.screens.passenger.CommuteInk
import com.example.ui.screens.passenger.CommuteTeal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Silent session synchronisation. Permission is requested only by the contextual opt-in control. */
@Composable fun AppPushRegistration(userId: String?, role: String?) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val environment = ApiClient.environment()
    LaunchedEffect(userId, role, environment) {
        MobilityPush.setDriverSession(context, userId, role)
        RidePush.setSession(context, userId, role)
        if (!MobilityPush.production(context)) { MobilityPush.pauseAutomaticRegistration(context); return@LaunchedEffect }
        MobilityPush.ensureChannel(context)
        while (true) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && MobilityPush.allowed(context)) {
                try {
                    if (userId != null && role.equals("DRIVER", true)) MobilityPush.syncDriver(context, userId)
                    if (userId != null && role in setOf("DRIVER", "PASSENGER")) RidePush.sync(context, userId, role!!)
                    MobilityPush.syncGuestRequests(context)
                } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Retry next foreground interval. */ }
            }
            delay(5 * 60_000L)
        }
    }
}

/** Per-device choice for the sound used by new taxi and group-ride notifications. */
@Composable fun MobilityAlertToneSettings(driver: Boolean, language: AppLanguage, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var selected by remember(driver) { mutableStateOf(MobilityAlertTone.selected(context, driver)) }
    var open by remember { mutableStateOf(false) }
    val title = intercityText(language, if (driver) "Request alert sound" else "Ride alert sound",
        if (driver) "የጥያቄ ማሳወቂያ ድምፅ" else "የጉዞ ማሳወቂያ ድምፅ",
        if (driver) "Sagalee beeksisa gaaffii" else "Sagalee beeksisa imalaa")
    val soundName = when (selected) {
        MobilityAlertTone.RING -> intercityText(language, "Classic ring", "የስልክ ደወል", "Bilbila")
        MobilityAlertTone.BEEP -> intercityText(language, "Short beep", "አጭር ቢፕ", "Beep gabaabaa")
    }
    Surface(color = Color.White, contentColor = CommuteInk, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFFE2E8E7)), modifier = modifier.fillMaxWidth().clickable { open = true }) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.VolumeUp, null, tint = CommuteTeal)
            Column(Modifier.weight(1f).padding(start = 11.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = CommuteInk)
                Text(soundName, fontSize = 12.sp, color = CommuteTeal)
            }
            Text(intercityText(language, "Change", "ቀይር", "Jijjiiri"), color = CommuteTeal, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(title, color = CommuteInk, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(intercityText(language, "Choose the sound for new requests and ride updates on this phone.",
                    "በዚህ ስልክ ለአዳዲስ ጥያቄዎች እና የጉዞ ማሻሻያዎች ድምፅ ይምረጡ።",
                    "Bilbila kana irratti gaaffii haaraa fi haaromsa imalaa kamiin akka dhageessisu filadhu."), color = Color(0xFF627385), fontSize = 13.sp)
                MobilityAlertTone.values().forEach { tone ->
                    val label = if (tone == MobilityAlertTone.RING)
                        intercityText(language, "Classic ring", "የስልክ ደወል", "Bilbila")
                    else intercityText(language, "Short beep", "አጭር ቢፕ", "Beep gabaabaa")
                    Row(Modifier.fillMaxWidth().clickable {
                        MobilityAlertTone.select(context, tone, driver)
                        MobilityPush.ensureChannel(context)
                        selected = tone; open = false
                    }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected == tone, onClick = {
                            MobilityAlertTone.select(context, tone, driver)
                            MobilityPush.ensureChannel(context)
                            selected = tone; open = false
                        })
                        Text(label, Modifier.padding(start = 7.dp), color = CommuteInk, fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(intercityText(language, "Done", "ጨርስ", "Xumuri"), color = CommuteTeal) } },
        containerColor = Color.White, tonalElevation = 0.dp, shape = RoundedCornerShape(24.dp)
    )
}

/** No OS dialog is opened until the driver/requester taps this control. */
@Composable fun MobilityNotificationOptIn(driver: Boolean, modifier: Modifier = Modifier, language: AppLanguage = AppLanguage.ENGLISH, taxi: Boolean = false) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var granted by remember { mutableStateOf(MobilityPush.notificationsGranted(context)) }
    var attempted by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        granted = allowed && MobilityPush.notificationsGranted(context)
        if (granted) MobilityPush.onPermissionGranted(context)
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
            val now = MobilityPush.notificationsGranted(context)
            if (now && !granted) MobilityPush.onPermissionGranted(context)
            granted = now
        } }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    if (!MobilityPush.production(context) || !MobilityPush.configured(context) || granted) return
    fun t(en: String, am: String, om: String) = intercityText(language, en, am, om)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(if (taxi) t("Get ride acceptance, arrival and boarding updates even when Passo is closed.", "ፓሶ ተዘግቶም የጉዞ መቀበል፣ መድረስ እና መሳፈር ማሳወቂያዎችን ያግኙ።", "Passo cufamee jirullee beeksisa fudhatama, ga'umsaa fi yaabbannoo argadhu.") else if (driver) t("Get requests near your last known location, even when Passo is closed. Open Passo to refresh your location.", "ፓሶ ተዘግቶም በመጨረሻ ከታወቀው አካባቢዎ አቅራቢያ ያሉ ጥያቄዎችን ያግኙ። አካባቢዎን ለማዘመን ፓሶን ይክፈቱ።", "Passo cufamee jirullee bakka kee dhuma irratti beekame naannawatti gaaffii geejjibaa argadhu. Bakka kee haaromsuuf Passo bani.") else t("Get driver quotes and arrival updates even when Passo is closed.", "ፓሶ ተዘግቶም የአሽከርካሪ ዋጋ እና የመድረሻ ማሳወቂያዎችን ያግኙ።", "Passo cufamee jirullee gatii fi odeeffannoo ga'umsaa argadhu."), color = CommuteInk, fontSize = 12.sp)
        OutlinedButton(onClick = {
            MobilityPush.ensureChannel(context)
            if (Build.VERSION.SDK_INT >= 33 && !attempted) { attempted = true; permission.launch(Manifest.permission.POST_NOTIFICATIONS) }
            else runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
        }) {
            Icon(Icons.Default.NotificationsActive, null, tint = CommuteTeal, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(7.dp))
            Text(if (attempted) t("Open notification settings", "የማሳወቂያ ቅንብሮችን ይክፈቱ", "Qindaa'ina beeksisaa bani") else t("Enable request updates", "የጥያቄ ማሳወቂያዎችን አንቃ", "Beeksisa gaaffii bani"), color = CommuteTeal)
        }
    }
}
