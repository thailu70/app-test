package com.example.ui.screens.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.localization.AppLanguage
import com.example.data.api.CommutePlace
import com.example.data.repository.DriverApplicationRepository
import com.example.ui.screens.passenger.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.Locale

internal fun driverQuoteKey(draft: AuthDraft) = listOf(draft.pickup.trim(), draft.destination.trim(), draft.vehicleType).joinToString("\u0000")
internal fun driverMonthlyFee(draft: AuthDraft): Double? {
    val quote = draft.quote?.takeIf { draft.quotedKey == driverQuoteKey(draft) } ?: return null
    val fee = if (quote.price.mode == "FIXED") quote.price.amountEtb else draft.monthlyFee.toDoubleOrNull()
    return fee?.takeIf { it.isFinite() && it > 0 && it <= 1_000_000 && kotlin.math.abs(it * 100 - kotlin.math.round(it * 100)) < 0.000001 }
}
internal fun formatDriverFee(value: Double) = String.format(Locale.US, "%.2f", value)

@Composable
internal fun DriverRouteProposalFields(draft: AuthDraft, applications: DriverApplicationRepository,
    language: AppLanguage, enabled: Boolean) {
    fun t(en: String, am: String, om: String) = authText(language, en, am, om)
    var departures by remember { mutableStateOf<List<CommutePlace>>(emptyList()) }
    var destinations by remember { mutableStateOf<List<CommutePlace>>(emptyList()) }
    var suggestionError by remember { mutableStateOf(false) }
    LaunchedEffect(draft.pickup) {
        delay(250)
        try { departures = applications.suggestions("departure", draft.pickup); suggestionError = false }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { departures = emptyList(); suggestionError = true }
    }
    LaunchedEffect(draft.pickup, draft.destination) {
        delay(250)
        try { destinations = applications.suggestions("destination", draft.destination, draft.pickup) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { destinations = emptyList(); suggestionError = true }
    }
    LaunchedEffect(draft.pickup, draft.destination, draft.vehicleType, draft.quoteRefresh) {
        draft.quote = null; draft.quotedKey = null; draft.quoteError = null
        draft.monthlyFee = ""
        if (draft.pickup.trim().length < 2 || draft.destination.trim().length < 2 || draft.vehicleType.isBlank()) {
            draft.quoteLoading = false
            return@LaunchedEffect
        }
        draft.quoteLoading = true
        val key = driverQuoteKey(draft)
        try {
            delay(350)
            val quote = applications.quote(draft.pickup, draft.destination, draft.vehicleType)
            if (driverQuoteKey(draft) == key) {
                draft.quote = quote; draft.quotedKey = key
                if (quote.price.mode == "FIXED") draft.monthlyFee = quote.price.amountEtb?.let(::formatDriverFee).orEmpty()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { draft.quoteError = failure.message ?: "Could not check the monthly fee." }
        finally { draft.quoteLoading = false }
    }
    Text(t("Propose your daily route", "የዕለት መስመርዎን ይጠቁሙ", "Karaa guyyaa kee yaada dhiheessi"), color = CommuteInk)
    AuthField(draft.pickup, { draft.pickup = it.take(120) }, t("Pickup / departure", "መነሻ", "Bakka ka'umsaa"),
        Modifier.fillMaxWidth().testTag("auth_driver_pickup_input"), enabled = enabled, language = language, leadingIcon = Icons.Default.LocationOn)
    departures.filter { !it.label.equals(draft.pickup.trim(), true) }.take(3).forEach { place ->
        TextButton(onClick = { draft.pickup = place.label }, enabled = enabled, modifier = Modifier.testTag("auth_departure_suggestion_${place.label}")) { Text(place.label, color = CommuteTeal) }
    }
    AuthField(draft.destination, { draft.destination = it.take(120) }, t("Destination", "መድረሻ", "Bakka ga'umsaa"),
        Modifier.fillMaxWidth().testTag("auth_driver_destination_input"), enabled = enabled, language = language, leadingIcon = Icons.Default.LocationOn)
    destinations.filter { !it.label.equals(draft.destination.trim(), true) }.take(3).forEach { place ->
        TextButton(onClick = { draft.destination = place.label }, enabled = enabled, modifier = Modifier.testTag("auth_destination_suggestion_${place.label}")) { Text(place.label, color = CommuteTeal) }
    }
    if (suggestionError) Text(t("Place suggestions are unavailable. You can still enter your route.", "የቦታ አስተያየቶች አልተገኙም። መስመርዎን መጻፍ ይችላሉ።", "Yaadni bakka hin jiru. Karaa kee barreessuu dandeessa."), color = CommuteInk, fontSize = 11.sp)
    if (draft.quoteLoading) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(t("Checking the monthly vehicle fee…", "ወርሃዊ የተሽከርካሪ ዋጋን በማረጋገጥ ላይ…", "Kaffaltii konkolaataa ji'aa ilaalaa jira…"), color = CommuteInk, fontSize = 12.sp)
    }
    draft.quote?.let { quote ->
        AuthField(draft.monthlyFee, { draft.monthlyFee = it.take(12) },
            t("Monthly fee · whole vehicle · ETB", "ወርሃዊ ዋጋ · ሙሉ ተሽከርካሪ · ብር", "Kaffaltii ji'aa · konkolaataa guutuu · ETB"),
            Modifier.fillMaxWidth().testTag("auth_driver_monthly_fee"), enabled = enabled, language = language,
            leadingIcon = Icons.Default.Payments, readOnly = quote.price.mode == "FIXED", decimal = true)
        Text(if (quote.price.mode == "FIXED") t("Existing route: ${quote.price.passengerCapacity} passenger seats × ETB ${quote.price.passengerMonthlyFareEtb}. This fee is fixed.", "ያለ መስመር፦ ${quote.price.passengerCapacity} መቀመጫ × ${quote.price.passengerMonthlyFareEtb} ብር። ዋጋው ቋሚ ነው።", "Karaa jiru: teessoo ${quote.price.passengerCapacity} × ETB ${quote.price.passengerMonthlyFareEtb}. Kaffaltiin dhaabbataa dha.")
            else t("New or unmatched route: propose the fee for the whole vehicle per month.", "አዲስ መስመር፦ ለሙሉ ተሽከርካሪው የወር ዋጋ ይጻፉ።", "Karaa haaraa: kaffaltii konkolaataa guutuu ji'aan dhiheessi."),
            color = CommuteTeal, fontSize = 12.sp, modifier = Modifier.testTag(if (quote.price.mode == "FIXED") "auth_driver_fee_fixed" else "auth_driver_fee_proposal"))
    }
    draft.quoteError?.let {
        Text(it, color = Color(0xFFAC2F2F), fontSize = 12.sp)
        TextButton(onClick = { draft.quoteRefresh++ }, enabled = enabled) { Text(t("Check fee again", "ዋጋን እንደገና ያረጋግጡ", "Kaffaltii irra deebi'ii ilaali")) }
    }
}
