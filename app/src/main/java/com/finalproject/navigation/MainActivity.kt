package com.finalproject.navigation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.view.MotionEvent
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.finalproject.navigation.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var yoloDetector: YoloDetector
    private lateinit var feedbackManager: FeedbackManager
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var videoAnalyzer: VideoAnalyzer

    private lateinit var voiceCommandManager: VoiceCommandManager
    private lateinit var powerSaveManager: PowerSaveManager
    private var isDetectionActive = true
    private var isPowerSaveMode = false

    private var lastTapTime = 0L
    private var tapCount = 0

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "모든 권한이 허용되었습니다.", Toast.LENGTH_SHORT).show()
            initFeatures()
        } else {
            Toast.makeText(this, "카메라, 마이크, 위치 권한이 모두 필요합니다.", Toast.LENGTH_LONG).show()
            finish()
        }
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

    private val speechRecognizerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = matches?.firstOrNull()?.lowercase() ?: ""
            Log.d(TAG, "인식된 목적지: $spokenText")
            if (spokenText.isNotBlank()) {
                handleSpokenDestination(spokenText)
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        yoloDetector = YoloDetector(this)
        feedbackManager = FeedbackManager(this)
        videoAnalyzer = VideoAnalyzer(this, yoloDetector)
        cameraExecutor = Executors.newSingleThreadExecutor()

        voiceCommandManager = VoiceCommandManager(this) { command ->
            when (command) {
                VoiceCommandManager.Command.START -> {
                    isDetectionActive = true
                    feedbackManager.speak("보행 안내를 시작합니다.")
                }

                VoiceCommandManager.Command.PAUSE -> {
                    isDetectionActive = false
                    feedbackManager.speak("보행 안내를 일시 정지합니다.")
                }

                VoiceCommandManager.Command.STATUS -> {
                    val mode = if (isPowerSaveMode) "절전 모드" else "일반 작동 모드"
                    feedbackManager.speak("현재 시스템은 $mode 입니다.")
                }

                VoiceCommandManager.Command.UNKNOWN -> {}
            }
        }

        voiceCommandManager.startListening()

        powerSaveManager = PowerSaveManager(this) { powerSave ->
            isPowerSaveMode = powerSave
            runOnUiThread {
                if (powerSave) {
                    binding.tvStatus.text = "시스템 상태: PASSIVE (절전 모드)"
                } else {
                    binding.tvStatus.text = "시스템 상태: ACTIVE (정상 탐지)"
                }
            }
        }

        binding.root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastTapTime < 500) {
                    tapCount++
                } else {
                    tapCount = 1
                }
                lastTapTime = currentTime

                if (tapCount == 3) {
                    tapCount = 0

                    feedbackManager.speak("목적지를 말씀해 주세요.")
                    startDestinationRecognition()

                    return@setOnTouchListener true
                }
            }
            false
        }

        val btnTest = findViewById<Button>(R.id.btnTestTMap)
        btnTest.setOnClickListener {
            Log.d("TMapTest", "버튼 클릭됨! 테스트 함수를 시작합니다.")
            testTMapConnection()
        }

        binding.btnSelectVideo.setOnClickListener {
            openVideoPicker()
        }

        testTMapConnection()
        checkAndRequestPermissions()
    }

    private fun testTMapConnection() {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://apis.openapi.sk.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val service = retrofit.create(TMapApiService::class.java)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d("TMapTest", "네트워크 요청 시작...")

                val response = service.getPedestrianRoute(
                    appKey = "yPDwRxoi5d2x5zYsGinmf3LWrVPMcegB9KxGbsJK",
                    startX = 126.978388,
                    startY = 37.566610,
                    endX = 126.972533,
                    endY = 37.555946
                )

                val firstFeature = response.features?.firstOrNull()
                val totalDistance = firstFeature?.properties?.totalDistance ?: 0
                val totalTime = firstFeature?.properties?.totalTime ?: 0

                Log.d("TMapTest", "연동 성공! 총 거리: ${totalDistance}m, 총 소요 시간: ${totalTime}초")

            } catch (e: Exception) {
                Log.e("TMapTest", "연동 실패: ${e.localizedMessage ?: e.message}")
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            initFeatures()
        }
    }

    private fun initFeatures() {
        startCamera()
        powerSaveManager.start()
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
                        if (!isDetectionActive || (isPowerSaveMode && System.currentTimeMillis() % 2 != 0L)) {
                            imageProxy.close()
                            return@setAnalyzer
                        }

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

    private fun startDestinationRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "목적지를 말씀해 주세요")
        }
        try {
            speechRecognizerLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "음성 인식 시작 실패", e)
        }
    }

    private fun handleSpokenDestination(destinationText: String) {
        feedbackManager.speak("${destinationText}로 경로를 검색합니다.")

        val (endLat, endLng) = when {
            destinationText.contains("도서관") -> Pair(37.5560, 126.9720)
            destinationText.contains("정문") -> Pair(37.5550, 126.9700)
            else -> {
                feedbackManager.speak("등록되지 않은 목적지입니다. 다시 시도해 주세요.")
                return
            }
        }

        val startLat = 37.5665
        val startLng = 126.9780

        val retrofit = Retrofit.Builder()
            .baseUrl("https://apis.openapi.sk.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val service = retrofit.create(TMapApiService::class.java)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = service.getPedestrianRoute(
                    appKey = "yPDwRxoi5d2x5zYsGinmf3LWrVPMcegB9KxGbsJK",
                    startX = startLng,
                    startY = startLat,
                    endX = endLng,
                    endY = endLat
                )

                val firstFeature = response.features?.firstOrNull()
                val distance = firstFeature?.properties?.totalDistance ?: 0
                val time = firstFeature?.properties?.totalTime ?: 0

                val message = String.format(
                    Locale.KOREAN,
                    "목적지까지 총 거리 %d미터, 예상 소요 시간 %d분입니다.",
                    distance,
                    time / 60
                )

                runOnUiThread {
                    feedbackManager.speak(message)
                }
            } catch (e: Exception) {
                Log.e(TAG, "경로 요청 실해: ${e.message}")
                runOnUiThread {
                    feedbackManager.speak("경로를 불러오는 데 실패했습니다.")
                }
            }
        }
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

            val videoResults = videoAnalyzer.analyzeVideo(
                videoUri = videoUri,
                intervalSeconds = 1.0f,
                onFrameAnalyzed = { boxes, frameWidth, frameHeight, timestamp ->
                    runOnUiThread {
                        binding.overlayView.setResults(
                            boundingBoxes = boxes,
                            labelArray = yoloDetector.labels,
                            imgWidth = frameWidth,
                            imgHeight = frameHeight
                        )
                        val viewWidth = binding.viewFinder.width
                        val viewHeight = binding.viewFinder.height
                        feedbackManager.processDetections(boxes, viewWidth, viewHeight)
                    }
                }
            )

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

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        yoloDetector.close()
        feedbackManager.shutdown()
        voiceCommandManager.destroy()
        powerSaveManager.stop()
    }
}