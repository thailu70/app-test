package com.example.data.api

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class VehicleTypeOption(val code: String, val name: String, val capacity: Int, val active: Boolean = true) {
    companion object {
        val DEFAULTS = listOf(
            VehicleTypeOption("MINIVAN_8", "Minivan", 8),
            VehicleTypeOption("MINIBUS_14", "Minibus", 14),
            VehicleTypeOption("HIGER_24", "Higer", 24),
            VehicleTypeOption("ANBESSA_BUS_30", "Bus", 30)
        )
    }
}

@JsonClass(generateAdapter = true)
data class VehicleTypesResponse(val success: Boolean = false, val vehicleTypes: List<VehicleTypeOption> = emptyList()) {
    fun activeOptions(): List<VehicleTypeOption> = vehicleTypes.filter {
        it.active && it.code.isNotBlank() && it.name.isNotBlank() && it.capacity > 0
    }.distinctBy { it.code }
}
