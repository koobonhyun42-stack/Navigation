package com.finalproject.navigation.manager

import com.finalproject.navigation.data.HazardPoint
import com.finalproject.navigation.data.HazardRepository
import com.finalproject.navigation.TMapApiService
import com.finalproject.navigation.TMapRouteResponse
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class NavigationManager(
    private val appKey: String,
    private val hazardRepository: HazardRepository = HazardRepository()
) {
    private val tmapService: TMapApiService

    init {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://apis.openapi.sk.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        tmapService = retrofit.create(TMapApiService::class.java)
    }

    suspend fun fetchRoute(
        startX: Double, startY: Double,
        endX: Double, endY: Double
    ): TMapRouteResponse? {
        return try {
            tmapService.getPedestrianRoute(
                appKey = appKey,
                startX = startX, startY = startY,
                endX = endX, endY = endY
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun checkNearByHazards(currentLat: Double, currentLng: Double, thresholdMeter: Double = 10.0): List<HazardPoint> {
        val allHazards = hazardRepository.getNearbyHazards()
        return allHazards.filter { hazard ->
            val distance = calculateDistance(currentLat, currentLng, hazard.location.latitude, hazard.location.longitude)
            distance <= thresholdMeter
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }
}