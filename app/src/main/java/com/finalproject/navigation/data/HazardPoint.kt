package com.finalproject.navigation.data

import com.google.firebase.firestore.GeoPoint
data class HazardPoint(
    val id: String = "",
    val location: GeoPoint = GeoPoint(0.0, 0.0),
    val description: String = "",
    val hazardType: String = "OBSTACLE"
)
