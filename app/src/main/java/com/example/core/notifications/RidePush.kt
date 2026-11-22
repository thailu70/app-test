package com.example.core.notifications

import android.content.Context
import com.example.data.api.ApiClient
import com.example.data.api.MobilityPushApi
import com.example.data.api.MobilityPushInput
import kotlinx.coroutines.*

internal fun canConfirmRidePush(owner: String, role: String, activeOwner: String?, activeRole: String?,
    bearer: String, currentBearer: String?, currentSession: Boolean, permitted: Boolean): Boolean =
    owner == activeOwner && role == activeRole && role in setOf("DRIVER", "PASSENGER") &&
        bearer == currentBearer && currentSession && permitted

/** Account-bound taxi/shared notifications, independent of guest cargo capabilities. */
internal object RidePush {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    @Volatile private var known = false
    @Volatile private var activeOwner: String? = null
    @Volatile private var activeRole: String? = null
    fun setSession(context: Context, userId: String?, role: String?) {
        val nextRole = role?.takeIf { it in setOf("DRIVER", "PASSENGER") && MobilityPush.production(context) }
        val next = userId?.takeIf { nextRole != null }
        synchronized(lock) {
            if (known && next == activeOwner && nextRole == activeRole) return
            known = true; activeOwner = next; activeRole = nextRole
            val store = MobilityPushStore(context)
            runCatching { val saved = store.read(); if (saved.rideOwner != next || saved.rideRole != nextRole) store.revokeRides() }
                .onFailure { runCatching { store.revokeRides() } }
        }
    }
    suspend fun sync(context: Context, owner: String, role: String) {
        if (activeOwner != owner || activeRole != role || !MobilityPush.allowed(context)) return
        val bearer = ApiClient.getAuthToken()?.takeIf(String::isNotBlank) ?: return
        val session = ApiClient.sessionScope()
        val api = ApiClient.createSessionService(MobilityPushApi::class.java, bearer)
        val token = MobilityPush.currentToken(context) ?: return
        val point = MobilityPush.recentLocation(context)
        val result = api.registerRides(MobilityPushInput(token, point?.first, point?.second))
        if (result.isSuccessful && result.body()?.success == true) synchronized(lock) {
            MobilityPushStore(context).confirmRides(owner, role) {
                canConfirmRidePush(owner, role, activeOwner, activeRole, bearer, ApiClient.getAuthToken(), ApiClient.isCurrent(session), MobilityPush.allowed(context))
            }
        }
    }
    fun onPermissionGranted(context: Context) {
        val owner = activeOwner ?: return; val role = activeRole ?: return
        scope.launch { runCatching { sync(context.applicationContext, owner, role) } }
    }
    fun beforeLogout(context: Context) {
        val saved = synchronized(lock) {
            known = true; activeOwner = null; activeRole = null
            val store = MobilityPushStore(context)
            val value = runCatching { store.read() }.getOrNull()
            runCatching { store.revokeRides() }; value
        }
        val bearer = ApiClient.getAuthToken()?.takeIf { MobilityPush.production(context) && it.isNotBlank() } ?: return
        val token = saved?.token?.takeIf(String::isNotBlank) ?: return
        val api = ApiClient.createSessionService(MobilityPushApi::class.java, bearer)
        scope.launch { runCatching { api.removeRides(MobilityPushInput(token)) } }
    }
    fun deliveryAllowed(context: Context, role: String): Boolean {
        if (role !in setOf("DRIVER", "PASSENGER") || !MobilityPush.production(context)) return false
        if (known && (activeOwner == null || activeRole != role)) return false
        return runCatching { val store = MobilityPushStore(context); val saved = store.read()
            !store.ridesRevoked() && saved.rideOwner != null && saved.rideRole == role && (!known || saved.rideOwner == activeOwner)
        }.getOrDefault(false)
    }
}
