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
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var yoloDetector: YoloDetector
    private lateinit var feedbackManager: FeedbackManager
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var videoAnalyzer: VideoAnalyzer

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

        yoloDetector = YoloDetector(this)
        feedbackManager = FeedbackManager(this)
        videoAnalyzer = VideoAnalyzer(this, yoloDetector)
        cameraExecutor = Executors.newSingleThreadExecutor()

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
                        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                        val isRotated = rotationDegrees == 90 || rotationDegrees == 270
                        val imgWidth = if (isRotated) imageProxy.height else imageProxy.width
                        val imgHeight = if (isRotated) imageProxy.width else imageProxy.height

                        runOnUiThread {
                            binding.overlayView.setResults(
                                boundingBoxes = results,
                                labelArray = yoloDetector.labels,
                                imgWidth = imgWidth,
                                imgHeight = imgHeight
                            )

                            val viewWidth = binding.viewFinder.width
                            val viewHeight = binding.viewFinder.height
                            feedbackManager.processDetections(results, viewWidth, viewHeight)
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
                    val label = res.box.clsName.ifBlank { "Unknown" }
                    Log.d(
                        TAG,
                        "Timestamp: ${res.timestampMs}ms | 객체: $label | 거리: ${String.format(Locale.KOREAN, "%.2f", res.estimateDistanceMeter)}m"
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