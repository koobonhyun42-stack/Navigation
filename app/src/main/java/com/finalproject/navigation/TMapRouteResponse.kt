package com.finalproject.navigation

import com.google.gson.annotations.SerializedName

data class TMapRouteResponse(
    @SerializedName("type")
    val type: String?,
    @SerializedName("features")
    val features: List<Feature>?
)

data class Feature(
    @SerializedName("type")
    val type: String?,
    @SerializedName("geometry")
    val geometry: Geometry?,
    @SerializedName("properties")
    val properties: Properties?,
    val totalDistance: Any
)

data class Geometry (
    @SerializedName("type")
    val type: String?,
    @SerializedName("coordinates")
    val coordinates: Any?
)

data class Properties(
    @SerializedName("totalDistance") val totalDistance: Int?,
    @SerializedName("totalTime") val totalTime: Int?,
    @SerializedName("index") val index: Int,
    @SerializedName("name") val name: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("turnType") val turnType: Int?,
    @SerializedName("facilityType") val facilityType: String?
)
