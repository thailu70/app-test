package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.data.api.MobilityLocation
import com.example.ui.components.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoadVehicleMotionTest {
    private val road = listOf(9.0 to 38.7, 9.0 to 38.701, 9.001 to 38.701)
    private fun fix(seconds: Int, lat: Double = 9.0, lon: Double = 38.7001) = MobilityLocation(lat, lon,
        "2026-11-16T00:00:${"%02d".format(seconds)}Z", accuracyMeters = 10.0)
    @Test fun `visual matching follows road only inside GPS accuracy tolerance`() {
        val projection = projectToRoad(9.00005 to 38.7004, road, 10.0)!!
        assertEquals(9.0, projection.point.first, .000001)
        assertEquals(38.7004, projection.point.second, .000001)
        assertEquals(90f, projection.heading, .1f)
        assertTrue(projection.distanceMeters < 6)
        assertNull(projectToRoad(9.01 to 38.71, road, 1000.0))
    }
    @Test fun `animation keeps the real road corner rather than crossing the block`() {
        val path = roadVehiclePath(9.0 to 38.7001, 9.0009 to 38.701, road, 10.0)
        assertTrue(path.contains(road[1]))
        for (fraction in listOf(.25f, .5f, .75f)) {
            val point = roadPathFrame(path, fraction)!!.first
            assertTrue(abs(point.first - 9.0) < .000001 || abs(point.second - 38.701) < .000001)
        }
        assertEquals(90f, roadPathFrame(path, 0f)!!.second, .1f)
        assertEquals(0f, roadPathFrame(path, 1f)!!.second, .1f)
    }
    @Test fun `same old stale paused or jitter fixes never invent vehicle progress`() {
        val map = MapView(ApplicationProvider.getApplicationContext())
        val motion = RoadVehicleMotion(map, R.drawable.taxi_car_top)
        try {
            motion.update(fix(0), true, road, animate = false)
            val initial = motion.marker.position
            motion.update(fix(0, lon = 38.7008), true, road, animate = false)
            assertEquals(initial, motion.marker.position)
            motion.update(fix(2, lon = 38.70011), true, road, animate = false)
            assertEquals(initial, motion.marker.position)
            motion.update(fix(10, 9.0007, 38.701), true, road, animate = false)
            val moved = motion.marker.position
            assertNotEquals(initial, moved)
            motion.update(fix(15, 9.0009, 38.701), true, road, animate = false, paused = true)
            assertEquals(moved, motion.marker.position)
            motion.update(fix(20, 9.001, 38.701), false, road, animate = false)
            assertEquals(moved, motion.marker.position)
            motion.update(fix(12, lon = 38.7008), true, road, animate = false)
            assertEquals(moved, motion.marker.position)
        } finally { motion.cancel(); map.onDetach() }
    }
    @Test fun `native overlay retains one driver marker across updates and nearby cars stay independent`() {
        val map = MapView(ApplicationProvider.getApplicationContext())
        val motion = RoadVehicleMotion(map, R.drawable.taxi_car_top)
        try {
            val first = fix(0); motion.update(first, true, road, animate = false)
            replaceRoadOverlays(map, road, emptyList(), road.first(), road.last(), first, true, true, "Pickup", "Destination", stableDriverMarker = motion.marker)
            assertSame(motion.marker, map.overlays.filterIsInstance<Marker>().last())
            val next = fix(10, 9.0007, 38.701); motion.update(next, true, road, animate = false)
            replaceRoadOverlays(map, road, emptyList(), road.first(), road.last(), next, true, true, "Pickup", "Destination", stableDriverMarker = motion.marker)
            assertEquals(3, map.overlays.filterIsInstance<Marker>().size)
            assertSame(motion.marker, map.overlays.filterIsInstance<Marker>().last())
        } finally { motion.cancel(); map.onDetach() }
    }
    @Test fun `at pickup and destination need no degenerate zero length approach route`() {
        assertFalse(roadApproachNeeded(road.first(), road.first()))
        assertFalse(roadApproachNeeded(road.first(), 9.00001 to 38.7))
        assertTrue(roadApproachNeeded(road.first(), road.last()))
    }
    private fun abs(value: Double) = kotlin.math.abs(value)
}
