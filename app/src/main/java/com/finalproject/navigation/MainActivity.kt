package com.finalproject.navigation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.finalproject.navigation.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    private val pathGuideManager = PathGuideManager()

    @Volatile private var isDetectionActive = true
    @Volatile private var isPowerSaveMode = false
    @Volatile private var isVideoRunning = false
    @Volatile private var isClosing = false
    @Volatile private var isForeground = false
    @Volatile private var cancelVideo = false
    private var cameraStarted = false
    private var cameraFailed = false
    private var routeLoading = false
    private var routeRequestId = 0
    // 2차 시안: 경로 안내 상태 (상태 문장 아래 줄에 표시)
    private var routeMessage: String? = null
    private var routeActive = false
    // 카메라 권한을 "다시 묻지 않음"으로 거절해서 폰 설정에서만 켤 수 있는 상태
    private var cameraBlocked = false
    // 탐지 쪽에서 마지막으로 들려준 경고 문장 (없으면 null)
    private var lastWarning: String? = null
    private lateinit var moreBackCallback: OnBackPressedCallback
    // 시연 화면(카메라 전체 화면) 상태
    private var demoMode = false
    private lateinit var demoBackCallback: OnBackPressedCallback
    // 음성 명령 듣기 시작을 알리는 짧은 삐 소리
    private var toneGenerator: ToneGenerator? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            cameraBlocked = false
            initFeatures()
        } else {
            // 거절 후에도 "왜 필요한지 설명"을 할 수 없으면 다시 묻지 않음 상태로 봄
            cameraBlocked = !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            refreshStatus()
        }
    }

    private val microphonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        when {
            granted -> notifyUser(R.string.ui_microphone_ready)
            // 다시 묻지 않음 상태: 폰 설정으로 보내는 창을 띄움
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ->
                showSettingsDialog(R.string.ui_microphone_blocked)
            else -> notifyUser(R.string.ui_microphone_needed)
        }
    }

    private val selectVideoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let(::analyzeSelectedVideo)
        }
    }

    private val speechRecognizerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spokenText = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull().orEmpty()
        if (result.resultCode == RESULT_OK && spokenText.isNotBlank()) {
            handleSpokenDestination(spokenText)
        } else {
            notifyAndSpeak(getString(R.string.ui_route_cancelled))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 처음 실행이면 안내 화면부터 보여 줌
        if (!UiSettings.isOnboarded(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        isDetectionActive = savedInstanceState?.getBoolean("detectionActive", true) ?: true
        routeMessage = savedInstanceState?.getString("routeSummary")
        routeActive = savedInstanceState?.getBoolean("routeActive", false) ?: false
        yoloDetector = YoloDetector(this)
        feedbackManager = FeedbackManager(this)
        videoAnalyzer = VideoAnalyzer(this, yoloDetector)
        cameraExecutor = Executors.newSingleThreadExecutor()

        voiceCommandManager = VoiceCommandManager(this) { command ->
            when (command) {
                VoiceCommandManager.Command.START -> changeDetection(true)
                VoiceCommandManager.Command.PAUSE -> changeDetection(false)
                VoiceCommandManager.Command.STATUS -> speakStatus()
                // 알아듣지 못했을 때도 알려 줌
                VoiceCommandManager.Command.UNKNOWN -> feedbackManager.speak(
                    getString(R.string.ui_voice_unknown), FeedbackManager.SpeechPriority.HIGH)
            }
        }
        powerSaveManager = PowerSaveManager(this) { powerSave ->
            isPowerSaveMode = powerSave
            runOnUiThread { if (!isClosing) refreshStatus() }
        }
        ViewCompat.setAccessibilityHeading(binding.tvMoreTitle, true)
        // 톡백 사용자: 화면 영역이 바뀌면 톡백이 이름을 읽어 줌
        ViewCompat.setAccessibilityPaneTitle(binding.mainPanel, getString(R.string.ui_screen_main))
        ViewCompat.setAccessibilityPaneTitle(binding.morePanel, getString(R.string.ui_screen_more))
        // 톡백: "두 번 탭하여 현재 상태 다시 듣기"
        ViewCompat.replaceAccessibilityAction(binding.statusBlock,
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
            getString(R.string.ui_status_hint), null)

        // 메인 화면
        binding.statusBlock.setOnClickListener { speakStatus() }
        binding.btnToggleDetection.setOnClickListener {
            if (isVideoRunning) {
                cancelVideo = true
                refreshStatus()
            } else if (cameraBlocked && !hasPermission(Manifest.permission.CAMERA)) {
                // 다시 묻지 않음 상태에서는 권한 창이 뜨지 않으므로 폰 설정을 열어 줌
                openAppSettings()
            } else if (!hasPermission(Manifest.permission.CAMERA) || cameraFailed) {
                checkAndRequestPermissions()
            } else {
                changeDetection(!isDetectionActive)
            }
        }
        binding.btnDestinationVoice.setOnClickListener {
            if (routeActive) confirmEndRoute() else startDestinationRecognition()
        }
        binding.btnMore.setOnClickListener { showMore(true) }

        // 더보기 화면
        binding.btnDestinationText.setOnClickListener { showDestinationPicker() }
        binding.btnVoiceCommand.setOnClickListener {
            if (ensureMicrophonePermission()) startVoiceCommand()
        }
        binding.btnLastWarning.setOnClickListener {
            feedbackManager.speak(lastWarning ?: getString(R.string.ui_last_warning_none),
                FeedbackManager.SpeechPriority.HIGH)
        }
        binding.btnSelectVideo.setOnClickListener { openVideoPicker() }
        binding.btnSettings.setOnClickListener {
            speakIfNoScreenReader(getString(R.string.ui_screen_settings))
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnBack.setOnClickListener { showMore(false) }
        moreBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = showMore(false)
        }
        onBackPressedDispatcher.addCallback(this, moreBackCallback)

        // 시연 화면: 더보기에서 켜고, 뒤로가기로 닫음
        binding.btnDemoMode.setOnClickListener { setDemoMode(true) }
        demoBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = setDemoMode(false)
        }
        onBackPressedDispatcher.addCallback(this, demoBackCallback)
        binding.overlayView.onSummaryChanged = { count, name, distance ->
            runOnUiThread { updateDemoHeader(count, name, distance) }
        }
        updateDemoHeader(0, null, 0f)

        refreshStatus()
        checkAndRequestPermissions()
    }

    // 톡백이 켜져 있는지 확인
    private fun isScreenReaderOn(): Boolean {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.isEnabled && manager.isTouchExplorationEnabled
    }

    // 톡백을 안 쓰는 사용자에게만 앱 음성으로 화면 이동을 알림 (톡백과 두 목소리가 겹치지 않게)
    private fun speakIfNoScreenReader(text: String) {
        if (!isScreenReaderOn()) feedbackManager.speak(text, FeedbackManager.SpeechPriority.HIGH)
    }

    // 음성 명령: 삐 소리로 듣기 시작을 알린 뒤 듣기 시작
    // (안내 음성을 먼저 말하면 마이크가 그 말을 명령으로 알아들을 수 있어서 짧은 소리를 씀)
    private fun startVoiceCommand() {
        try {
            if (toneGenerator == null) toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (e: RuntimeException) {
            Log.w(TAG, "삐 소리 재생 실패", e)
        }
        binding.root.postDelayed({
            if (!isClosing) voiceCommandManager.startListening()
        }, 300)
    }

    // 시연 화면: 카메라를 꽉 채우고 위쪽 안내 줄과 아래 문구만 보여 줌 (버튼은 숨김)
    private fun setDemoMode(on: Boolean) {
        demoMode = on
        if (on) showMore(false)
        val normal = if (on) View.GONE else View.VISIBLE
        binding.statusBlock.visibility = normal
        binding.statusDivider.visibility = normal
        binding.bigButtonRow.visibility = normal
        binding.btnMore.visibility = normal
        binding.demoHeader.visibility = if (on) View.VISIBLE else View.GONE
        binding.demoFooter.visibility = if (on) View.VISIBLE else View.GONE
        val pad = if (on) 0 else (16 * resources.displayMetrics.density).toInt()
        binding.mainPanel.setPadding(pad, pad, pad, pad)
        binding.viewFinder.scaleType = if (on) PreviewView.ScaleType.FILL_CENTER
            else PreviewView.ScaleType.FIT_CENTER
        binding.overlayView.fillMode = on
        demoBackCallback.isEnabled = on
        if (on) Toast.makeText(this, R.string.ui_demo_exit_hint, Toast.LENGTH_SHORT).show()
        refreshStatus()
    }

    private fun updateDemoHeader(count: Int, name: String?, distance: Float) {
        binding.demoCount.text = getString(R.string.demo_count, count)
        binding.demoNearest.text = when {
            name == null -> getString(R.string.demo_nearest_none)
            distance > 0f -> getString(R.string.demo_nearest, name, distance)
            else -> getString(R.string.demo_nearest_no_distance, name)
        }
    }

    private fun showMore(show: Boolean) {
        speakIfNoScreenReader(getString(if (show) R.string.ui_screen_more else R.string.ui_screen_main))
        binding.morePanel.visibility = if (show) View.VISIBLE else View.GONE
        binding.mainPanel.visibility = if (show) View.GONE else View.VISIBLE
        moreBackCallback.isEnabled = show
        if (show) {
            // 톡백 초점을 더보기 제목으로 옮김
            binding.tvMoreTitle.post {
                binding.tvMoreTitle.performAccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
            }
        } else {
            binding.btnMore.requestFocus()
        }
    }

    // 같은 자리 버튼이 "목적지 말하기" ↔ "안내 끝내기"로 바뀌어 잘못 누르기 쉬우므로 한 번 확인
    private fun confirmEndRoute() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ui_route_end_confirm)
            .setPositiveButton(R.string.ui_route_end_yes) { _, _ -> endRoute() }
            .setNegativeButton(R.string.ui_route_end_no, null)
            .show()
    }

    private fun endRoute() {
        routeRequestId++
        routeLoading = false
        routeActive = false
        // 끝냈다는 문장을 상태 아래 줄에 남김 (톡백은 이 줄이 바뀌면 스스로 읽음)
        val message = getString(R.string.ui_route_ended)
        routeMessage = message
        refreshStatus()
        speakIfNoScreenReader(message)
    }

    // 토스트는 톡백이 스스로 읽으므로, 톡백이 꺼져 있을 때만 앱 음성으로도 말함
    private fun notifyAndSpeak(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        speakIfNoScreenReader(message)
    }

    // 이 앱의 폰 설정 화면(권한 켜는 곳)을 엶
    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "설정 화면 열기 실패", e)
        }
    }

    // 권한이 막혔을 때: 이유를 말해 주고 [설정 열기]를 보여 줌
    private fun showSettingsDialog(messageId: Int) {
        speakIfNoScreenReader(getString(messageId))
        MaterialAlertDialogBuilder(this)
            .setMessage(messageId)
            .setPositiveButton(R.string.ui_open_settings) { _, _ -> openAppSettings() }
            .setNegativeButton(R.string.ui_cancel, null)
            .show()
    }

    // 최근 경고 한 줄을 보여 줌.
    // 탐지 쪽(FeedbackManager)에서 경고를 말할 때 이 함수를 불러 주면 화면과 [마지막 경고 다시 듣기]에 나타남.
    // 아직 연결 전이라 지금은 불리지 않음 (연결 방법은 설계서 참고)
    fun showLastWarning(text: String) {
        if (isClosing || text.isBlank()) return
        lastWarning = text
        binding.tvLastWarning.text = getString(R.string.ui_last_warning, text)
        binding.tvLastWarning.visibility = View.VISIBLE
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun checkAndRequestPermissions() {
        if (hasPermission(Manifest.permission.CAMERA)) initFeatures()
        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun ensureMicrophonePermission(): Boolean {
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) return true
        notifyUser(R.string.ui_microphone_needed)
        microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        return false
    }

    private fun initFeatures() {
        if (!cameraStarted || cameraFailed) startCamera()
        powerSaveManager.start()
    }

    private fun changeDetection(active: Boolean) {
        if (isVideoRunning || isClosing) return
        isDetectionActive = active
        if (!active) binding.overlayView.setResults(emptyList())
        val before = binding.tvStatus.text.toString()
        refreshStatus()
        // 톡백이 켜져 있고 상태 문장이 바뀌었으면 톡백이 읽으므로 앱 음성은 생략 (두 목소리 겹침 방지).
        // 상태가 그대로면(예: 이미 켜져 있는데 "시작"이라고 말함) 톡백이 읽지 않으니 앱이 말함
        if (!isScreenReaderOn() || before == binding.tvStatus.text.toString()) speakStatus()
    }

    private fun refreshStatus() {
        val status = when {
            isVideoRunning && cancelVideo -> R.string.ui_video_stopping
            isVideoRunning -> R.string.ui_status_video
            !hasPermission(Manifest.permission.CAMERA) && cameraBlocked -> R.string.ui_status_blocked
            !hasPermission(Manifest.permission.CAMERA) -> R.string.ui_status_permission
            cameraFailed -> R.string.ui_status_camera_error
            !cameraStarted -> R.string.ui_status_waiting
            !isDetectionActive -> R.string.ui_status_paused
            isPowerSaveMode -> R.string.ui_status_passive
            else -> R.string.ui_status_active
        }
        val detail = routeMessage ?: getString(when (status) {
            R.string.ui_status_paused -> R.string.ui_status_paused_detail
            R.string.ui_status_blocked -> R.string.ui_status_blocked_detail
            R.string.ui_status_permission, R.string.ui_status_camera_error -> R.string.ui_status_permission_detail
            R.string.ui_status_video, R.string.ui_video_stopping -> R.string.ui_status_video_detail
            else -> R.string.ui_status_detail
        })
        binding.tvStatus.setText(status)
        binding.tvStatusDetail.text = detail
        // 시연 화면 위쪽 줄의 상태 점과 글자
        val demoActive = status == R.string.ui_status_active || status == R.string.ui_status_passive
        binding.demoState.setText(if (demoActive) R.string.demo_state_active else R.string.demo_state_paused)
        binding.demoDot.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (demoActive) android.graphics.Color.parseColor("#7EE08A")
            else ContextCompat.getColor(this, R.color.ui_stopped))
        if (!demoActive) updateDemoHeader(0, null, 0f)
        val stopped = status == R.string.ui_status_paused || status == R.string.ui_status_permission ||
            status == R.string.ui_status_camera_error || status == R.string.ui_status_blocked
        binding.tvStatus.setTextColor(ContextCompat.getColor(this,
            if (stopped) R.color.ui_stopped else R.color.ui_text))

        binding.btnToggleDetection.setText(when {
            isVideoRunning -> R.string.ui_video_stop
            !hasPermission(Manifest.permission.CAMERA) && cameraBlocked -> R.string.ui_permission_settings
            !hasPermission(Manifest.permission.CAMERA) || cameraFailed -> R.string.ui_permission_retry
            isDetectionActive -> R.string.ui_pause
            else -> R.string.ui_resume
        })
        binding.btnToggleDetection.setIconResource(when {
            isVideoRunning -> R.drawable.ic_ui_stop
            isDetectionActive && hasPermission(Manifest.permission.CAMERA) && !cameraFailed -> R.drawable.ic_ui_pause
            else -> R.drawable.ic_ui_play
        })
        binding.btnToggleDetection.isEnabled = !cancelVideo
        binding.btnDestinationVoice.setText(if (routeActive) R.string.ui_route_end else R.string.ui_destination_voice)
        binding.btnDestinationVoice.setIconResource(if (routeActive) R.drawable.ic_ui_stop else R.drawable.ic_ui_mic)
        binding.btnDestinationVoice.isEnabled = routeActive || (!routeLoading && !isVideoRunning)
        binding.btnSelectVideo.isEnabled = !isVideoRunning
        binding.btnVoiceCommand.isEnabled = !isVideoRunning
        binding.btnDestinationText.isEnabled = !routeLoading && !isVideoRunning
    }

    private fun speakStatus() {
        // 최근 경고가 있으면 상태 다음에 이어서 읽음
        val warning = lastWarning?.let { " ${getString(R.string.ui_last_warning, it)}" }.orEmpty()
        val text = "${binding.tvStatus.text}. ${binding.tvStatusDetail.text}$warning"
        feedbackManager.speak(text, FeedbackManager.SpeechPriority.HIGH)
    }

    private fun notifyUser(messageId: Int) {
        Toast.makeText(this, messageId, Toast.LENGTH_LONG).show()
    }

    private fun startCamera() {
        cameraFailed = false
        cameraStarted = false
        refreshStatus()
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (isClosing) return@addListener
            try {
                val cameraProvider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }
                val analyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build().also { analysis ->
                        analysis.setAnalyzer(cameraExecutor) { image ->
                            try {
                                if (isClosing || !isForeground || !isDetectionActive || isVideoRunning ||
                                    (isPowerSaveMode && System.currentTimeMillis() % 2 != 0L)) {
                                    return@setAnalyzer
                                }
                                val results = yoloDetector.detect(image)
                                val rotated = image.imageInfo.rotationDegrees % 180 != 0
                                val width = if (rotated) image.height else image.width
                                val height = if (rotated) image.width else image.height
                                runOnUiThread {
                                    if (!isClosing && isForeground && isDetectionActive && !isVideoRunning) {
                                        binding.overlayView.setResults(results, yoloDetector.labels, width, height)
                                        // 탐지 상자는 미리보기 칸 좌표가 아니라 원본 이미지 픽셀 기준으로 계산
                                        feedbackManager.processDetections(results, width, height)
                                        val guidance = pathGuideManager.evaluatePathAlignment(results, width)
                                        if (guidance.message.isNotBlank()) feedbackManager.speak(guidance.message)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Frame analysis failed", e)
                            } finally {
                                image.close()
                            }
                        }
                    }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer)
                cameraStarted = true
            } catch (e: Exception) {
                cameraFailed = true
                Log.e(TAG, "Camera binding failed", e)
                notifyUser(R.string.ui_camera_error_help)
            }
            refreshStatus()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startDestinationRecognition() {
        if (routeLoading || isVideoRunning || !ensureMicrophonePermission()) return
        voiceCommandManager.stopListening()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.ui_choose_destination))
        }
        try {
            speechRecognizerLauncher.launch(intent)
        } catch (e: Exception) {
            notifyUser(R.string.ui_speech_unavailable)
        }
    }

    private fun showDestinationPicker() {
        if (routeLoading || isVideoRunning) return
        val destinations = resources.getStringArray(R.array.ui_destinations)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ui_choose_destination)
            .setItems(destinations) { _, index ->
                showMore(false)
                handleSpokenDestination(destinations[index])
            }
            .setNegativeButton(R.string.ui_cancel, null)
            .show()
    }

    private fun handleSpokenDestination(destinationText: String) {
        if (routeLoading || isClosing) return
        // 기존 데모 목적지 두 곳(도서관, 정문)과 고정 출발점을 그대로 사용
        val destination = when {
            destinationText.contains("도서관") -> "도서관"
            destinationText.contains("정문") -> "정문"
            else -> {
                notifyAndSpeak(getString(R.string.ui_route_unavailable))
                return
            }
        }
        val (endLat, endLng) = if (destination == "도서관") 37.5560 to 126.9720
            else 37.5550 to 126.9700
        routeLoading = true
        val requestId = ++routeRequestId
        routeActive = true
        routeMessage = getString(R.string.ui_route_loading, destination)
        refreshStatus()
        TMapClient.requestRoute(126.9780, 37.5665, endLng, endLat) { response ->
            if (isClosing || requestId != routeRequestId) return@requestRoute
            routeLoading = false
            val properties = response?.features?.firstOrNull()?.properties
            val distance = properties?.totalDistance
            val seconds = properties?.totalTime
            val ok = distance != null && seconds != null && distance > 0 && seconds > 0
            val message = if (ok) {
                getString(R.string.ui_route_result, destination, distance!!, (seconds!! + 59) / 60)
            } else getString(R.string.ui_route_failed)
            routeActive = ok
            routeMessage = message
            refreshStatus()
            // 톡백은 상태 아래 줄이 바뀌면 스스로 읽으므로 톡백이 꺼져 있을 때만 말함
            speakIfNoScreenReader(message)
        }
    }

    fun openVideoPicker() {
        if (isVideoRunning) return
        voiceCommandManager.stopListening()
        val intent = Intent(Intent.ACTION_PICK).apply { type = "video/*" }
        try { selectVideoLauncher.launch(intent) }
        catch (e: Exception) { notifyUser(R.string.ui_video_error) }
    }

    private fun analyzeSelectedVideo(uri: Uri) {
        if (isVideoRunning || isClosing) return
        isVideoRunning = true
        cancelVideo = false
        binding.viewFinder.visibility = View.INVISIBLE
        binding.tvVideoResult.visibility = View.GONE
        binding.overlayView.setResults(emptyList())
        refreshStatus()
        cameraExecutor.execute {
            var resultCount = -1
            try {
                // 팀 VideoAnalyzer에는 중간 멈춤 기능이 없어서, 멈춤을 누르면 화면 표시·경고만 멈추고 분석은 영상 끝까지 돎
                val results = videoAnalyzer.analyzeVideo(uri, 1.0f) { boxes, width, height, _ ->
                    runOnUiThread {
                        if (!isClosing && !cancelVideo) {
                            binding.overlayView.setResults(boxes, yoloDetector.labels, width, height)
                            feedbackManager.processDetections(boxes, width, height)
                        }
                    }
                }
                resultCount = results.size
            } catch (e: Exception) {
                Log.e(TAG, "Video analysis failed", e)
            } finally {
                val count = resultCount
                runOnUiThread {
                    isVideoRunning = false
                    if (!isClosing) {
                        binding.viewFinder.visibility = View.VISIBLE
                        binding.overlayView.setResults(emptyList())
                        binding.tvVideoResult.visibility = View.VISIBLE
                        binding.tvVideoResult.text = when {
                            cancelVideo -> getString(R.string.ui_video_cancelled)
                            count >= 0 -> getString(R.string.ui_video_done, count)
                            else -> getString(R.string.ui_video_error)
                        }
                        cancelVideo = false
                        refreshStatus()
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("detectionActive", isDetectionActive)
        if (!routeLoading) {
            routeMessage?.let { outState.putString("routeSummary", it) }
            outState.putBoolean("routeActive", routeActive)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        isForeground = false
        if (isVideoRunning) cancelVideo = true
        voiceCommandManager.stopListening()
        powerSaveManager.stop()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        // 폰 설정에서 카메라를 허용하고 돌아온 경우 바로 카메라를 켬
        if (::binding.isInitialized && cameraBlocked && hasPermission(Manifest.permission.CAMERA)) {
            cameraBlocked = false
            initFeatures()
        }
        if (::binding.isInitialized) refreshStatus()
        if (::powerSaveManager.isInitialized && cameraStarted) powerSaveManager.start()
    }

    override fun onDestroy() {
        // 안내 화면으로 바로 넘어간 경우에는 정리할 것이 없음
        if (!::powerSaveManager.isInitialized) {
            super.onDestroy()
            return
        }
        isClosing = true
        routeRequestId++
        voiceCommandManager.destroy()
        toneGenerator?.release()
        toneGenerator = null
        powerSaveManager.stop()
        feedbackManager.shutdown()

        cameraExecutor.execute { yoloDetector.close() }
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object { private const val TAG = "MainActivity" }
}
