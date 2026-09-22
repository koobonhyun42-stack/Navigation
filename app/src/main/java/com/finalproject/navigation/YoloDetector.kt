package com.finalproject.navigation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import java.nio.FloatBuffer
import java.util.Collections
import com.finalproject.navigation.BoundingBox

class YoloDetector(
    private val context: Context,
    private val modelPath: String = "yolov8n.onnx",
    private val onResults: ((List<BoundingBox>) -> Unit)? = null
) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    val inputSize: Int = 640

    val labels = arrayOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
        "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
        "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
        "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
        "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
        "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
        "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator",
        "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
    )

    init {
        val modelBytes = context.assets.open(modelPath).readBytes()
        session = env.createSession(modelBytes, OrtSession.SessionOptions())
    }

    fun detect(imageProxy: ImageProxy): List<BoundingBox> {
        val bitmap: Bitmap = imageProxyToBitmap(imageProxy)
        return detectBitmap(bitmap, bitmap.width, bitmap.height)
    }

    fun detectBitmap(bitmap: Bitmap, originalWidth: Int, originalHeight: Int): List<BoundingBox> {
        val resizedBitmap: Bitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        val floatBuffer = FloatBuffer.allocate(1 * 3 * inputSize * inputSize)
        val intValues = IntArray(inputSize * inputSize)
        resizedBitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        for (i in 0 until inputSize * inputSize) {
            val pixel = intValues[i]
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f

            floatBuffer.put(i, r)
            floatBuffer.put(inputSize * inputSize + i, g)
            floatBuffer.put(2 * inputSize * inputSize + i, b)
        }

        val inputName = session.inputNames.first()
        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        val inputTensor = OnnxTensor.createTensor(env, floatBuffer, shape)

        val output = session.run(Collections.singletonMap(inputName, inputTensor))
        val outputTensor = output[0].value as Array<Array<FloatArray>>

        inputTensor.close()
        output.close()

        return processOutPut(outputTensor[0], originalWidth, originalHeight)
    }

    private fun processOutPut(output: Array<FloatArray>, imgWidth: Int, imgHeight: Int): List<BoundingBox> {
        val boxes = mutableListOf<BoundingBox>()
        val numClasses = 80
        val numAnchors = 8400

        for (i in 0 until numAnchors) {
            var maxConf = 0.0f
            var maxClass = -1

            for (c in 0 until numClasses) {
                val conf = output[4 + c][i]
                if (conf > maxConf) {
                    maxConf = conf
                    maxClass = c
                }
            }

            if (maxConf > 0.4f) {
                val cx = output[0][i]
                val cy = output[1][i]
                val w = output[2][i]
                val h = output[3][i]

                val x1 = (cx - w / 2) * imgWidth / inputSize.toFloat()
                val y1 = (cy - h / 2) * imgHeight / inputSize.toFloat()
                val x2 = (cx + w / 2) * imgWidth / inputSize.toFloat()
                val y2 = (cy + h / 2) * imgHeight / inputSize.toFloat()

                boxes.add(BoundingBox(x1, y1, x2, y2, maxConf, maxClass))
            }
        }

        return nms(boxes)
    }

    private fun nms(boxes: List<BoundingBox>): List<BoundingBox> {
        val sortedBoxes = boxes.sortedByDescending { it.cnf }.toMutableList()
        val selectedBoxes = mutableListOf<BoundingBox>()

        while (sortedBoxes.isNotEmpty()) {
            val first = sortedBoxes.removeAt(0)
            selectedBoxes.add(first)

            val iterator = sortedBoxes.iterator()
            while (iterator.hasNext()) {
                val next = iterator.next()
                if (calculateIou(first, next) > 0.45f) {
                    iterator.remove()
                }
            }
        }
        return selectedBoxes
    }

    private fun calculateIou(a: BoundingBox, b: BoundingBox): Float {
        val x1 = maxOf(a.x1, b.x1)
        val y1 = maxOf(a.y1, b.y1)
        val x2 = minOf(a.x2, b.x2)
        val y2 = minOf(a.y2, b.y2)

        val intersection = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
        val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
        val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)

        return intersection / (areaA + areaB - intersection)
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val bitmap = imageProxy.toBitmap()
        val matrix = Matrix()
        matrix.postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun close() {
        session.close()
        env.close()
    }
}