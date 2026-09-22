package com.finalproject.navigation

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri

data class VideoDetectionResult(
    val timestampMs: Long,
    val box: BoundingBox,
    val estimateDistanceMeter: Float
)

class VideoAnalyzer(private val context: Context, private val detector: YoloDetector) {

    fun analyzeVideo(videoUri: Uri, intervalSeconds: Float = 1.0f): List<VideoDetectionResult> {
        val results = mutableListOf<VideoDetectionResult>()
        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(context, videoUri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLong() ?: 0L

            val intervalMs = (intervalSeconds * 1000).toLong()
            var currentMs = 0L

            while (currentMs < durationMs) {
                val frameBitmap = retriever.getFrameAtTime(
                    currentMs * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )

                frameBitmap?.let { bitmap ->
                    val detectedBoxes = detector.detectBitmap(bitmap, bitmap.width, bitmap.height)

                    for (box in detectedBoxes) {
                        val distance = estimateDistance(box, bitmap.height)
                        results.add(VideoDetectionResult(currentMs, box, distance))
                    }
                }

                currentMs += intervalMs
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            retriever.release()
        }

        return results
    }

    private fun estimateDistance(box: BoundingBox, frameHeight: Int): Float {
        // 객체 바운딩 박스 높이(px) 계산
        val boxHeightPx = kotlin.math.abs(box.y2 - box.y1)
        if (boxHeightPx <= 0f) return -1f

        val realObjectHeightMeter = when (box.cls) {
            0 -> 1.7f   // person
            1 -> 1.0f   // bicycle
            2 -> 1.5f   // car
            5 -> 3.2f   // bus
            56 -> 0.9f  // chair
            else -> 1.0f
        }

        val focalLengthPx = frameHeight * 0.8f

        return (realObjectHeightMeter * focalLengthPx) / boxHeightPx
    }
}