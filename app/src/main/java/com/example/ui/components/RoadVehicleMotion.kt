package com.example.ui.components

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.example.data.api.MobilityLocation
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import kotlin.math.*

internal data class RoadProjection(val point: Pair<Double, Double>, val alongMeters: Double,
    val distanceMeters: Double, val heading: Float)

/** Visual matching only. Fare evidence always remains the original GPS sent to the server. */
internal fun projectToRoad(point: Pair<Double, Double>, road: List<Pair<Double, Double>>,
    accuracyMeters: Double? = null): RoadProjection? {
    if (!validRoadPoint(point) || road.size < 2 || road.any { !validRoadPoint(it) }) return null
    val tolerance = (accuracyMeters?.takeIf { it.isFinite() && it > 0 } ?: 25.0).coerceIn(25.0, 60.0)
    val metersLat = 111_195.0
    val metersLon = (metersLat * cos(Math.toRadians(point.first))).coerceAtLeast(1.0)
    var travelled = 0.0
    var closest: RoadProjection? = null
    road.zipWithNext().forEach { (a, b) ->
        val ax = (a.second - point.second) * metersLon; val ay = (a.first - point.first) * metersLat
        val dx = (b.second - a.second) * metersLon; val dy = (b.first - a.first) * metersLat
        val squared = dx * dx + dy * dy
        val fraction = if (squared <= .0001) 0.0 else (-(ax * dx + ay * dy) / squared).coerceIn(0.0, 1.0)
        val projected = (a.first + (b.first - a.first) * fraction) to (a.second + (b.second - a.second) * fraction)
        val distance = roadDistanceMeters(point, projected)
        val segment = roadDistanceMeters(a, b)
        if (segment > .01 && (closest == null || distance < closest!!.distanceMeters)) {
            val heading = ((Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0).toFloat()
            closest = RoadProjection(projected, travelled + segment * fraction, distance, heading)
        }
        travelled += segment
    }
    return closest?.takeIf { it.distanceMeters <= tolerance }
}

/** Include actual road corners between two received positions; never cut across their blocks. */
internal fun roadVehiclePath(from: Pair<Double, Double>, to: Pair<Double, Double>,
    road: List<Pair<Double, Double>>, accuracyMeters: Double? = null): List<Pair<Double, Double>> {
    val a = projectToRoad(from, road, accuracyMeters) ?: return emptyList()
    val b = projectToRoad(to, road, accuracyMeters) ?: return emptyList()
    val length = abs(b.alongMeters - a.alongMeters)
    if (length > 2_000 || length > max(100.0, roadDistanceMeters(from, to) * 4)) return emptyList()
    var travelled = 0.0
    val interior = mutableListOf<Pair<Double, Double>>()
    road.forEachIndexed { index, point ->
        if (index > 0) travelled += roadDistanceMeters(road[index - 1], point)
        if (travelled > min(a.alongMeters, b.alongMeters) + .01 && travelled < max(a.alongMeters, b.alongMeters) - .01) interior += point
    }
    return (listOf(a.point) + if (b.alongMeters >= a.alongMeters) interior else interior.reversed()) + b.point
}

internal fun roadPathFrame(path: List<Pair<Double, Double>>, fraction: Float): Pair<Pair<Double, Double>, Float>? {
    if (path.size < 2) return null
    val distances = path.zipWithNext().map { roadDistanceMeters(it.first, it.second) }
    val total = distances.sum()
    if (total < .01) return path.last() to 0f
    var remaining = total * fraction.coerceIn(0f, 1f)
    for (index in distances.indices) {
        val distance = distances[index]
        if (remaining <= distance || index == distances.lastIndex) {
            val ratio = if (distance > .01) (remaining / distance).coerceIn(0.0, 1.0) else 1.0
            val a = path[index]; val b = path[index + 1]
            val point = (a.first + (b.first - a.first) * ratio) to (a.second + (b.second - a.second) * ratio)
            val heading = ((Math.toDegrees(atan2((b.second - a.second) * cos(Math.toRadians(point.first)), b.first - a.first)) + 360) % 360).toFloat()
            return point to heading
        }
        remaining -= distance
    }
    return path.last() to 0f
}

/** One persistent marker moves only toward an already received, fresh driver fix. No extrapolation. */
internal class RoadVehicleMotion(private val map: MapView, private val iconResource: Int?) {
    val marker = Marker(map).apply { setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); isEnabled = false }
    private var animator: ValueAnimator? = null
    private var received: String? = null
    private var placed = false
    private var iconFresh: Boolean? = null
    private var receivedAt: Long? = null
    fun update(location: MobilityLocation?, fresh: Boolean, road: List<Pair<Double, Double>>, animate: Boolean = true, paused: Boolean = false) {
        val raw = location?.let { (it.latitude to it.longitude).takeIf(::validRoadPoint) }
        if (raw == null) { cancel(); marker.isEnabled = false; placed = false; received = null; return }
        val fix = location ?: return
        marker.isEnabled = true
        marker.title = if (fresh) "Driver · live" else "Driver · last known"
        marker.alpha = if (fresh) 1f else .6f
        if (iconFresh != fresh) { marker.icon = icon(fresh); iconFresh = fresh }
        val key = "${fix.timestamp}:${raw.first}:${raw.second}:$fresh:$paused"
        if (received == key) return
        received = key
        cancel()
        val timestamp = runCatching { java.time.Instant.parse(fix.timestamp).toEpochMilli() }.getOrNull()
        val previousTimestamp = receivedAt
        if (placed && (!fresh || paused || (timestamp != null && receivedAt != null && timestamp <= receivedAt!!))) {
            if (timestamp != null && (receivedAt == null || timestamp > receivedAt!!)) receivedAt = timestamp
            map.invalidate(); return
        }
        receivedAt = timestamp
        val projection = if (fresh) projectToRoad(raw, road, fix.accuracyMeters) else null
        val target = projection?.point ?: raw
        val current = marker.position.let { it.latitude to it.longitude }
        val deadband = ((fix.accuracyMeters ?: 10.0) / 2).coerceIn(3.0, 12.0)
        if (placed && fresh && roadDistanceMeters(current, target) < deadband) { map.invalidate(); return }
        val path = if (placed && fresh && projection != null) roadVehiclePath(current, target, road, fix.accuracyMeters) else emptyList()
        val canAnimate = animate && placed && fresh && path.size >= 2 && roadDistanceMeters(current, target) > 1 &&
            (android.os.Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())
        if (!canAnimate) {
            marker.position = GeoPoint(target.first, target.second)
            projection?.let { if (iconResource != null) marker.rotation = -it.heading }
            placed = true; map.invalidate(); return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (timestamp != null && previousTimestamp != null) ((timestamp - previousTimestamp) / 2).coerceIn(800, 5_000) else 1_200
            interpolator = LinearInterpolator()
            addUpdateListener { value -> roadPathFrame(path, value.animatedValue as Float)?.let { (point, heading) ->
                marker.position = GeoPoint(point.first, point.second)
                // osmdroid draws Marker rotation with the opposite sign to a clockwise road bearing.
                if (iconResource != null) marker.rotation = -heading
                map.invalidate()
            } }
            start()
        }
        placed = true
    }
    fun cancel() { animator?.cancel(); animator = null }
    private fun icon(fresh: Boolean): android.graphics.drawable.Drawable {
        val size = (36 * map.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); val canvas = Canvas(bitmap)
        val supplied = iconResource?.let { ContextCompat.getDrawable(map.context, it)?.mutate() }
        if (supplied != null) { supplied.setBounds(0, 0, size, size); supplied.draw(canvas) }
        else {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG); paint.color = if (fresh) 0xFF1977E6.toInt() else 0xFF71808A.toInt()
            canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2, paint)
            paint.color = android.graphics.Color.WHITE; paint.textAlign = Paint.Align.CENTER; paint.textSize = size * .44f; paint.isFakeBoldText = true
            canvas.drawText("D", size / 2f, size / 2f - (paint.ascent() + paint.descent()) / 2, paint)
        }
        return BitmapDrawable(map.resources, bitmap)
    }
}
