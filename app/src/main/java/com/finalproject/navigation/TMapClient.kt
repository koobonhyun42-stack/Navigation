package com.finalproject.navigation

import android.provider.Settings
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
        Retrofit.Builder()
            .baseUrl("https://apis.openai.sk.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TMapApiService::class.java)
    }

    fun requestRoute(
        startLng: Double,
        startLat: Double,
        endLng: Double,
        endLat: Double,
        onResult: (TMapRouteResponse?) -> Unit
    ) {
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
                    onResult(response)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "TMAP 경로 요청 실패: ${e.localizedMessage}")
                    onResult(null)
                }
            }
        }
    }

    fun searchDestinationAndRoute(
        keyword: String,
        startLat: Double,
        startLng: Double,
        onDestinationNotFound: () -> Unit,
        onResult: (placeName: String?, routeResponse: TMapRouteResponse?) -> Unit
    ) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val poiResponse = tmapService.searchPois(
                appKey = TMAP_APP_KEY,
                searchKeyword = keyword,
                centerLat = startLat,
                centerLon = startLng
                )
                val firstPoi = poiResponse.searchPoiInfo?.pois?.poi?.firstOrNull {
                    it.getLatitude() != null && it.getLongitude() != null
                }

                val endLat = firstPoi?.getLatitude()
                val endLng = firstPoi?.getLongitude()

                if (firstPoi == null || endLat == null || endLng == null) {
                    withContext(Dispatchers.Main) {
                        onDestinationNotFound()
                    }
                    return@launch
                }

                val routeResponse = tmapService.getPedestrianRoute(
                    appKey = TMAP_APP_KEY,
                    startX = startLng,
                    startY = startLat,
                    endX = endLng,
                    endY = endLat
                )

                withContext(Dispatchers.Main) {
                    onResult(firstPoi.name ?: keyword, routeResponse)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "POI 검색 또는 경로 요청 실패: ${e.localizedMessage}")
                    onResult(null, null)
                }
            }
        }
    }
}