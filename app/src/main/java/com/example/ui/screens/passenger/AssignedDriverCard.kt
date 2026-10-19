package com.example.ui.screens.passenger

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.api.ApiClient
import com.example.ui.screens.profile.AuthenticatedProfilePhoto
import com.example.ui.theme.Slate600
import com.example.ui.theme.Slate900
import com.example.ui.theme.TransportGreenPrimary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import retrofit2.http.GET

data class AssignedDriver(
    val userId: String,
    val fullName: String,
    val phone: String,
    val vehicleType: String,
    val vehicleColor: String,
    val plateNumber: String,
    val model: String,
    val routeName: String
)

data class AssignedDriverResponse(val success: Boolean, val driver: AssignedDriver? = null)
interface AssignedDriverApi {
    @GET("api/commute/my-driver")
    suspend fun getDriver(): retrofit2.Response<AssignedDriverResponse>
}

private fun String.availableDetail(): String? = trim().takeUnless {
    it.lowercase() in setOf("", "not provided", "not set", "unknown", "null", "undefined", "n/a", "-")
}

internal fun friendlyVehicleType(raw: String): String? = when (raw.uppercase()) {
    "MINIVAN_8" -> "Minivan · 8 seats"
    "MINIBUS_14" -> "Minibus · 14 seats"
    "HIGER_24" -> "Bus · 24 seats"
    "ANBESSA_BUS_30" -> "Bus · 30 seats"
    else -> raw.availableDetail()?.replace('_', ' ')?.lowercase()?.replaceFirstChar { it.titlecase() }
}

@Composable
fun AssignedDriverCard(
    subscription: com.example.data.entity.SubscriptionEntity? = null,
    route: com.example.data.entity.RouteEntity? = null,
    routeName: String? = null,
    stops: List<com.example.data.entity.RouteStopEntity> = emptyList(),
    lang: com.example.core.localization.AppLanguage = com.example.core.localization.AppLanguage.ENGLISH,
    onShowPass: () -> Unit = {},
    onRenew: () -> Unit = {},
    paymentAvailable: Boolean = false
) {
    var driver by remember { mutableStateOf<AssignedDriver?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val api = remember { ApiClient.createFeatureService(AssignedDriverApi::class.java) }
    LaunchedEffect(retry) {
        while (true) {
            try {
                val response = api.getDriver()
                val body = response.body()
                if (response.isSuccessful && body?.success == true) {
                    driver = body.driver
                    error = null
                } else {
                    if (response.code() == 401 || response.code() == 403) driver = null
                    error = if (response.code() == 401) "Sign in again to see your driver." else "Could not load your driver. Try again."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Check your connection to load your driver."
            } finally {
                loading = false
            }
            delay(30_000)
        }
    }

    CommuterActiveTripCard(subscription, route, routeName, stops, driver, loading, error, lang,
        onRetry = { retry++ }, onShowPass = onShowPass, onRenew = onRenew, paymentAvailable = paymentAvailable)
}