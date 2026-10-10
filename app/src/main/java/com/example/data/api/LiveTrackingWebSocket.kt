package com.example.data.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class VehicleLocationEvent(
    val vehicleId: String,
    val latitude: Double,
    val longitude: Double,
    val speed: Double,
    val currentStop: String,
    val timestamp: String
)

data class BoardingEvent(
    val checkinId: String,
    val passengerName: String,
    val stopName: String,
    val vehicleId: String,
    val currentOccupancy: Int,
    val capacityLimit: Int,
    val isFull: Boolean
)

data class StopArrivalEvent(
    val tripId: String,
    val vehicleId: String,
    val routeId: String,
    val stopName: String
)

object LiveTrackingWebSocket {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _latestLocation = MutableStateFlow<VehicleLocationEvent?>(null)
    val latestLocation: StateFlow<VehicleLocationEvent?> = _latestLocation.asStateFlow()

    private val _boardingEvents = MutableSharedFlow<BoardingEvent>(extraBufferCapacity = 10)
    val boardingEvents: SharedFlow<BoardingEvent> = _boardingEvents.asSharedFlow()

    private val _stopArrivalEvents = MutableSharedFlow<StopArrivalEvent>(extraBufferCapacity = 10)
    val stopArrivalEvents: SharedFlow<StopArrivalEvent> = _stopArrivalEvents.asSharedFlow()

    fun connect(baseUrl: String = ApiClient.getBaseUrl()) {
        if (webSocket != null) return

        val authToken = ApiClient.getAuthToken()
        val baseWsUrl = baseUrl
            .replace("http://", "ws://")
            .replace("https://", "wss://")
            .let { if (it.endsWith("/")) "${it}ws" else "$it/ws" }

        // Do not attach bearer tokens as URL query parameters: proxies may log them.
        // The first in-band AUTHENTICATE message is sent from onOpen below.
        val request = Request.Builder().url(baseWsUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _isConnected.value = true
                authToken?.let { token ->
                    val authMsg = JSONObject().apply {
                        put("type", "AUTHENTICATE")
                        put("token", token)
                    }
                    webSocket.send(authMsg.toString())
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "VEHICLE_LOCATION_UPDATE" -> {
                            val event = VehicleLocationEvent(
                                vehicleId = json.optString("vehicleId"),
                                latitude = json.optDouble("latitude"),
                                longitude = json.optDouble("longitude"),
                                speed = json.optDouble("speed", 0.0),
                                currentStop = json.optString("currentStop"),
                                timestamp = json.optString("timestamp")
                            )
                            _latestLocation.value = event
                        }
                        "PASSENGER_BOARDED" -> {
                            val event = BoardingEvent(
                                checkinId = json.optString("checkinId"),
                                passengerName = json.optString("passengerName"),
                                stopName = json.optString("stopName"),
                                vehicleId = json.optString("vehicleId"),
                                currentOccupancy = json.optInt("currentOccupancy"),
                                capacityLimit = json.optInt("capacityLimit"),
                                isFull = json.optBoolean("isFull")
                            )
                            scope.launch { _boardingEvents.emit(event) }
                        }
                        "VEHICLE_STOP_ARRIVAL" -> {
                            val event = StopArrivalEvent(
                                tripId = json.optString("tripId"),
                                vehicleId = json.optString("vehicleId"),
                                routeId = json.optString("routeId"),
                                stopName = json.optString("stopName")
                            )
                            scope.launch { _stopArrivalEvents.emit(event) }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore malformed payloads
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                _isConnected.value = false
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _isConnected.value = false
                LiveTrackingWebSocket.webSocket = null
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _isConnected.value = false
                LiveTrackingWebSocket.webSocket = null
            }
        })
    }

    fun sendDriverLocation(
        vehicleId: String,
        driverId: String,
        tripId: String,
        latitude: Double,
        longitude: Double,
        speed: Double = 0.0,
        currentStop: String = ""
    ) {
        val payload = JSONObject().apply {
            put("type", "DRIVER_LOCATION_UPDATE")
            put("vehicleId", vehicleId)
            put("driverId", driverId)
            put("tripId", tripId)
            put("latitude", latitude)
            put("longitude", longitude)
            put("speed", speed)
            put("currentStop", currentStop)
        }
        webSocket?.send(payload.toString())
    }

    fun disconnect() {
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        _isConnected.value = false
    }
}
