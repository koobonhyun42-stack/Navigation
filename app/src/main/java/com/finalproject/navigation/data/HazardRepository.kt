package com.finalproject.navigation.data

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class HazardRepository {
    private val db = FirebaseFirestore.getInstance()

    suspend fun getNearbyHazards(): List<HazardPoint> {
        return try {
            val snapshot = db.collection("hazards").get().await()
            snapshot.documents.mapNotNull { doc ->
                val point = doc.toObject(HazardPoint::class.java)
                point?.copy(id = doc.id)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}