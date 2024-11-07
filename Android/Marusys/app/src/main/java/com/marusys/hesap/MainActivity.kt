package com.marusys.hesap

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import com.marusys.hesap.MainActivity.Constants.RECORDING_TIME
import com.marusys.hesap.MainActivity.Constants.SAMPLE_RATE
import com.marusys.hesap.MainActivity.Constants.STEP_SIZE
import com.marusys.hesap.MainActivity.Constants.THRESHOLD
import com.marusys.hesap.MainActivity.Constants.WINDOW_SIZE
import com.marusys.hesap.feature.MemoryUsageManager
import com.marusys.hesap.presentation.screen.AudioScreen
import com.marusys.hesap.presentation.viewmodel.MainViewModel
import com.marusys.hesap.util.ThresholdUtil
import java.util.Arrays


class MainActivity : ComponentActivity() {

    // 여러 페이지에서 사용하는 값을 관리하는 viewModel
    private val mainViewModel = MainViewModel()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var memoryUsageManager: MemoryUsageManager

    object Constants {
        // 변경되지 않는 상수 값
        const val THRESHOLD = 0.95
        const val SAMPLE_RATE = 16000   // 샘플 레이트 16KHz (16000Hz)
        const val RECORDING_TIME = 2    // 녹음 시간 (2초)
        const val WINDOW_SIZE = SAMPLE_RATE * RECORDING_TIME  // 전체 window size
        const val STEP_SIZE = SAMPLE_RATE / 2     // sliding window 사이즈 (겹치는 구간)

        // 라벨 정의 (모델 학습 시 사용한 라벨에 맞게 수정)
        val LABELS = arrayOf("unknown", "ssafy")
    }

    // 모델 타입
    enum class ModelType {
        RESNET, CNN
    }

    // 모델 타입을 결정하는 변수 (초기값 설정 가능)
    var MODEL_TYPE: ModelType = ModelType.CNN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 오디오 녹음 권한이 있는지 확인
        checkAndRequestPermissions()
        // 메모리 사용량 관리자 초기화
        initializeMemoryUsageManager()

        setContent {
            AudioScreen(
                viewModel = mainViewModel,
                recordButtons = {resnetClassify()}
            )
        }
    }

    /*
     * 메모리 사용량 체크 메서드
     */
    private val updateMemoryRunnable = object : Runnable {
        override fun run() {
            mainViewModel.setMemoryText(memoryUsageManager.getMemoryUsage())
            handler.postDelayed(this, 1000) // 1초마다 업데이트
        }
    }

    private fun initializeMemoryUsageManager() {
        memoryUsageManager = MemoryUsageManager(this)
    }

    override fun onResume() {
        super.onResume()
        startRecordingWithModel();
        handler.post(updateMemoryRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateMemoryRunnable)
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
            ModelType.RESNET -> cnnRealTimeRecordAndClassify()
            ModelType.CNN -> cnnRealTimeRecordAndClassify()
        }
    }

    // ======== 음성 인식 기반 분류 ========
    
    // 호출어 인식 여부에 따라 스레드 일시 중단 시키기 위한 변수
    private var isListening = false

    fun cnnRealTimeRecordAndClassify() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * RECORDING_TIME

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        // 상태 표시
        runOnUiThread {
            mainViewModel.setResultText("녹음 중...")
        }

        Thread {
            isListening = true  // 스레드 실행 시작
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("MainActivity", "AudioRecord 초기화 실패")
                runOnUiThread {
                    mainViewModel.setResultText("녹음 초기화 실패")
                }
                return@Thread
            }

            val audioBuffer = ShortArray(bufferSize / 2)
            val slidingWindowBuffer = FloatArray(WINDOW_SIZE)  // 1초 버퍼
            var bufferPosition = 0

            audioRecord.startRecording()

            // 실시간으로 데이터를 읽어들여 모델로 전달
            while (isListening) {
                val readSize = audioRecord.read(audioBuffer, 0, audioBuffer.size)
                if (readSize > 0) {
                    for (i in 0 until readSize) {
                        slidingWindowBuffer[bufferPosition] = audioBuffer[i] / 32768.0f
                        bufferPosition++

                        // 슬라이딩 윈도우가 채워졌으면 호출어 검출을 수행
                        if (bufferPosition >= WINDOW_SIZE) {
                            bufferPosition = 0

                            try {
                                val classifier = AudioClassifier(this)
                                val inputBuffer = classifier.createInputBuffer(slidingWindowBuffer)
                                val results = classifier.classify(inputBuffer)

                                // results[0] 값을 실시간으로 화면에 표시
                                runOnUiThread {
                                    val percentage = String.format("%.2f%%", results[0] * 100)

                                    mainViewModel.setResultText("확률값: $percentage")
                                }

                                // 호출어가 감지되면 팝업을 띄우고 스레드를 중단
                                if (results[0] >= THRESHOLD) {
                                    runOnUiThread {
                                        showSuccessDialog()
                                    }
                                    isListening = false  // 스레드 중단
                                    break  // 루프 종료
                                }
                            } catch (e: Exception) {
                                Log.e("MainActivity", "분류 중 오류 발생", e)
                                runOnUiThread {
                                    mainViewModel.setResultText("분류 중 오류가 발생했습니다: " + e.message)
                                }
                            }

                            // 슬라이딩 윈도우를 50% 이동시키기 위해 이전 데이터를 복사
                            System.arraycopy(slidingWindowBuffer, STEP_SIZE, slidingWindowBuffer, 0, WINDOW_SIZE - STEP_SIZE)
                            bufferPosition = WINDOW_SIZE - STEP_SIZE
                        }
                    }
                }
            }
            audioRecord.stop()
            audioRecord.release()
        }.start()
    }

    fun resnetRealTimeRecordAndClassify() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * RECORDING_TIME

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        // 상태 표시
        runOnUiThread {
            mainViewModel.setResultText("녹음 중...")
        }

        Thread {
            isListening = true  // 스레드 실행 시작
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("MainActivity", "AudioRecord 초기화 실패")
                runOnUiThread {
                    mainViewModel.setResultText("녹음 초기화 실패")
                }
                return@Thread
            }

            val audioBuffer = ShortArray(bufferSize / 2)
            val slidingWindowBuffer = FloatArray(WINDOW_SIZE)
            var bufferPosition = 0

            audioRecord.startRecording()

            // 실시간으로 데이터를 읽어들여 모델로 전달
            while (isListening) {
                val readSize = audioRecord.read(audioBuffer, 0, audioBuffer.size)
                if (readSize > 0) {
                    for (i in 0 until readSize) {
                        slidingWindowBuffer[bufferPosition] = audioBuffer[i] / 32768.0f
                        bufferPosition++

                        // 슬라이딩 윈도우가 채워졌으면 호출어 검출을 수행
                        if (bufferPosition >= WINDOW_SIZE) {
                            bufferPosition = 0

                            try {
                                val classifier = ResnetClassifier(this)
                                val results = classifier.classifyAudio(slidingWindowBuffer)
                                val accuracy = ThresholdUtil.checkTrigger(results)

                                // 정확도 값을 실시간으로 화면에 표시
                                runOnUiThread {
                                    val percentage = String.format("%.2f%%", accuracy * 100)
                                    mainViewModel.setResultText("확률값: $percentage")
                                }

                                // 호출어가 감지되면 팝업을 띄우고 스레드를 중단
                                if (accuracy >= THRESHOLD) {
                                    runOnUiThread {
                                        showSuccessDialog()
                                    }
                                    isListening = false  // 스레드 중단
                                    break  // 루프 종료
                                }
                            } catch (e: Exception) {
                                Log.e("MainActivity", "분류 중 오류 발생", e)
                                runOnUiThread {
                                    mainViewModel.setResultText("분류 중 오류가 발생했습니다: " + e.message)
                                }
                            }

                            // 슬라이딩 윈도우를 50% 이동시키기 위해 이전 데이터를 복사
                            System.arraycopy(slidingWindowBuffer, STEP_SIZE, slidingWindowBuffer, 0, WINDOW_SIZE - STEP_SIZE)
                            bufferPosition = WINDOW_SIZE - STEP_SIZE
                        }
                    }
                }
            }
            audioRecord.stop()
            audioRecord.release()
        }.start()
    }


    // 호출어 인식 성공 시 보여줄 팝업
    private fun showSuccessDialog() {
        AlertDialog.Builder(this)
            .setTitle("호출어 인식 성공")
            .setMessage("호출어가 성공적으로 인식되었습니다!")
            .setPositiveButton("확인") { dialog, _ ->
                dialog.dismiss()  // 팝업 닫기
                startRecordingWithModel()  // 스레드 재시작
            }
            .setCancelable(false)
            .show()
    }

    // 모델을 사용하여 음성 데이터를 분류하는 함수
    fun resnetClassify() {
        // AudioRecord 초기화 및 녹음 설정
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * RECORDING_TIME;

        // 녹음 권한 확인
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        // UI에 녹음 상태 표시
        runOnUiThread {
            mainViewModel.setResultText("녹음 중...")
        }

        // 백그라운드 스레드에서 녹음 및 분류 실행
        Thread(Runnable {
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("MainActivity", "AudioRecord 초기화 실패")
                runOnUiThread {
                    mainViewModel.setResultText("녹음 초기화 실패")
                }
                return@Runnable
            }

            val audioBuffer = ShortArray(WINDOW_SIZE) // 녹음 샘플을 저장할 버퍼

            // 녹음 시작
            audioRecord.startRecording()
            audioRecord.read(audioBuffer, 0, audioBuffer.size)
            audioRecord.stop()
            audioRecord.release()

            // short 배열을 float 배열로 변환 (정규화 포함)
            val audioData = FloatArray(WINDOW_SIZE)

            for (i in audioData.indices) {
                audioData[i] = audioBuffer[i] / 32768.0f  // 16비트 정규화
            }

            // 입력 데이터 준비 완료
            try {
                // ResnetClassifier 초기화
                val classifier = ResnetClassifier(this)

                val results = classifier.classifyAudio(audioData)
                val accuracy = ThresholdUtil.checkTrigger(results)

                val percentage = String.format("%.2f%%", accuracy * 100)

                Log.e("getLabel : ", classifier.getLabel(results))

                val resultText = StringBuilder()
                resultText.append(classifier.getLabel(results))
                resultText.append(" : " )
                resultText.append(percentage)

                val finalResult = resultText.toString()
                runOnUiThread {
                    mainViewModel.setResultText(finalResult)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "분류 중 오류 발생", e)
                runOnUiThread {
                    mainViewModel.setResultText("분류 중 오류가 발생했습니다: " + e.message)
                }
            }
        }).start()
    }
    
    fun resnetFromFile(){
        val classifier = ResnetClassifier(this)

        val result = classifier.classifyFromFile("data/left_2.wav");
    }

    // 버튼 리스너 기반 -> 음성인식 코드 구현
    fun recordAndClassify() {
        // 샘플 레이트 16KHz(16000Hz)

        val sampleRate = 16000

        // 녹음 설정 시간 ( 1초로 설정 )
        val recordingTime = 1

        // 샘플 갯수 계산
        val totalSamples = sampleRate * recordingTime

        // 최소 버퍼 크기를 얻어옴
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        // 녹음 권한이 있는지 재확인
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        // 녹음 상태 표시해주기 (스레드 별도 설정)
        runOnUiThread {
            mainViewModel.setResultText("녹음 중...")
        }

        // 백그라운드 스레드에서 녹음 및 분류 실행

        Thread(Runnable {
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,  // 마이크에서 오디오 소스 가져옴
                sampleRate,  // 샘플레이트 설정 (16kHz)
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize // 버퍼 크기 설정
            )
            // AudioRecord 초기화 실패 시 로그 출력 및 종료
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("MainActivity", "AudioRecord 초기화 실패")
                runOnUiThread {
                    mainViewModel.setResultText("녹음 초기화 실패")
                }
                return@Runnable
            }

            // 녹음할 샘플을 저장할 버퍼 생성
            val audioBuffer = ShortArray(totalSamples)

            // 녹음 시작
            audioRecord.startRecording()

            // 녹음 데이터 읽기
            audioRecord.read(audioBuffer, 0, audioBuffer.size)

            // 녹음 종료 & 리소스 해제
            audioRecord.stop()
            audioRecord.release()

            Log.d("원시 데이터", audioBuffer.contentToString())

            // short 배열을 float 배열로 변환 (정규화 포함)
            val audioData = FloatArray(16000)
            for (i in audioData.indices) {
                // 16비트 데이터 정규화 (-1.0 ~ 1.0 값으로 맞춰줌)
                audioData[i] = audioBuffer[i] / 32768.0f
            }

            // 입력 음성 데이터 값 로그
            try {
                // 자체 AudioClassifier를 사용하여 분류
                val classifier = AudioClassifier(this)

                // 입력 데이터를 분류 모델의 형식에 맞게 변환
                val inputBuffer = classifier.createInputBuffer(audioData)
                val results = classifier.classify(inputBuffer)

                // 결과 문자열 생성 (결과 값 포맷팅)
                val resultText = StringBuilder()

                resultText.append(Arrays.toString(results));

                val finalResult = resultText.toString()

                // 결과 출력
                runOnUiThread {
                    mainViewModel.setResultText(finalResult)
                }
                Log.d("resultText", finalResult)
            } catch (e: Exception) {
                Log.e("MainActivity", "분류 중 오류 발생", e)
                runOnUiThread {
                    mainViewModel.setResultText("분류 중 오류가 발생했습니다: " + e.message)
                }
            }
        }).start()
    }


}



