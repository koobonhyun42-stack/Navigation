package com.finalproject.navigation

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

interface TMapApiService {
    @GET("tmap/routes/pedestrian?version=1")
    suspend fun getPedestrianRoute(
        @Header("appKey") appKey: String,
        @Query("startX") startX: Double,
        @Query("startY") startY: Double,
        @Query("endX") endX: Double,
        @Query("endY") endY: Double,
        @Query("reqCoordType") reqCoordType: String = "WGS84GEO",
        @Query("resCoordType") resCoordType: String = "WGS84GEO",
        @Query("startName") startName: String = "출발지",
        @Query("endName") endName: String = "목적지",
    ): TMapRouteResponse
}