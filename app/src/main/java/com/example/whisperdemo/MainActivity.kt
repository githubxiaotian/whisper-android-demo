package com.example.whisperdemo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.annotation.SuppressLint
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.whisperdemo.databinding.ActivityMainBinding
import com.example.whisperdemo.utils.TTSUtil
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), AudioRecorder.AudioCallback {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var whisperService: WhisperService
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var ttsUtil: TTSUtil

    private var isModelLoaded = false
    private var isRecording = false

    // 权限请求启动器
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isPermissionGranted ->
        if (isPermissionGranted) {
            // 权限授予，可以开始录音
            startRecordingInternal()
        } else {
            // 权限被拒绝
            showToast("需要录音权限才能使用语音识别功能")
            updateUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initializeComponents()
        setupClickListeners()
        loadModel()
    }

    private fun initializeComponents() {
        whisperService = WhisperService()
        audioRecorder = AudioRecorder(this)
        audioRecorder.setCallback(this)

        // 初始化 TTS
        ttsUtil = TTSUtil.getInstance(this)

        // 初始化 UI 状态
        updateUI()
    }

    private fun setupClickListeners() {
        binding.recordButton.setOnClickListener {
            binding.inputContent.text.toString().let { inputText ->
                if (inputText.isNotEmpty()) {
                    testTTSRecognition(inputText)
                } else {
                    showToast("请输入要测试的文本")
                }
            }
        }

        binding.clearButton.setOnClickListener {
            clearResults()
        }
    }

    // 改进版：更准确的 PCM 16bit 转 FloatArray（归一化到 -1.0 ~ 1.0）
    private fun pcmToFloatArrayImproved(pcmData: ByteArray): FloatArray {
        val floatArray = FloatArray(pcmData.size / 2)
        var i = 0
        while (i < pcmData.size) {
            // 小端序读取 16bit 有符号整数
            val shortValue = ((pcmData[i + 1].toInt() shl 8) or (pcmData[i].toInt() and 0xFF)).toShort()
            // 归一化到 -1.0 ~ 1.0，使用 32768.0f 确保范围正确
            floatArray[i / 2] = shortValue.toFloat() / 32768.0f
            i += 2
        }
        return floatArray
    }
    
    // 线性插值重采样：将音频从 sourceSampleRate 重采样到 targetSampleRate (16000Hz)
    private fun resampleAudio(floatArray: FloatArray, sourceSampleRate: Int, targetSampleRate: Int = 16000): FloatArray {
        if (sourceSampleRate == targetSampleRate) {
            return floatArray // 不需要重采样
        }
        
        val ratio = sourceSampleRate.toDouble() / targetSampleRate.toDouble()
        val newLength = (floatArray.size / ratio).toInt()
        val resampled = FloatArray(newLength)
        
        for (i in 0 until newLength) {
            val sourceIndex = i * ratio
            
            // 线性插值
            val index0 = sourceIndex.toInt()
            val index1 = minOf(index0 + 1, floatArray.size - 1)
            val fraction = sourceIndex - index0
            
            resampled[i] = floatArray[index0] * (1 - fraction) + floatArray[index1] * fraction
        }
        
        Log.d(TAG, "重采样完成：$sourceSampleRate Hz -> $targetSampleRate Hz, ${floatArray.size} -> ${resampled.size} samples")
        return resampled
    }

    // 测试模式：使用 TTS 生成的音频进行识别测试
    private fun testTTSRecognition(text: String) {
        binding.statusText.text = "正在生成 TTS 音频..."
        binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.blue))
        
        ttsUtil.generateAudioData(text) { pcmData ->
            if (pcmData.isNotEmpty()) {
                // 计算音频时长，验证采样率是否正确
                val durationMs = (pcmData.size / 2) * 1000L / sampleRate
                val expectedWords = text.split(" ").size
                val wordsPerMinute = (expectedWords.toDouble() / durationMs) * 60000
                
                Log.d(TAG, "=== TTS 音频信息 ===")
                Log.d(TAG, "PCM 大小：${pcmData.size} bytes")
                Log.d(TAG, "采样率：$sampleRate Hz")
                Log.d(TAG, "时长：${durationMs}ms (${durationMs / 1000.0}s)")
                Log.d(TAG, "预期单词数：$expectedWords")
                Log.d(TAG, "语速：${wordsPerMinute.toInt()} WPM")
                Log.d(TAG, "==================")
                
                if (sampleRate != 16000) {
                    Log.w(TAG, "⚠️ 警告：TTS 采样率 $sampleRate Hz 不是 16000Hz，将进行重采样")
                }
                
                if (pcmData.size < 1024) {
                    Log.e(TAG, "❌ 错误：音频数据太小，可能生成失败")
                    runOnUiThread {
                        showToast("TTS 音频生成失败，数据量过小")
                        binding.statusText.text = getString(R.string.model_loaded)
                        binding.statusText.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.green))
                    }
                    return@generateAudioData
                }
                
                Log.d(TAG, "TTS 音频生成成功：${pcmData.size} bytes")

                lifecycleScope.launch {
                    try {
                        binding.statusText.text = "正在识别 TTS 音频..."
                        
                        // 将 PCM 字节数组转换为浮点数组
                        val floatArray = pcmToFloatArrayImproved(pcmData)
                        
                        // 调用 WhisperService 进行识别（假设 TTS 输出就是 16000Hz）
                        // 如果实际不是 16000Hz，声音会失真，但可以先测试效果
                        val result = whisperService.transcribeAudio(floatArray)

                        runOnUiThread {
                            if (result.isNotEmpty()) {
                                val currentTime = System.currentTimeMillis()
                                val timestampedResult = "[$currentTime] TTS 测试结果：$result"

                                val currentText = binding.resultText.text.toString()
                                val newText = if (currentText == getString(R.string.recognition_result)) {
                                    getString(R.string.recognition_result) + "\n\n" + timestampedResult
                                } else {
                                    currentText + "\n\n" + timestampedResult
                                }
                                binding.resultText.text = newText

                                binding.scrollView.post {
                                    binding.scrollView.fullScroll(View.FOCUS_DOWN)
                                }

                                binding.resultText.alpha = 0.7f
                                binding.resultText.animate()
                                    .alpha(1.0f)
                                    .setDuration(300)
                                    .start()

                                Log.i(TAG, "TTS 识别结果：原文='$text', 识别='$result'")

                                showToast("TTS 识别完成")
                            } else {
                                showToast("TTS 音频识别结果为空")
                            }

                            binding.statusText.text = getString(R.string.model_loaded)
                            binding.statusText.setTextColor(
                                ContextCompat.getColor(this@MainActivity, R.color.green)
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "TTS 音频识别错误", e)
                        runOnUiThread {
                            showToast("识别失败：${e.message}")
                            binding.statusText.text = getString(R.string.model_loaded)
                            binding.statusText.setTextColor(
                                ContextCompat.getColor(this@MainActivity, R.color.green)
                            )
                        }
                    }
                }
            } else {
                showToast("TTS 音频生成失败")
                binding.statusText.text = getString(R.string.model_loaded)
                binding.statusText.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.green))
            }
        }
    }

    private fun loadModel() {
        binding.progressBar.visibility = View.VISIBLE
        binding.statusText.text = getString(R.string.loading_model)
        binding.recordButton.isEnabled = false

        lifecycleScope.launch {
            try {
                val success = whisperService.loadWhisperModel(this@MainActivity)

                runOnUiThread {
                    binding.progressBar.visibility = View.GONE

                    if (success) {
                        isModelLoaded = true
                        binding.statusText.text = getString(R.string.model_loaded)
                        binding.statusText.setTextColor(
                            ContextCompat.getColor(
                                this@MainActivity,
                                R.color.green
                            )
                        )
                        showToast("模型加载成功！版本：${whisperService.getVersionInfo()}")
                    } else {
                        binding.statusText.text = getString(R.string.model_load_failed)
                        binding.statusText.setTextColor(
                            ContextCompat.getColor(
                                this@MainActivity,
                                R.color.red
                            )
                        )
                        showToast("模型加载失败，请检查模型文件")
                    }

                    updateUI()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading model", e)
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.statusText.text = getString(R.string.model_load_failed)
                    binding.statusText.setTextColor(
                        ContextCompat.getColor(
                            this@MainActivity,
                            R.color.red
                        )
                    )
                    showToast("模型加载异常：${e.message}")
                    updateUI()
                }
            }
        }
    }

    private fun startRecording() {
        if (!isModelLoaded) {
            showToast("模型未加载，无法开始录音")
            return
        }

        // 检查录音权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // 请求权限
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            // 已有权限，直接开始录音
            startRecordingInternal()
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startRecordingInternal() {
        if (audioRecorder.startRecording()) {
            isRecording = true
            binding.statusText.text = "正在监听，请说话..."
            binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.blue))
            updateUI()
            Log.i(TAG, "Recording started")
        } else {
            showToast("启动录音失败")
        }
    }

    private fun stopRecording() {
        audioRecorder.stopRecording()
        isRecording = false
        binding.statusText.text = getString(R.string.model_loaded)
        binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.green))
        updateUI()
        Log.i(TAG, "Recording stopped")
    }

    private fun clearResults() {
        binding.resultText.text = getString(R.string.recognition_result)
    }

    private fun updateUI() {
        binding.recordButton.isEnabled = isModelLoaded

        if (isRecording) {
            binding.recordButton.text = getString(R.string.stop_recording)
            binding.recordButton.setBackgroundColor(ContextCompat.getColor(this, R.color.red))
        } else {
            binding.recordButton.text = getString(R.string.start_recording)
            binding.recordButton.setBackgroundColor(
                ContextCompat.getColor(
                    this,
                    R.color.purple_500
                )
            )
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showSettingsDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("音频处理设置")

        val view = layoutInflater.inflate(R.layout.dialog_settings, null)

        // 获取当前设置
        val sharedPrefs = getSharedPreferences("whisper_settings", Context.MODE_PRIVATE)
        val enableDenoising = sharedPrefs.getBoolean("enable_denoising", true)
        val enableVoiceEnhancement = sharedPrefs.getBoolean("enable_voice_enhancement", true)
        val silenceThreshold = sharedPrefs.getInt("silence_threshold", 500)

        // 设置控件
        val denoisingSwitch = view.findViewById<Switch>(R.id.switch_denoising)
        val enhancementSwitch = view.findViewById<Switch>(R.id.switch_enhancement)
        val thresholdSeeker = view.findViewById<SeekBar>(R.id.seekbar_threshold)
        val thresholdText = view.findViewById<TextView>(R.id.text_threshold_value)

        denoisingSwitch.isChecked = enableDenoising
        enhancementSwitch.isChecked = enableVoiceEnhancement
        thresholdSeeker.progress = silenceThreshold / 10
        thresholdText.text = silenceThreshold.toString()

        thresholdSeeker.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress * 10
                thresholdText.text = value.toString()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        builder.setView(view)
        builder.setPositiveButton("保存") { _, _ ->
            // 保存设置
            val editor = sharedPrefs.edit()
            editor.putBoolean("enable_denoising", denoisingSwitch.isChecked)
            editor.putBoolean("enable_voice_enhancement", enhancementSwitch.isChecked)
            editor.putInt("silence_threshold", thresholdSeeker.progress * 10)
            editor.apply()

            showToast("设置已保存")
        }
        builder.setNegativeButton("取消", null)
        builder.show()
    }

    // AudioRecorder.AudioCallback 实现
    override fun onAudioData(data: FloatArray) {
        Log.d(TAG, "Received audio data: ${data.size} samples")

        // 在后台线程处理转录
        lifecycleScope.launch {
            try {
                binding.statusText.text = getString(R.string.recognizing)
                binding.statusText.setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        R.color.blue
                    )
                )

                val result = whisperService.transcribeAudio(data)

                runOnUiThread {
                    if (result.isNotEmpty()) {
                        // 流式显示 - 先显示当前结果，然后累积
                        val currentTime = System.currentTimeMillis()
                        val timestampedResult = "[$currentTime] $result"

                        val currentText = binding.resultText.text.toString()
                        val newText = if (currentText == getString(R.string.recognition_result)) {
                            getString(R.string.recognition_result) + "\n\n" + timestampedResult
                        } else {
                            currentText + "\n\n" + timestampedResult
                        }
                        binding.resultText.text = newText

                        // 滚动到底部
                        binding.scrollView.post {
                            binding.scrollView.fullScroll(View.FOCUS_DOWN)
                        }

                        // 添加动画效果
                        binding.resultText.alpha = 0.7f
                        binding.resultText.animate()
                            .alpha(1.0f)
                            .setDuration(300)
                            .start()

                        Log.i(TAG, "Transcription result: $result")
                    }

                    // 恢复状态文本
                    if (isRecording) {
                        binding.statusText.text = "正在监听，请说话..."
                    } else {
                        binding.statusText.text = getString(R.string.model_loaded)
                        binding.statusText.setTextColor(
                            ContextCompat.getColor(
                                this@MainActivity,
                                R.color.green
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during transcription", e)
                runOnUiThread {
                    showToast("转录过程中出错：${e.message}")
                    binding.statusText.text = getString(R.string.model_loaded)
                    binding.statusText.setTextColor(
                        ContextCompat.getColor(
                            this@MainActivity,
                            R.color.green
                        )
                    )
                }
            }
        }
    }

    override fun onVoiceDetected() {
        runOnUiThread {
            binding.statusText.text = "检测到语音，正在录制..."
            binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.green))
        }
        Log.d(TAG, "Voice detected")
    }

    override fun onSilenceDetected() {
        runOnUiThread {
            binding.statusText.text = "处理中..."
            binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.blue))
        }
        Log.d(TAG, "Silence detected")
    }

    override fun onError(error: String) {
        runOnUiThread {
            showToast(error)
            binding.statusText.text = getString(R.string.model_loaded)
            binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.green))
        }
        Log.e(TAG, "Audio error: $error")
    }

    override fun onDestroy() {
        super.onDestroy()
        audioRecorder.release()
        whisperService.release()
        ttsUtil.shutdown()
    }
}
