package com.finalproject.navigation

import com.google.gson.annotations.SerializedName

data class TMapPoiResponse(
    @SerializedName("searchPoiInfo")
    val searchPoiInfo: SearchPoiInfo?
)

data class SearchPoiInfo(
    @SerializedName("pois")
    val pois: PoiList?
)

data class PoiList(
    @SerializedName("poi")
    val poi: List<PoiItem>?
)

data class PoiItem(
    @SerializedName("name")
    val name: String?,
    @SerializedName("noorLat")
    val noorLat: String?,
    @SerializedName("noorLon")
    val noorLon: String?,
    @SerializedName("frontLat")
    val frontLat: String?,
    @SerializedName("frontLon")
    val frontLon: String?
) {
    fun getLatitude(): Double? = (frontLat ?: noorLat)?.toDoubleOrNull()
    fun getLongitude(): Double? = (frontLon ?: noorLon)?.toDoubleOrNull()
}