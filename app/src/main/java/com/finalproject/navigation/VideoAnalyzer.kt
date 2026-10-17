package com.finalproject.navigation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.util.Locale

data class VideoDetectionResult(
    val timestampMs: Long,
    val box: BoundingBox,
    val estimateDistanceMeter: Float,
    val frameWidth: Int,
    val frameHeight: Int
)

class VideoAnalyzer(
    private val context: Context,
    private val detector: YoloDetector
) {

    fun analyzeVideo(
        videoUri: Uri,
        intervalSeconds: Float = 1.0f,
        onFrameAnalyzed: ((List<BoundingBox>, Int, Int, Long) -> Unit)? = null
    ): List<VideoDetectionResult> {
        val results = mutableListOf<VideoDetectionResult>()
        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(context, videoUri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLong() ?: 0L

            val rotationDegree = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0

            val intervalMs = (intervalSeconds * 1000).toLong()
            var currentMs = 0L

            while (currentMs < durationMs) {
                val frameBitmap = retriever.getFrameAtTime(
                    currentMs * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )

                frameBitmap?.let { bitmap ->
                    val rotatedBitmap = if (rotationDegree != 0) {
                        val matrix = Matrix().apply { postRotate(rotationDegree.toFloat()) }
                        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                        if (rotated != bitmap) {
                            bitmap.recycle()
                        }
                        rotated
                    } else {
                        bitmap
                    }

                    val width = rotatedBitmap.width
                    val height = rotatedBitmap.height

                    val detectedBoxes = detector.detectBitmap(
                        bitmap = rotatedBitmap,
                        originalWidth = width,
                        originalHeight = height
                    )

                    for (box in detectedBoxes) {
                        val distance = estimateDistance(box, height)
                        box.distanceMeter = distance

                        results.add(
                            VideoDetectionResult(
                                timestampMs = currentMs,
                                box = box,
                                estimateDistanceMeter = distance,
                                frameWidth = width,
                                frameHeight = height
                            )
                        )
                    }

                    onFrameAnalyzed?.invoke(detectedBoxes, width, height, currentMs)
                    rotatedBitmap.recycle()
                }

                currentMs += intervalMs
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return results
    }

    private fun estimateDistance(box: BoundingBox, frameHeight: Int): Float {
        val boxHeightPx = box.h
        if (boxHeightPx <= 0f) return -1f

        val realObjectHeightMeter = when (box.clsName.lowercase(Locale.ROOT)) {
            "person", "사람" -> 1.7f
            "bicycle", "자전거" -> 1.0f
            "car", "차량" -> 1.5f
            "bus", "버스" -> 3.2f
            "chair", "의자" -> 0.9f
            "bollard", "볼라드" -> 0.8f
            else -> 1.0f
        }

        val focalLengthPx = frameHeight * 0.8f

        return (realObjectHeightMeter * focalLengthPx) / boxHeightPx
    }
}