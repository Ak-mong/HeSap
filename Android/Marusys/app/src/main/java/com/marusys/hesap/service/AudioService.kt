package com.marusys.hesap.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.marusys.hesap.classifier.AudioClassifier
import com.marusys.hesap.feature.VoiceRecognitionEngine
import com.marusys.hesap.feature.VoiceRecognitionState
import com.marusys.hesap.feature.VoiceStateManager
import com.marusys.hesap.presentation.components.Notification
import com.marusys.hesap.presentation.components.OverlayContent
import com.marusys.hesap.presentation.viewmodel.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

private val TAG = "AudioService"

class AudioService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private object PackageName {
        const val YOUTUBE = "com.google.android.youtube"
        const val KAKAO = "com.kakao.talk"
    }

    private object UrlName{
        const val WEATHER = "https://www.weather.go.kr/weather/special/special_03_final.jsp?sido=4700000000&gugun=4719000000&dong=4792032000"
    }


    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var recognizerIntent: Intent
    private lateinit var classifier: AudioClassifier  // AudioClassifier 인스턴스
    private lateinit var voiceEngine: VoiceRecognitionEngine
    private val mainViewModel: MainViewModel by lazy {
        MainViewModel()
    }

    // 손전등 관련
    private lateinit var cameraManager: CameraManager
    private var cameraId: String? = null

    // 알림창
    private lateinit var notificationManager: Notification

    // 오베리이 관련
    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null

    override fun onCreate() {
        super.onCreate()
        Log.e(TAG, "오디오 서비스 시작")
        playSoundFromAssets("ne.wav") // WAV 파일 재생
        VoiceStateManager.updateState(VoiceRecognitionState.HotwordDetecting)
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        notificationManager = Notification(this)
        classifier =
            AudioClassifier(this)  // AudioClassifier 초기화
        // 윈도우 매니져 서비스 시작
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        // 카메라 초기화- 손전등 관련 코드
        initializeCamera()
        // SpeechRecognizer 시작
        initializeSpeechRecognizer()
        // 포 그라운드 시작
        // startForeground(NOTIFICATION_ID, createNotification())
        // 10초 있다가 종료
        Handler(Looper.getMainLooper()).postDelayed({ stopListening() }, 10000) // 디자인 작업 이후 주석 해제
        // 서비스 상태 변경
        updateServiceState(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startListening() // 명령 인식 시작
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        updateServiceState(false)
        speechRecognizer.destroy()
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
        }
        serviceScope.cancel()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        VoiceStateManager.updateState(VoiceRecognitionState.WaitingForHotword) // 키워드 대기상태
    }

    // 음성녹음 초기화
    private fun initializeSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(recognitionListener)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            // FREE_FORM : 언어 모델이 자유 형식의 음성을 인식하도록 지정-> 일반적인 대화나 다양한 주제의 음성을 인식하는 데 적합
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            // 서비스를 호출하는 앱 패키지 이름을 지정 -> 인식 결과를 올바른 앱으로 반환
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            // 반환할 최대 인식 결과 수, 가장 가능성 높은 거만
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // 최소 밀리 세컨드 이상
//            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 100)
//             1초 정도 정적이 있으면 음성 인식을 완료됐을 가능성 있다고 판단
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 100)
            // 일리 세컨드 정도 완전한 침묵 = 입력 완
//            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 100)
        }
    }

    // 카메라 초기화
    private fun initializeCamera() {
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraId = cameraManager.cameraIdList.firstOrNull {
            cameraManager.getCameraCharacteristics(it)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle?) {
            val match = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            match?.firstOrNull()?.let { command ->
                mainViewModel.setCommandText(command)
            }
            Log.e(TAG, "partialResults = $match")
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            matches?.firstOrNull()?.let { command ->
                mainViewModel.setCommandText(command)
                if (executeCommand(command)) {
                    stopListening()
                }
            }
            Log.e(TAG, "results $matches")
            val intent = Intent("SPEECH_RECOGNITION_RESULT")
            intent.putStringArrayListExtra("matches", matches)
            LocalBroadcastManager.getInstance(this@AudioService).sendBroadcast(intent)
            startListening()
        }
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d("SpeechRecognizer", "onReadyForSpeech to listen...")
        }

        override fun onBeginningOfSpeech() {
            Log.d("SpeechRecognizer", "onBeginningOfSpeech to listen...")
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            val errorMessage = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "오디오 녹음 오류"
                SpeechRecognizer.ERROR_CLIENT -> "클라이언트 측 오류"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "권한 부족"
                SpeechRecognizer.ERROR_NETWORK -> "네트워크 오류"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "네트워크 시간 초과"
                SpeechRecognizer.ERROR_NO_MATCH -> "일치하는 음성 없음"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "음성 인식기 사용 중"
                SpeechRecognizer.ERROR_SERVER -> "서버 오류"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "음성 입력 시간 초과"
                else -> "알 수 없는 오류"
            }
            Log.e("AudioService", "Speech recognition error: $errorMessage")
            Handler(Looper.getMainLooper()).postDelayed({ startListening() }, 1000)
        }
    }

    private fun startListening() {
        speechRecognizer.startListening(recognizerIntent)
        // 오버레이
        if (overlayView == null) {
            overlayView = ComposeView(this).apply {
                setViewTreeLifecycleOwner(this@AudioService)
                setViewTreeSavedStateRegistryOwner(this@AudioService)
                setContent {
                    OverlayContent({ stopListening() }, mainViewModel)
                }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL  // 하단 중앙에 위치
            }
            windowManager.addView(overlayView, params)
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

    }

    private fun executeCommand(command: String): Boolean {
        var executeCommant = true

        when {
            // 명령어 하드 코딩
            command.contains("손전등 켜", ignoreCase = true) -> toggleFlashlight(true)
            command.contains("손전등 꺼", ignoreCase = true) -> toggleFlashlight(false)
            command.contains("날씨", ignoreCase = true) -> {
                weatherInBrowser(UrlName.WEATHER)
                playSoundFromAssets("weather.wav") // WAV 파일 재생
            }
            command.contains("유튜브", ignoreCase = true) -> openApp(PackageName.YOUTUBE)
            command.contains("카카오톡", ignoreCase = true) -> openApp(PackageName.KAKAO)
            else -> executeCommant = false
        }
        return executeCommant
    }
    private fun playSoundFromAssets(fileName: String) {
        try {
            val assetManager = assets
            val assetFileDescriptor = assetManager.openFd(fileName)
            val mediaPlayer = MediaPlayer()
            mediaPlayer.setDataSource(
                assetFileDescriptor.fileDescriptor,
                assetFileDescriptor.startOffset,
                assetFileDescriptor.length
            )
            mediaPlayer.prepare()
            mediaPlayer.start()
            mediaPlayer.setOnCompletionListener {
                it.release() // 재생 완료 후 MediaPlayer 해제
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }




    private fun updateServiceState(isRunning: Boolean) {
        val intent = Intent("AUDIO_SERVICE_STATE_CHANGED")
        intent.putExtra("isRunning", isRunning)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun stopListening() {
        val intent = Intent(this, AudioService::class.java)
//        speechRecognizer.stopListening()
        speechRecognizer.destroy()
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
        }
        updateServiceState(false)
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        VoiceStateManager.updateState(VoiceRecognitionState.WaitingForHotword) // 키워드 대기상태
        stopService(intent) // 서비스 종료 = 오버레이와 음성인식 종료
    }

    // 손전등 on off
    private fun toggleFlashlight(on: Boolean) {
        cameraId?.let { id ->
            cameraManager.setTorchMode(id, on)
        }
    }

    private fun weatherInBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)  // 새로운 태스크로 실행하도록 플래그 추가
        startActivity(intent)
    }

    private fun openApp(packageName: String) {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.setPackage(packageName)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "앱이 설치되어 있지 않습니다.", Toast.LENGTH_SHORT).show()
        }
    }


}