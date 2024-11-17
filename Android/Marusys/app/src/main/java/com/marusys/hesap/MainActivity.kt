package com.marusys.hesap

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import com.marusys.hesap.MainActivity.Constants.RECORDING_TIME
import com.marusys.hesap.MainActivity.Constants.SAMPLE_RATE
import com.marusys.hesap.MainActivity.Constants.STEP_SIZE
import com.marusys.hesap.MainActivity.Constants.THRESHOLD
import com.marusys.hesap.MainActivity.Constants.TRIGGER_WORD
import com.marusys.hesap.MainActivity.Constants.WINDOW_SIZE
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.marusys.hesap.classifier.AudioClassifier
import com.marusys.hesap.classifier.BaseAudioClassifier
import com.marusys.hesap.classifier.ResnetClassifier
import com.marusys.hesap.feature.MemoryUsageManager
import com.marusys.hesap.feature.VoiceRecognitionState
import com.marusys.hesap.feature.VoiceStateManager
import com.marusys.hesap.presentation.screen.AudioScreen
import com.marusys.hesap.presentation.viewmodel.MainViewModel
import com.marusys.hesap.util.ThresholdUtil
import com.marusys.hesap.service.AudioService
import kotlinx.coroutines.launch


class MainActivity : ComponentActivity() {

    // 여러 페이지에서 사용하는 값을 관리하는 viewModel
    private val mainViewModel = MainViewModel()
    private val handler = Handler(Looper.getMainLooper())
    private val OVERLAY_PERMISSION_REQUEST_CODE = 1

    // 모델 타입
    enum class ModelType {
        RESNET, CNN, GRU
    }

    // CNN & RNN은 같은 AudioClassifer 클래스를 사용하므로 (RNN <-> CNN) 변경 시 클래스 내부 tflite 모델 (RNN <-> CNN) 변경 필요
    var MODEL_TYPE: ModelType = ModelType.RESNET

    // 녹음 관련 상수 정의
    object Constants {

        // 호출어 성공 여부 판단을 위한 임계값
        const val THRESHOLD = 0.95

        // 샘플 레이트 16KHz (16000Hz)
        const val SAMPLE_RATE = 16000

        // 녹음 시간 (2초)
        const val RECORDING_TIME = 2

        // 전체 window size
        const val WINDOW_SIZE = SAMPLE_RATE * RECORDING_TIME

        // sliding window 사이즈 (겹치는 구간)
        const val STEP_SIZE = SAMPLE_RATE / 2

        // Resnet Softmax 분류를 위한 트리거워드 설정
        const val TRIGGER_WORD = "hey_ssafy"
    }


    // AudioService에서 인식한 음성 명령어를 MainActivity에서 받는 콜백함수
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "SPEECH_RECOGNITION_RESULT" -> {
                    val matches = intent.getStringArrayListExtra("matches")
                    val recognizedText = matches?.firstOrNull() ?: ""

                    // 현재 표시 중인 윈도우 매니져가 있다면 메시지 업데이트
                    mainViewModel.setCommandText(recognizedText)
                }

                "AUDIO_SERVICE_STATE_CHANGED" -> {
                    val isRunning = intent.getBooleanExtra("isRunning", false)
                    mainViewModel.setAudioServiceRunning(isRunning)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        // 오디오 녹음 권한이 있는지 확인
        checkAndRequestPermissions()

        // 오버레이 권한 체크 및 요청
        checkOverlayPermission()

        // BroadcastReceiver 등록
        LocalBroadcastManager.getInstance(this).registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction("SPEECH_RECOGNITION_RESULT")
                addAction("AUDIO_SERVICE_STATE_CHANGED")
            }
        )

        // 상태 관찰
        lifecycleScope.launch {
            VoiceStateManager.voiceState.collect { state ->
                when (state) {
                    is VoiceRecognitionState.WaitingForHotword -> {
                        Log.e("", "호출어 대기 상태")
                        startRecordingWithModel()
                    }

                    is VoiceRecognitionState.HotwordDetecting -> {
                        Log.e("", "호출어 인식 상태")
                    }

                    is VoiceRecognitionState.CommandListening -> {
                        Log.e("", "명령 들은 상태")
                    }

                    else -> {
                        // 다른 상태 처리
                    }
                }
            }
        }

        setContent {
            Surface(
                modifier = Modifier.fillMaxSize()
            ) {

                AudioScreen(
                    viewModel = mainViewModel,
                )
            }
        }
    }

    /*
     *  각종 권한 체크 함수
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            1 -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    // 모든 권한이 승인된 경우
                    startRecordingWithModel()
                } else {
                    // 권한이 거부된 경우
                    Toast.makeText(this, "권한이 필요합니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
        }
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "오버레이 권한이 필요합니다", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver)
    }

    // 권한 체크 및 요청
    private fun checkAndRequestPermissions() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA),
                1
            )
        }
    }

    // 사용자에게 모델 타입을 선택할 수 있게 해주는 메서드
    private fun startRecordingWithModel() {
        when (MODEL_TYPE) {
            ModelType.RESNET -> resnetRealTimeRecordAndClassify()
            ModelType.CNN -> cnnRealTimeRecordAndClassify()
            ModelType.GRU -> gruRealTimeRecordAndClassify()
        }
    }


    /*
     * 음성인식 수행 메서드
     */

    // 음성 인식 및 Classifer 기반 분석 수행 공통 메서드
    fun startRealTimeRecognition(
        classifierProvider: () -> BaseAudioClassifier, // 모델별 분류기 생성자
    ) {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * RECORDING_TIME

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        runOnUiThread {
            mainViewModel.setResultText("듣고 있는 중이에요.")
        }

        Thread {
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("MainActivity", "AudioRecord 초기화 실패")
                runOnUiThread { mainViewModel.setResultText("녹음 초기화 실패") }
                return@Thread
            }

            val audioBuffer = ShortArray(bufferSize / 2)
            val slidingWindowBuffer = FloatArray(WINDOW_SIZE)
            var bufferPosition = 0

            audioRecord.startRecording()

            while (VoiceStateManager.voiceState.value == VoiceRecognitionState.WaitingForHotword) {
                val readSize = audioRecord.read(audioBuffer, 0, audioBuffer.size)
                if (readSize > 0) {
                    for (i in 0 until readSize) {
                        slidingWindowBuffer[bufferPosition] = audioBuffer[i] / 32768.0f
                        bufferPosition++

                        if (bufferPosition >= WINDOW_SIZE) {
                            bufferPosition = 0
                            try {

                                // 선택한 classifier 기반 음성 분류 실행
                                val classifier = classifierProvider()
                                val results = classifier.classify(slidingWindowBuffer)

                                // Resnet 음성인식 결과 처리 -> Softmax
                                if (MODEL_TYPE == ModelType.RESNET) {
                                    processResNetResults(results, classifier)
                                } else {
                                    // CNN / GRU 음성인식 결과 처리 -> Sigmoid
                                    processOtherModelResults(results)
                                }

                            } catch (e: Exception) {
                                Log.e("MainActivity", "분류 중 오류 발생", e)
                                runOnUiThread {
                                    mainViewModel.setResultText("분류 중 오류가 발생했습니다: ${e.message}")
                                }
                            }

                            // 슬라이딩 윈도우 이동
                            System.arraycopy(
                                slidingWindowBuffer,
                                STEP_SIZE,
                                slidingWindowBuffer,
                                0,
                                WINDOW_SIZE - STEP_SIZE
                            )
                            bufferPosition = WINDOW_SIZE - STEP_SIZE
                        }
                    }
                }
            }

            audioRecord.stop()
            audioRecord.release()
        }.start()
    }

    // 모델별 음성인식 메서드
    fun cnnRealTimeRecordAndClassify() {
        startRealTimeRecognition(
            classifierProvider = {AudioClassifier(this)},
        )
    }

    fun gruRealTimeRecordAndClassify() {
        startRealTimeRecognition(
            classifierProvider = {AudioClassifier(this)},
        )
    }

    fun resnetRealTimeRecordAndClassify() {
        startRealTimeRecognition(
            classifierProvider = {ResnetClassifier(this)},
        )
    }

    // ResNet 결과 처리 메서드
    private fun processResNetResults(
        results: FloatArray,
        classifier: BaseAudioClassifier,
    ) {

        // Softmax 계산 알고리즘을 통해 가장 높은 확률의 라벨 정확도 계산
        val accuracy = ThresholdUtil.checkTrigger(results)

        // 선택된 라벨 할당
        val resultLabel = classifier.getLabel(results)

        val resultText = "${resultLabel} : ${String.format("%.2f%%", accuracy * 100)}"

        // UI에 선택된 라벨과 정확도 출력
        runOnUiThread { mainViewModel.setResultText(resultText) }

        // 정확도가 THRESHOLD 이상이고 내가 원하는 호출어가 맞다면 TTS 음성인식 수행
        if (accuracy >= THRESHOLD && resultLabel == TRIGGER_WORD) {
            runOnUiThread { startAudioService() }
        }
    }

    // CNN & GRU 결과 처리 메서드
    private fun processOtherModelResults(
        results: FloatArray,
    ) {

        val accuracy = String.format("%.2f%%", results[0] * 100)

        val resultText = "확률값 : $accuracy"

        // UI에 정확도 출력
        runOnUiThread { mainViewModel.setResultText(resultText) }

        // 정확도가 THRESHOLD 이상인 경우 TTS 음성인식 수행
        if (results[0] >= THRESHOLD) {
            runOnUiThread { startAudioService() }
        }
    }


    // 로출어 인식 -> 서비스 시작
    private fun startAudioService() {
        val bundle = Bundle()
        bundle.putString("commandText", mainViewModel.commandText.value)
        bundle.putBoolean("isAudioServiceRunning", true)

        // intent AudioService로 넘기기
        val serviceIntent = Intent(this, AudioService::class.java)
        serviceIntent.putExtra("viewModelState", bundle)

        // 포그라운드 Service 시작
        // ContextCompat.startForegroundService(this, serviceIntent)

        VoiceStateManager.updateState(VoiceRecognitionState.HotwordDetecting) // 호출어 인식 완료, isListen = false
        startService(serviceIntent)
    }

    // 서비스 종료
    private fun stopAudioService() {
        // 배경 변경
        mainViewModel.setAudioServiceRunning(false)
        val serviceIntent = Intent(this, AudioService::class.java)
        stopService(serviceIntent)
        VoiceStateManager.updateState(VoiceRecognitionState.WaitingForHotword) // 호출어 대기
    }

}



