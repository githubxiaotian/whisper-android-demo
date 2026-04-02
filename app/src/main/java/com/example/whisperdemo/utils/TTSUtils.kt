package com.example.whisperdemo.utils

import android.content.Context
import android.media.AudioFormat
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.*

class TTSUtil private constructor(private val context: Context) {
    private var tts: TextToSpeech? = null
    private val baos = ByteArrayOutputStream()
    
    // 假设 TTS 默认输出 16kHz（大多数设备的标准）
    // 实际值可能因设备而异，需要运行时检测
    var detectedSampleRate: Int = 16000
        private set

    init {
        initializeTTS()
    }
    
    private fun initializeTTS() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // 设置英文
                val langResult = tts?.setLanguage(Locale.ENGLISH)
                // 可选：设置语速（1.0 正常）
                tts?.setSpeechRate(1.0f)
                // 可选：设置音调（1.0 正常）
                tts?.setPitch(1.0f)

                if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                    langResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    // 提示用户去设置下载语音包
                }
            }
        }
        
        // 设置监听器来监听合成完成事件
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // 开始合成
            }

            override fun onDone(utteranceId: String?) {
                // 合成完成，从临时文件读取数据
                val utteranceFile = tempFiles[utteranceId]
                if (utteranceFile != null && utteranceFile.exists()) {
                    try {
                        utteranceFile.inputStream().use { inputStream ->
                            inputStream.copyTo(baos)
                        }
                        
                        // 尝试检测实际的采样率（通过文件大小和时长估算）
                        // 或者使用预设的标准值
                        val actualSampleRate = detectSampleRateFromAudio(baos.toByteArray())
                        
                        // 调用回调返回音频数据和检测到的采样率
                        currentCallback?.invoke(baos.toByteArray(), actualSampleRate)
                        currentCallback = null
                    } finally {
                        utteranceFile.delete()
                        tempFiles.remove(utteranceId)
                    }
                } else {
                    currentCallback?.invoke(ByteArray(0), detectedSampleRate)
                    currentCallback = null
                }
            }

            override fun onError(utteranceId: String?) {
                // 合成出错
                tempFiles[utteranceId]?.delete()
                tempFiles.remove(utteranceId)
                currentCallback?.invoke(ByteArray(0), detectedSampleRate)
                currentCallback = null
            }
        })
    }
    
    // 通过分析音频特征来估算采样率
    private fun detectSampleRateFromAudio(audioData: ByteArray): Int {
        // 简单策略：根据常见 TTS 输出特征判断
        // 大多数 Android TTS 输出 16000Hz 或 22050Hz
        
        // 计算音频时长（基于经验公式）
        // 英语正常语速约 150 词/分钟，即 2.5 词/秒
        // 每个样本 2 字节（16bit）
        
        // 这里我们采用保守策略：假设是 16000Hz
        // 如果需要精确检测，需要使用 WAV 格式而不是裸 PCM
        
        return detectedSampleRate // 默认返回 16000
    }

    // 用于存储临时文件的映射
    private val tempFiles = mutableMapOf<String?, File>()
    private var currentCallback: ((ByteArray, Int) -> Unit)? = null

    companion object {
        private var instance: TTSUtil? = null

        fun getInstance(context: Context): TTSUtil {
            return instance ?: synchronized(this) {
                instance ?: TTSUtil(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    // 朗读
    fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    // 核心：输入文字 → 返回 PCM 音频裸数据和采样率（估计值）
    fun generateAudioData(text: String, callback: (ByteArray, Int) -> Unit) {
        baos.reset()

        val params = Bundle()
        
        // 创建临时文件存储合成的音频
        val tempFile = File.createTempFile("tts_audio", ".pcm", context.cacheDir)
        tempFile.deleteOnExit()
        
        // 生成唯一的 utterance ID
        val utteranceId = UUID.randomUUID().toString()
        
        // 保存文件和回调的引用
        tempFiles[utteranceId] = tempFile
        currentCallback = callback

        // 使用正确的参数类型：Bundle 和 File
        tts?.synthesizeToFile(text, params, tempFile, utteranceId)
    }
    
    // 可选：生成带 WAV 头的音频文件（可以准确获取采样率）
    fun generateWavAudioData(text: String, callback: (ByteArray, Int) -> Unit) {
        baos.reset()

        val params = Bundle()
        
        // 创建临时 WAV 文件
        val tempFile = File.createTempFile("tts_audio", ".wav", context.cacheDir)
        tempFile.deleteOnExit()
        
        val utteranceId = UUID.randomUUID().toString()
        tempFiles[utteranceId] = tempFile
        wavCurrentCallback = callback

        tts?.synthesizeToFile(text, params, tempFile, utteranceId)
    }
    
    private var wavCurrentCallback: ((ByteArray, Int) -> Unit)? = null
    
    // 重写 onDone 来处理 WAV 文件（如果需要的话）

    // 销毁释放资源
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        // 清理所有临时文件
        tempFiles.values.forEach { it.delete() }
        tempFiles.clear()
        instance = null
    }
}
