package com.example.ui.screens.driver

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.localization.AppLanguage
import com.example.ui.screens.passenger.CommuteMint

internal fun driverLocationDisclosure(language: AppLanguage): String = when (language) {
    AppLanguage.AMHARIC -> "PASSO የአሁኑን ቦታዎን ለማሳየት እና የመሳፈሪያ ቦታ መድረስዎን ለማረጋገጥ አካባቢዎን ይጠቀማል። በንቁ ጉዞ ወቅት ማጋራትን ሲመርጡ፣ መተግበሪያው በጀርባ ሲሠራ ወይም ማያ ገጹ ሲዘጋም የአካባቢ ውሂብ ይሰበስባል እና ለተመደቡ ተሳፋሪዎች እና ለተፈቀደላቸው የትራንስፖርት አስተባባሪዎች ያጋራል። ለእያንዳንዱ ጉዞ ማጋራትን በራስዎ ያስጀምራሉ። ቋሚ ማሳወቂያው ማጋራትን ያሳያል፣ እና በመተግበሪያው ወይም በማሳወቂያው ላይ ማቆም ይችላሉ። ጉዞው ሲያልቅ ወይም ሲወጡ ማጋራት ይቆማል።"
    AppLanguage.OROMO -> "PASSO bakka ammaa kee agarsiisuu fi bakka yaabbannoo ga'uu kee mirkaneessuuf bakka kee fayyadama. Imala hojii irra jiru keessatti qooduu yoo filatte, yeroo appiin duubaan hojjetu yookaan iskiriiniin cufamu illee odeeffannoo bakka kee walitti qaba, imaltoota ramadamanii fi qindeessitoota geejjibaa hayyamamaniif qooda. Qooduu imala tokkoon tokkoof ofii keetiin jalqabda. Beeksisni yeroo hunda mul'atu qooduu agarsiisa; appii yookaan beeksisa irraa dhaabuu dandeessa. Imalli yoo xumurame yookaan yoo baate qooduun ni dhaabata."
    else -> "PASSO uses your location to show your current position and confirm arrival at the boarding point. If you choose trip location sharing, it also collects location data during the active trip while the app is minimized or not in use, including with the screen locked, and shares it with assigned passengers and authorized transport dispatch staff. You start sharing separately for each trip. An ongoing notification shows sharing; you can stop it in the app or notification. Sharing ends when the trip ends or you sign out."
}

@Composable
internal fun DriverLocationDisclosureText(language: AppLanguage) {
    Text(driverLocationDisclosure(language), modifier = Modifier.verticalScroll(rememberScrollState()).testTag("driver_location_disclosure"))
}

/** The callback requests Android permission only after the user has read and accepted this disclosure. */
@Composable
internal fun DriverGpsPermissionControl(language: AppLanguage, onRequestPermissions: () -> Unit) {
    var explain by remember { mutableStateOf(false) }
    fun t(en: String, am: String, om: String) = when (language) {
        AppLanguage.AMHARIC -> am
        AppLanguage.OROMO -> om
        else -> en
    }
    Surface(shape = RoundedCornerShape(18.dp), color = CommuteMint) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("Location for boarding", "ለመሳፈር አካባቢ", "Bakka yaabbannoof"), fontWeight = FontWeight.Bold)
            Text(t("Enable GPS when you are ready to confirm your pickup point.", "የመሳፈሪያ ቦታዎን ለማረጋገጥ ሲዘጋጁ GPS ያብሩ።", "Bakka yaabbannoo kee mirkaneessuuf yeroo qophooftu GPS bani."))
            Button(onClick = { explain = true }, modifier = Modifier.testTag("driver_enable_location")) {
                Text(t("Enable location", "አካባቢ ፍቀድ", "Bakka hayyami"))
            }
        }
    }
    if (explain) AlertDialog(onDismissRequest = { explain = false },
        title = { Text(t("How PASSO uses location", "PASSO አካባቢን እንዴት ይጠቀማል", "PASSO bakka akkamitti fayyadama")) },
        text = { DriverLocationDisclosureText(language) },
        confirmButton = { TextButton(onClick = { explain = false; onRequestPermissions() }, modifier = Modifier.testTag("driver_location_disclosure_allow")) {
            Text(t("Continue to permission", "ወደ ፈቃድ ቀጥል", "Gara hayyamaatti itti fufi"))
        } },
        dismissButton = { TextButton(onClick = { explain = false }, modifier = Modifier.testTag("driver_location_disclosure_cancel")) {
            Text(t("Not now", "አሁን አይደለም", "Amma miti"))
        } })
}
