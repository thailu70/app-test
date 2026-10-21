package com.example

import com.example.data.api.RoutePassApiService
import com.example.data.api.VehicleTypesResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.*
import org.junit.Test
import retrofit2.http.GET

class VehicleTypeCatalogTest {
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(VehicleTypesResponse::class.java)

    @Test fun `live catalog preserves admin names and capacities and excludes inactive types`() {
        val response = adapter.fromJson("""{"success":true,"vehicleTypes":[
          {"code":"COACH_53","name":"Executive Coach","capacity":53,"active":true},
          {"code":"OLD_BUS","name":"Retired Bus","capacity":30,"active":false}
        ]}""")!!
        val available = response.activeOptions()
        assertEquals(1, available.size)
        assertEquals("COACH_53", available.single().code)
        assertEquals("Executive Coach", available.single().name)
        assertEquals(53, available.single().capacity)
    }

    @Test fun `successful empty catalog stays empty without inventing default choices`() {
        assertTrue(adapter.fromJson("""{"success":true,"vehicleTypes":[]}""")!!.activeOptions().isEmpty())
    }

    @Test fun `catalog and GPS settings use the shared authenticated HTTP client contracts`() {
        val catalog = RoutePassApiService::class.java.methods.single { it.name == "getVehicleTypes" }
        assertEquals("api/master-data/vehicle-types", catalog.getAnnotation(GET::class.java).value)
        val gps = RoutePassApiService::class.java.methods.single { it.name == "getGpsSharing" }
        assertEquals("api/master-data/gps-sharing", gps.getAnnotation(GET::class.java).value)
    }
}
