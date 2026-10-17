package com.finalproject.navigation

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object TMapClient {
    private const val TAG = "TMapClient"
    private const val TMAP_APP_KEY = "yPDwRxoi5d2x5zYsGinmf3LWrVPMcegB9KxGbsJK"

    private val tmapService: TMapApiService by lazy {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://apis.openapi.sk.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        retrofit.create(com.finalproject.navigation.TMapApiService::class.java)
    }

    fun requestRoute(startLng: Double, startLat: Double, endLng: Double, endLat: Double) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val response = tmapService.getPedestrianRoute(
                    appKey = TMAP_APP_KEY,
                    startX = startLng,
                    startY = startLat,
                    endX = endLng,
                    endY = endLat
                )
                withContext(Dispatchers.Main) {
                    val totalDist = response.features?.firstOrNull()?.properties?.totalDistance ?: 0
                    Log.d(TAG, "TMAP 경로 요청 성공! 총 거리: ${totalDist}m")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "TMAP 경로 요청 실패: ${e.localizedMessage}")
                }
            }
        }
    }
}