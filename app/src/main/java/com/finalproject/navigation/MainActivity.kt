package com.finalproject.navigation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.finalproject.navigation.databinding.ActivityMainBinding
import com.finalproject.navigation.BoundingBox
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var yoloDetector: YoloDetector
    private lateinit var videoAnalyzer: VideoAnalyzer
    private lateinit var feedbackManager: FeedbackManager
    private lateinit var cameraExecutor: ExecutorService

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val TAG = "MainActivity"
    }

    private val selectVideoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val videoUri: Uri? = result.data?.data
            videoUri?.let { uri ->
                analyzeSelectedVideo(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        feedbackManager = FeedbackManager(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        yoloDetector = YoloDetector(this, "yolov8n.onnx") { detectedBoxes ->
            runOnUiThread {
                // OverlayView에 감지된 BoundingBox 전달
                binding.overlayView.setResults(detectedBoxes)

                // viewFinder 너비 기준 위치(좌/전방/우) 판단 및 피드백 처리
                val viewWidth = binding.viewFinder.width
                feedbackManager.processDetections(detectedBoxes, viewWidth)
            }
        }

        videoAnalyzer = VideoAnalyzer(this, yoloDetector)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        val results = yoloDetector.detect(imageProxy)

                        runOnUiThread {
                            binding.overlayView.setResults(results, yoloDetector.labels)

                            val screenWidth = resources.displayMetrics.widthPixels
                            feedbackManager.processFeedback(results, screenWidth)
                        }

                        imageProxy.close()
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Camera binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    fun openVideoPicker() {
        val intent = Intent(Intent.ACTION_PICK).apply {
            type = "video/*"
        }
        selectVideoLauncher.launch(intent)
    }

    private fun analyzeSelectedVideo(videoUri: Uri) {
        cameraExecutor.execute {
            Log.d(TAG, "비디오 분석 시작: $videoUri")

            val videoResults = videoAnalyzer.analyzeVideo(videoUri, intervalSeconds = 1.0f)

            runOnUiThread {
                Toast.makeText(
                    this,
                    "분석 완료! 총 ${videoResults.size}개의 감지 결과가 있습니다.",
                    Toast.LENGTH_SHORT
                ).show()

                for (res in videoResults) {
                    val label = yoloDetector.labels.getOrNull(res.box.cls) ?: "Unknown"
                    Log.d(
                        TAG,
                        "Timestamp: ${res.timestampMs}ms | 객체: $label | 거리: ${String.format("%.2f", res.estimateDistanceMeter)}m"
                    )
                }
            }
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return REQUIRED_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(
                baseContext,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "카메라 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        yoloDetector.close()
        feedbackManager.shutdown()
    }
}