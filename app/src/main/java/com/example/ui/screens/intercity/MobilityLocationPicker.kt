package com.example.ui.screens.intercity

import android.Manifest
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.core.localization.AppLanguage
import com.example.data.api.ApiClient
import com.example.data.api.MobilityApi
import com.example.data.api.MobilityPlace
import com.example.ui.screens.passenger.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay

/** The native map is clipped both at its Compose slot and at its Android view bounds. */
private class BoundedMobilityMap(context: android.content.Context, map: MapView) : FrameLayout(context) {
    init {
        tag = "mobility_native_map_frame"
        clipChildren = true; clipToPadding = true
        // Keyboard/orientation layout changes move the same map to another bounded slot.
        (map.parent as? ViewGroup)?.removeView(map)
        addView(map, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    override fun dispatchDraw(canvas: Canvas) {
        val checkpoint = canvas.save()
        canvas.clipRect(0, 0, width, height)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(checkpoint)
    }
}

/** Real place search and map selection. A default viewport never counts as a selected location. */
@Composable
internal fun MobilityLocationPicker(
    title: String, initial: Pair<Double, Double>?, onDismiss: () -> Unit, onChoose: (Pair<Double, Double>) -> Unit,
    onPlaceName: (String) -> Unit = {}, language: AppLanguage = AppLanguage.ENGLISH, initialLabel: String = "",
    providedApi: MobilityApi? = null, tileDownloadsEnabled: Boolean = true
) {
    val context = LocalContext.current
    var clearSearchFocus by remember { mutableStateOf<() -> Unit>({}) }
    val scope = rememberCoroutineScope()
    val api = remember(providedApi) { providedApi ?: ApiClient.createFeatureService(MobilityApi::class.java) }
    var activeLanguage by remember(language) { mutableStateOf(language) }
    fun t(en: String, am: String, om: String) = intercityText(activeLanguage, en, am, om)
    val destination = remember(title) { title.contains("destination", true) || title.contains("መድረሻ") || title.contains("Ga'umsa", true) }
    val chooseTitle = if (destination) t("Choose destination location", "መድረሻ ቦታ ይምረጡ", "Bakka ga'umsaa filadhu")
        else t("Choose pickup location", "መነሻ ቦታ ይምረጡ", "Bakka ka'umsaa filadhu")
    val fallbackName = if (destination) t("Selected destination location", "የተመረጠ መድረሻ ቦታ", "Bakka ga'umsaa filatame") else t("Selected pickup location", "የተመረጠ መነሻ ቦታ", "Bakka ka'umsaa filatame")
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<MobilityPlace>>(emptyList()) }
    var searchMessage by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(initial) }
    var selectedName by remember { mutableStateOf(initialLabel) }
    var dismissedQuery by remember { mutableStateOf<String?>(null) }
    var touchedMap by remember { mutableStateOf(false) }
    var ignoreMapEventsUntil by remember { mutableLongStateOf(0L) }
    val map = remember {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            tag = "mobility_native_map"
            setTileSource(TileSourceFactory.MAPNIK); setUseDataConnection(tileDownloadsEnabled)
            setMultiTouchControls(true); setBuiltInZoomControls(false)
            controller.setZoom(if (initial == null) 12.0 else 15.0)
            controller.setCenter(initial?.let { GeoPoint(it.first, it.second) } ?: GeoPoint(9.03, 38.75))
        }
    }
    fun choosePoint(point: Pair<Double, Double>, name: String) {
        selected = point; selectedName = name; dismissedQuery = query
        suggestions = emptyList(); searchMessage = null; clearSearchFocus()
        ignoreMapEventsUntil = SystemClock.uptimeMillis() + 350
        map.controller.setCenter(GeoPoint(point.first, point.second)); map.invalidate()
    }
    fun currentLocation() { scope.launch {
        locating = true
        try {
            val fix = freshMobilityLocation(context)
            choosePoint(fix.latitude to fix.longitude, t("Current location", "አሁን ያሉበት ቦታ", "Bakka amma jirtu"))
            map.controller.setZoom(16.0)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { searchMessage = e.message ?: t("Location unavailable. Search for a place or tap the map.", "ቦታውን ይፈልጉ ወይም ካርታውን ይንኩ።", "Bakka barbaadi yookaan kaartaa tuqi.") }
        finally { locating = false }
    } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasMobilityLocationPermission(context)) currentLocation()
        else searchMessage = t("Allow location permission, or search for your place.", "የቦታ ፈቃድ ይስጡ ወይም ቦታውን ይፈልጉ።", "Hayyama bakka kenni yookaan bakka kee barbaadi.")
    }
    fun locate() {
        if (hasMobilityLocationPermission(context)) currentLocation()
        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    LaunchedEffect(query) {
        suggestions = emptyList(); searchMessage = null
        if (query.trim().length >= 2 && query != dismissedQuery) {
            delay(400); searching = true
            try {
                val response = mobilityBody(api.locations(query.trim()))
                check(response.success) { response.error ?: "Search unavailable." }
                suggestions = response.places
                if (response.places.isEmpty()) searchMessage = t("No matching place. Try a nearby landmark or tap the map.", "ቦታው አልተገኘም። አቅራቢያ ያለ ምልክት ይፈልጉ።", "Bakki hin argamne. Mallattoo dhihoo barbaadi.")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { searchMessage = t("Search is unavailable. You can still tap the map.", "ፍለጋ አይገኝም። በካርታው ላይ ይምረጡ።", "Barbaaduun hin argamu. Kaartaa irratti filadhu.") }
            finally { searching = false }
        } else searching = false
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(map, owner) {
        val overlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                choosePoint(p.latitude to p.longitude, t("Selected map point", "በካርታ የተመረጠ ቦታ", "Bakka kaartaa filatame")); return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean = singleTapConfirmedHelper(p)
        })
        map.overlays.add(0, overlay)
        map.setOnTouchListener { _, event -> if (event.actionMasked == MotionEvent.ACTION_DOWN) touchedMap = true; false }
        val mapListener = object : MapListener {
            override fun onScroll(event: ScrollEvent): Boolean {
                if (touchedMap && SystemClock.uptimeMillis() > ignoreMapEventsUntil) {
                    selected = map.mapCenter.latitude to map.mapCenter.longitude
                    selectedName = t("Selected map point", "በካርታ የተመረጠ ቦታ", "Bakka kaartaa filatame")
                    suggestions = emptyList(); clearSearchFocus()
                }
                return false
            }
            override fun onZoom(event: ZoomEvent): Boolean = false
        }
        map.addMapListener(mapListener)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) map.onResume()
            if (event == Lifecycle.Event.ON_PAUSE) map.onPause()
        }
        owner.lifecycle.addObserver(observer); map.onResume()
        onDispose { owner.lifecycle.removeObserver(observer); map.removeMapListener(mapListener); map.setOnTouchListener(null); map.onPause(); map.onDetach() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val dialogFocus = LocalFocusManager.current
        DisposableEffect(dialogFocus) { clearSearchFocus = { dialogFocus.clearFocus() }; onDispose { clearSearchFocus = {} } }
        Surface(color = CommuteCream, modifier = Modifier.fillMaxSize().testTag("mobility_location_picker")) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                val landscape = maxWidth > maxHeight
                val availableHeight = maxHeight
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(start = 3.dp, end = 14.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss, modifier = Modifier.testTag("mobility_location_back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Back", "ተመለስ", "Deebi'i"), tint = CommuteInk) }
                        CommuterWordmark(Modifier.width(if (landscape) 128.dp else 115.dp))
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.weight(1f).widthIn(max = 270.dp)) { CommuterLanguageSwitch(activeLanguage, { activeLanguage = it }, compact = true) }
                    }
                    Text(chooseTitle, Modifier.padding(horizontal = 20.dp, vertical = if (landscape) 3.dp else 10.dp), fontSize = if (landscape) 20.sp else 23.sp,
                        fontWeight = FontWeight.ExtraBold, color = CommuteInk, maxLines = 2)
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        if (landscape) {
                            Column(Modifier.weight(0.85f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                LocationSearch(query, { query = it.take(120); dismissedQuery = null }, searching, locating, ::locate, suggestions, searchMessage, { choosePoint(it.latitude to it.longitude, it.name) }, ::t)
                                LocationConfirmation(selected != null, selectedName, destination, { selected?.let { onPlaceName(selectedName.ifBlank { fallbackName }); onChoose(it) } }, ::t)
                            }
                            LocationMap(map, selected != null, locating, ::locate, Modifier.weight(1.15f).fillMaxHeight())
                        } else {
                            Column(Modifier.fillMaxSize()) {
                                if (availableHeight < 560.dp) {
                                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) { LocationSearch(query, { query = it.take(120); dismissedQuery = null }, searching, locating, ::locate, suggestions, searchMessage, { choosePoint(it.latitude to it.longitude, it.name) }, ::t) }
                                        LocationMap(map, selected != null, locating, ::locate, Modifier.fillMaxWidth().height(200.dp))
                                    }
                                } else {
                                    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) { LocationSearch(query, { query = it.take(120); dismissedQuery = null }, searching, locating, ::locate, suggestions, searchMessage, { choosePoint(it.latitude to it.longitude, it.name) }, ::t) }
                                    LocationMap(map, selected != null, locating, ::locate, Modifier.weight(1f).fillMaxWidth())
                                }
                                LocationConfirmation(selected != null, selectedName, destination, { selected?.let { onPlaceName(selectedName.ifBlank { fallbackName }); onChoose(it) } }, ::t)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationSearch(query: String, onQuery: (String) -> Unit, searching: Boolean, locating: Boolean, onLocate: () -> Unit,
    suggestions: List<MobilityPlace>, message: String?, onSelect: (MobilityPlace) -> Unit, t: (String, String, String) -> String) {
    OutlinedTextField(query, onQuery, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("mobility_location_search"), shape = RoundedCornerShape(14.dp),
        placeholder = { Text(t("Search city, area or landmark", "ከተማ፣ አካባቢ ወይም ምልክት ይፈልጉ", "Magaalaa, naannoo yookaan mallattoo"), fontSize = 14.sp) },
        leadingIcon = { Icon(Icons.Default.Search, null, tint = Color(0xFF4D6577)) },
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CommuteTeal, unfocusedBorderColor = Color(0xFFCED5D5), focusedContainerColor = Color.White, unfocusedContainerColor = Color.White))
    if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp), color = CommuteTeal)
    if (suggestions.isNotEmpty()) Column(Modifier.fillMaxWidth().heightIn(max = 154.dp).verticalScroll(rememberScrollState()).testTag("mobility_location_suggestions")) {
        suggestions.forEachIndexed { index, place -> TextButton(onClick = { onSelect(place) }, modifier = Modifier.fillMaxWidth().testTag("mobility_location_suggestion_$index"), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 9.dp)) {
            Icon(Icons.Default.Place, null, tint = CommuteTeal, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(9.dp))
            Text(place.name, color = CommuteInk, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
        } }
    }
    TextButton(onClick = onLocate, enabled = !locating, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp), modifier = Modifier.fillMaxWidth().testTag("mobility_location_current")) {
        Surface(shape = CircleShape, color = CommuteMint, modifier = Modifier.size(34.dp)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.MyLocation, null, tint = CommuteTeal, modifier = Modifier.size(23.dp)) } }
        Spacer(Modifier.width(10.dp))
        Text(if (locating) t("Finding your location…", "ቦታዎን በማግኘት ላይ…", "Bakka kee barbaadaa…") else t("Use my current location", "አሁን ያለሁበትን ቦታ ተጠቀም", "Bakka amma jiru fayyadami"), color = CommuteTeal, modifier = Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
    message?.let { Text(it, color = Color(0xFF596D7B), fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(bottom = 8.dp).testTag("mobility_location_message")) }
}

@Composable
private fun LocationMap(map: MapView, selected: Boolean, locating: Boolean, onLocate: () -> Unit, modifier: Modifier) {
    Box(modifier.clipToBounds().background(Color(0xFFE8EEE8)).testTag("mobility_location_map")) {
        // MATCH_PARENT refers to this measured slot, never the activity or the full dialog.
        AndroidView(factory = { BoundedMobilityMap(it, map) }, modifier = Modifier.matchParentSize().clipToBounds(), update = { it.clipChildren = true; map.invalidate() })
        if (selected) Icon(Icons.Default.LocationOn, "Selected location", tint = CommuteTeal, modifier = Modifier.align(Alignment.Center).offset(y = (-21).dp).size(48.dp))
        Surface(shape = CircleShape, color = Color.White, shadowElevation = 3.dp, border = BorderStroke(1.dp, Color(0xFFDDE2DC)), modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp)) {
            IconButton(onClick = onLocate, enabled = !locating, modifier = Modifier.testTag("mobility_location_recenter")) { Icon(Icons.Default.MyLocation, "Use current location", tint = CommuteInk) }
        }
        Surface(color = Color.White.copy(alpha = 0.92f), shape = RoundedCornerShape(topEnd = 6.dp), modifier = Modifier.align(Alignment.BottomStart)) { Text("© OpenStreetMap contributors", color = CommuteInk, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) }
    }
}

@Composable
private fun LocationConfirmation(selected: Boolean, selectedName: String, destination: Boolean, onConfirm: () -> Unit, t: (String, String, String) -> String) {
    Surface(color = Color.White, shape = RoundedCornerShape(topStart = 23.dp, topEnd = 23.dp), modifier = Modifier.fillMaxWidth().testTag("mobility_location_confirmation")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 21.dp, vertical = 15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(if (destination) t("Selected destination", "የተመረጠ መድረሻ", "Ga'umsa filatame") else t("Selected pickup", "የተመረጠ መነሻ", "Ka'umsa filatame"), color = Color(0xFF4D6577), fontSize = 12.sp)
            Text(selectedName.ifBlank { if (selected) t("Selected map point", "በካርታ የተመረጠ ቦታ", "Bakka kaartaa filatame") else t("Choose a place on the map", "በካርታው ላይ ቦታ ይምረጡ", "Kaartaa irratti bakka filadhu") }, color = CommuteInk,
                fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("mobility_location_selected_name"))
            Text(t("Check the pickup or delivery entrance.", "የመነሻ ወይም የማድረሻ መግቢያውን ያረጋግጡ።", "Balbala ka'umsaa yookaan geejjibaa mirkaneessi."), color = Color(0xFF566D7B), fontSize = 12.sp, lineHeight = 16.sp)
            Button(onClick = onConfirm, enabled = selected, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 52.dp).testTag("mobility_location_confirm"), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = CommuteTeal)) {
                Text(if (destination) t("Use this destination", "ይህን መድረሻ ተጠቀም", "Ga'umsa kana fayyadami") else t("Use this pickup", "ይህን መነሻ ተጠቀም", "Ka'umsa kana fayyadami"), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

internal fun validMobilityPoint(latitude: String, longitude: String): Pair<Double, Double>? {
    val lat = latitude.toDoubleOrNull() ?: return null; val lon = longitude.toDoubleOrNull() ?: return null
    return if (lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
}
